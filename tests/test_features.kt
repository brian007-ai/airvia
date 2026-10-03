import com.opus.airvia.BroadcastCapture
import com.opus.airvia.Dmap
import com.opus.airvia.DspProcessor
import com.opus.airvia.dlna.Dlna
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM self-tests for Airvia 1.2.0's new pure-JVM pieces:
 *  - DspProcessor: 0 dB transparency, band gain at/away from center
 *    frequency, preamp gain, clamping without wraparound, no NaN blowups
 *  - Dmap: exact tag layout, empty-field skipping, 120-byte cap
 *  - BroadcastCapture: in-order fan-out, live-edge join, lag snap-forward
 *  - Dlna: SSDP location parse, description parse, SOAP/DIDL building
 */
var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else {
        println("FAIL: $name")
        failures++
    }
}

fun sine(freq: Double, frames: Int, amp: Double = 10000.0): ShortArray {
    val out = ShortArray(frames * 2)
    for (i in 0 until frames) {
        val v = (sin(2 * Math.PI * freq * i / 44100.0) * amp).toInt().toShort()
        out[2 * i] = v
        out[2 * i + 1] = v
    }
    return out
}

fun rms(samples: ShortArray, fromFrame: Int): Double {
    var sum = 0.0
    var n = 0
    for (f in fromFrame until samples.size / 2) {
        val v = samples[2 * f].toDouble()
        sum += v * v
        n++
    }
    return sqrt(sum / n)
}

