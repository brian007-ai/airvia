package com.opus.airvia.dlna

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA/UPnP MediaRenderer control: SSDP discovery plus SOAP AVTransport
 * (point the renderer at Airvia's local WAV stream, Play, Stop) and
 * RenderingControl volume. Sonos speakers are found too — a Sonos
 * ZonePlayer is a MediaRenderer with the same AVTransport service.
 *
 * Generalized from Outro's proven SonosDlna (same M-SEARCH rounds, same
 * description parsing, same SOAP envelope shape). All functions do
 * blocking network I/O; callers must use background threads. The parsing
 * helpers are pure and unit-tested.
 *
 * Device behavior cannot be verified from the build VM (multicast send
 * is blocked there); this is a faithful port of on-device-proven code.
 */
object Dlna {

    data class DlnaDevice(
        val udn: String,
        val name: String,
        val location: String,
        val avTransportUrl: String,
        val renderingControlUrl: String?,
    )

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ZONE_PLAYER_ST = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private const val AVTRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING_CONTROL_SERVICE =
        "urn:schemas-upnp-org:service:RenderingControl:1"

    /**
     * Multicast M-SEARCH for MediaRenderers (and Sonos ZonePlayers).
     * Returns one entry per distinct device (deduped by UDN).
     */
    fun discover(timeoutMs: Int = 5000): List<DlnaDevice> {
        val found = linkedMapOf<String, DlnaDevice>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket()
            socket.soTimeout = 800
            val group = InetAddress.getByName(SSDP_ADDR)
            val targets = listOf(MEDIA_RENDERER_ST, ZONE_PLAYER_ST)
            val packets = targets.map { st ->
                val msg = buildMSearch(st)
                DatagramPacket(msg, msg.size, group, SSDP_PORT)
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now - lastSend > 1200) {
                    for (pkt in packets) {
                        try { socket.send(pkt) } catch (_: Exception) {}
                    }
                    lastSend = now
                }
                try {
                    val recv = DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    val location = parseSsdpLocation(
                        recv.data.decodeToString(0, recv.length),
                    ) ?: continue
                    if (found.values.any { it.location == location }) continue
                    val device = describeDevice(location) ?: continue
                    found.putIfAbsent(device.udn, device)
                } catch (_: java.net.SocketTimeoutException) {
                    // keep waiting until the deadline
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    /** Point the renderer at [streamUrl] and start playing it. */
    fun play(device: DlnaDevice, streamUrl: String, title: String) {
        val meta = didlLite(streamUrl, title)
        soap(
            device.avTransportUrl, AVTRANSPORT_SERVICE, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>",
        )
        soap(
            device.avTransportUrl, AVTRANSPORT_SERVICE, "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )
    }

    fun stop(device: DlnaDevice) {
        soap(
            device.avTransportUrl, AVTRANSPORT_SERVICE, "Stop",
            "<InstanceID>0</InstanceID>",
        )
    }

    /** Set renderer volume 0..100 via RenderingControl (best effort). */
    fun setVolume(device: DlnaDevice, percent: Int) {
        val url = device.renderingControlUrl ?: return
        soap(
            url, RENDERING_CONTROL_SERVICE, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                "<DesiredVolume>${percent.coerceIn(0, 100)}</DesiredVolume>",
        )
    }

    // ------------------------------------------------------------------
    // Pure helpers (unit-testable)
    // ------------------------------------------------------------------

    fun buildMSearch(searchTarget: String, mx: Int = 3): ByteArray =
        ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
            "MAN: \"ns=01\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $searchTarget\r\n" +
            "\r\n").toByteArray(Charsets.UTF_8)

    /** Extract the LOCATION header value from an SSDP response, or null. */
    fun parseSsdpLocation(response: String): String? {
        for (line in response.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("location", ignoreCase = true)) {
                return line.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Parsed device description: identity + both control URLs. */
    data class DeviceDesc(
        val udn: String,
        val name: String,
        val avTransportUrl: String,
        val renderingControlUrl: String?,
    )

    /**
     * Parse a device description XML: friendly name, UDN, and the
     * AVTransport / RenderingControl control URLs resolved to absolute
     * form against [locationUrl]. Null when AVTransport is absent.
     */
    fun parseDeviceDesc(xml: String, locationUrl: String): DeviceDesc? {
        return try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
            val udn = doc.getElementsByTagName("UDN").item(0)?.textContent?.trim()
                ?: return null
            val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim()
                ?: "DLNA speaker"
            val services = doc.getElementsByTagName("service")
            var avCtrl: String? = null
            var rcCtrl: String? = null
            for (i in 0 until services.length) {
                val svc = services.item(i)
                var type: String? = null
                var ctrl: String? = null
                val kids = svc.childNodes
                for (j in 0 until kids.length) {
                    when (kids.item(j).nodeName) {
                        "serviceType" -> type = kids.item(j).textContent?.trim()
                        "controlURL" -> ctrl = kids.item(j).textContent?.trim()
                    }
                }
                if (ctrl.isNullOrEmpty()) continue
                when (type) {
                    AVTRANSPORT_SERVICE -> if (avCtrl == null) avCtrl = ctrl
                    RENDERING_CONTROL_SERVICE -> if (rcCtrl == null) rcCtrl = ctrl
                }
            }
            avCtrl ?: return null
            val base = URL(locationUrl)
            DeviceDesc(
                udn = udn,
                name = name,
                avTransportUrl = URL(base, avCtrl).toString(),
                renderingControlUrl = rcCtrl?.let { URL(base, it).toString() },
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildSoapEnvelope(serviceType: String, action: String, bodyXml: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\">$bodyXml</u:$action>" +
            "</s:Body></s:Envelope>"

    fun didlLite(streamUrl: String, title: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"airvia-stream\" parentID=\"0\" restricted=\"true\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/x-wav:*\">${xmlEscape(streamUrl)}</res>" +
            "</item></DIDL-Lite>"

    fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Network internals
    // ------------------------------------------------------------------

    private fun describeDevice(locationUrl: String): DlnaDevice? {
        return try {
            val xml = httpGet(locationUrl, 4000) ?: return null
            val desc = parseDeviceDesc(xml, locationUrl) ?: return null
            DlnaDevice(
                udn = desc.udn,
                name = desc.name,
                location = locationUrl,
                avTransportUrl = desc.avTransportUrl,
                renderingControlUrl = desc.renderingControlUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "text/xml")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun soap(controlUrl: String, serviceType: String, action: String, bodyXml: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            val payload = buildSoapEnvelope(serviceType, action, bodyXml)
                .toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.let { es ->
                        val bos = ByteArrayOutputStream()
                        es.copyTo(bos)
                        bos.toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) { null }
                throw IllegalStateException("DLNA SOAP $action failed: HTTP $code ${err?.take(200)}")
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