fun main() {
    // ---------------- DspProcessor ----------------------------------
    run {
        // Transparency with filters engaged but gains at 0 dB: preamp
        // 0.1 dB defeats the flat shortcut so the biquads run; expected
        // output is then the input scaled by exactly the preamp factor.
        val dsp = DspProcessor()
        dsp.setPreampDb(0.1)
        val input = sine(1000.0, 4410)
        val data = input.copyOf()
        dsp.process(data)
        val scale = Math.pow(10.0, 0.1 / 20.0)
        var maxDiff = 0
        for (i in input.indices) {
            val expected = (input[i] * scale).toInt()
            maxDiff = maxOf(maxDiff, abs(data[i] - expected))
        }
        check("dsp 0dB transparent (maxDiff $maxDiff)", maxDiff <= 3)
    }
    run {
        // +12 dB on the 60 Hz band amplifies a 60 Hz sine ~x4 at center.
        val dsp = DspProcessor()
        dsp.setBandGain(0, 12.0)
        val data = sine(60.0, 44100)
        val inRms = rms(data, 10000)
        dsp.process(data)
        val outRms = rms(data, 10000)
        val ratio = outRms / inRms
        check("dsp bass boost ratio ${"%.2f".format(ratio)}", ratio in 3.0..4.6)
    }
    run {
        // ...but leaves a 10 kHz sine almost alone.
        val dsp = DspProcessor()
        dsp.setBandGain(0, 12.0)
        val data = sine(10000.0, 44100)
        val inRms = rms(data, 10000)
        dsp.process(data)
        val ratio = rms(data, 10000) / inRms
        check("dsp bass boost spares treble (ratio ${"%.2f".format(ratio)})", ratio in 0.8..1.3)
    }
    run {
        // Preamp +6 dB doubles amplitude.
        val dsp = DspProcessor()
        dsp.setPreampDb(6.0)
        val data = sine(1000.0, 4410, 8000.0)
        val inRms = rms(data, 0)
        dsp.process(data)
        val ratio = rms(data, 0) / inRms
        check("dsp preamp x2 (ratio ${"%.2f".format(ratio)})", ratio in 1.9..2.1)
    }
    run {
        // Full-scale DC + boost + preamp clamps, never wraps sign.
        val dsp = DspProcessor()
        dsp.setBandGain(0, 12.0)
        dsp.setPreampDb(12.0)
        val data = ShortArray(8820) { 30000 }
        dsp.process(data)
        check("dsp clamps positive", data.all { it > 0 })
        val neg = ShortArray(8820) { -30000 }
        dsp.process(neg)
        check("dsp clamps negative", neg.all { it < 0 })
    }
    run {
        // Random-ish gain thrash: output stays finite and bounded.
        val dsp = DspProcessor()
        val data = sine(440.0, 44100, 20000.0)
        for (b in 0 until DspProcessor.BAND_COUNT) {
            dsp.setBandGain(b, if (b % 2 == 0) 12.0 else -12.0)
        }
        dsp.process(data)
        check(
            "dsp extreme settings bounded",
            data.all { it in -32768..32767 } && data.any { it != 0.toShort() },
        )
    }

    // ---------------- Dmap ------------------------------------------
    run {
        val body = Dmap.metadataBody("Song", "Artist", "Album")
        val expected = "minm".toByteArray() + byteArrayOf(0, 0, 0, 4) +
            "Song".toByteArray() +
            "asar".toByteArray() + byteArrayOf(0, 0, 0, 6) +
            "Artist".toByteArray() +
            "asal".toByteArray() + byteArrayOf(0, 0, 0, 5) +
            "Album".toByteArray()
        check("dmap exact layout", body.contentEquals(expected))
        check("dmap all empty", Dmap.metadataBody("", "  ", "").isEmpty())
        val partial = Dmap.metadataBody("", "A", "")
        check(
            "dmap skips empties",
            partial.size == 9 && partial.copyOf(4).toString(Charsets.US_ASCII) == "asar",
        )
        val long = Dmap.metadataBody("x".repeat(500), "", "")
        check("dmap field cap 120", long.size == 8 + 120)
        val utf = Dmap.metadataBody("Café", "", "")
        // "Café" = 5 UTF-8 bytes; length byte must say 5.
        check("dmap utf8 length", utf.size == 8 + 5 && utf[7].toInt() == 5)
    }

    // ---------------- BroadcastCapture -------------------------------
    run {
        val hub = BroadcastCapture()
        val c1 = hub.newConsumer()
        val c2 = hub.newConsumer()
        check("broadcast empty read null", c1.nextInterleaved() == null)
        val chunk = ShortArray(352 * 2) { (it % 30000).toShort() }
        hub.push(chunk)
        val a = c1.nextInterleaved()
        val b = c2.nextInterleaved()
        check(
            "broadcast fan-out identical",
            a != null && b != null && a.contentEquals(chunk) && b.contentEquals(chunk),
        )
        check("broadcast drained", c1.nextInterleaved() == null)
    }
    run {
        // Late joiner starts at the live edge, not at frame zero.
        val hub = BroadcastCapture()
        hub.push(ShortArray(352 * 4) { 7 })
        val late = hub.newConsumer()
        check("broadcast late joiner waits", late.nextInterleaved() == null)
        hub.push(ShortArray(352 * 2) { 9 })
        val got = late.nextInterleaved()
        check(
            "broadcast late joiner gets new data",
            got != null && got.all { it == 9.toShort() },
        )
    }
    run {
        // Lagging consumer snaps to the oldest retained frame.
        val hub = BroadcastCapture()
        val slow = hub.newConsumer()
        val totalFrames = 44100 * 8 + 5000 // capacity + overflow
        val ramp = ShortArray(totalFrames * 2) { i ->
            ((i / 2) % 30000).toShort()
        }
        hub.push(ramp)
        val got = slow.nextInterleaved()
        val firstFrame = (got?.get(0)?.toInt() ?: -1)
        val expectedFirst = (totalFrames - 44100 * 8) % 30000
        check(
            "broadcast snap-forward (first $firstFrame want $expectedFirst)",
            firstFrame == expectedFirst,
        )
    }
    run {
        // readFrames path (HTTP server): waits, returns what's there.
        val hub = BroadcastCapture()
        val c = hub.newConsumer()
        hub.push(ShortArray(100 * 2) { 5 })
        val got = c.readFrames(1000, 50)
        check("broadcast readFrames partial", got.size == 200 && got.all { it == 5.toShort() })
        hub.finish()
        check("broadcast consumer finished", c.finished)
    }

    // ---------------- Dlna -------------------------------------------
    run {
        val resp = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://10.0.0.50:1400/xml/device_description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        check(
            "dlna ssdp location",
            Dlna.parseSsdpLocation(resp) ==
                "http://10.0.0.50:1400/xml/device_description.xml",
        )
        check("dlna ssdp no location", Dlna.parseSsdpLocation("HTTP/1.1 200 OK\r\n\r\n") == null)
        val search = Dlna.buildMSearch(Dlna.MEDIA_RENDERER_ST).toString(Charsets.UTF_8)
        check(
            "dlna m-search shape",
            search.startsWith("M-SEARCH * HTTP/1.1") &&
                search.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"),
        )
        val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <friendlyName>Living Room</friendlyName>
    <UDN>uuid:RINCON_123</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
      </service>
    </serviceList>
  </device>
</root>"""
        val desc = Dlna.parseDeviceDesc(xml, "http://10.0.0.50:1400/xml/device_description.xml")
        check("dlna desc parsed", desc != null)
        check("dlna desc name", desc?.name == "Living Room")
        check(
            "dlna desc av url",
            desc?.avTransportUrl ==
                "http://10.0.0.50:1400/MediaRenderer/AVTransport/Control",
        )
        check(
            "dlna desc rc url",
            desc?.renderingControlUrl ==
                "http://10.0.0.50:1400/MediaRenderer/RenderingControl/Control",
        )
        val noAv = """<root><device><friendlyName>X</friendlyName><UDN>uuid:1</UDN></device></root>"""
        check("dlna desc without AVTransport", Dlna.parseDeviceDesc(noAv, "http://h/d.xml") == null)
        val env = Dlna.buildSoapEnvelope(
            "urn:schemas-upnp-org:service:AVTransport:1", "Play", "<InstanceID>0</InstanceID>")
        check(
            "dlna soap envelope",
            env.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"),
        )
        val didl = Dlna.didlLite("http://10.0.0.2:8899/stream.wav?a=1&b=2", "Tom & Jerry")
        check(
            "dlna didl escapes",
            didl.contains("a=1&amp;b=2") && didl.contains("Tom &amp; Jerry") &&
                didl.contains("audio/x-wav"),
        )
    }

    if (failures > 0) {
        println("NEW-FEATURE TESTS FAILED: $failures")
        kotlin.system.exitProcess(1)
    }
    println("ALL NEW-FEATURE CHECKS PASSED")
}
