package com.opus.airvia

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Airvia — stream ALL your Android audio to AirPlay 2 speakers.
 *
 * Pick a discovered speaker (or enter an IP), grant the one-time
 * capture consent Android requires, and every app's sound plays on the
 * speaker. Programmatic UI, probe-style: no resources, easy to audit.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_CAPTURE = 42
        private const val REQ_RECORD = 41
        private const val REQ_NOTIF = 43
    }

    private lateinit var statusTv: TextView
    private lateinit var speakersBox: LinearLayout
    private lateinit var volumeBar: SeekBar
    private lateinit var logTv: TextView
    private lateinit var logScroll: ScrollView

    private var discovery: SpeakerDiscovery? = null
    private var pendingSpeaker: Speaker? = null
    private var removeLogListener: (() -> Unit)? = null
    @Volatile
    private var resumed = false

    private val engineListener: (CastEngine.State) -> Unit = {
        runOnUiThread { refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Airvia"
        buildUi()
        CastEngine.addListener(engineListener)
        removeLogListener = LogBus.addListener {
            if (resumed) runOnUiThread { renderLog() }
        }
        discovery = SpeakerDiscovery(this) { speakers ->
            runOnUiThread { renderSpeakers(speakers) }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        LogBus.log("Airvia ready — pick a speaker to cast all device audio")
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        discovery?.start()
        renderLog()
        refreshStatus()
    }

    override fun onPause() {
        resumed = false
        discovery?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        CastEngine.removeListener(engineListener)
        removeLogListener?.invoke()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "Airvia"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Stream all your Android audio to AirPlay 2 speakers (HomePod included). " +
                "Free and open source — no trials, no noise, no account."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        })
        statusTv = TextView(this).apply { textSize = 16f }
        root.addView(statusTv)

        root.addView(TextView(this).apply {
            text = "Speakers on your Wi-Fi"
            textSize = 18f
            setPadding(0, 20, 0, 8)
        })
        speakersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(speakersBox)
        root.addView(TextView(this).apply {
            text = "Scanning… (same Wi-Fi as the speaker)"
            textSize = 13f
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        row.addView(Button(this).apply {
            text = "Rescan"
            setOnClickListener {
                discovery?.stop()
                discovery?.start()
                LogBus.log("[ui] rescan")
            }
        })
        row.addView(Button(this).apply {
            text = "Stop casting"
            setOnClickListener { stopCasting() }
        })
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "Speaker volume"
            textSize = 18f
            setPadding(0, 20, 0, 4)
        })
        volumeBar = SeekBar(this).apply {
            max = 100
            progress = CastEngine.volumePct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) CastEngine.setVolume(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        root.addView(volumeBar)
        root.addView(TextView(this).apply {
            text = "Tip: while casting, the phone's side buttons control the speaker — " +
                "even from the lock screen."
            textSize = 13f
        })

        root.addView(TextView(this).apply {
            text = "Connect by IP"
            textSize = 18f
            setPadding(0, 20, 0, 8)
        })
        val ipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hostEt = EditText(this).apply {
            hint = "10.0.0.148"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        val portEt = EditText(this).apply {
            setText("7000")
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipRow.addView(hostEt)
        ipRow.addView(portEt)
        root.addView(ipRow)
        root.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                val host = hostEt.text.toString().trim()
                val port = portEt.text.toString().toIntOrNull() ?: 7000
                if (host.isEmpty()) {
                    LogBus.log("[ui] enter a speaker IP first")
                } else {
                    beginCast(Speaker(host, host, port))
                }
            }
        })

        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 8)
        }
        logRow.addView(TextView(this).apply {
            text = "Connection log"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        logRow.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener { copyLog() }
        })
        logRow.addView(Button(this).apply {
            text = "Share"
            setOnClickListener { shareLog() }
        })
        logRow.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                LogBus.clear()
                renderLog()
            }
        })
        root.addView(logRow)
        logTv = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 700,
            )
            addView(logTv)
        }
        root.addView(logScroll)

        root.addView(TextView(this).apply {
            text = "Notes: Android only lets apps capture audio that allows it — " +
                "some apps and DRM content stay silent (platform rule, same for every " +
                "caster). AirPlay adds about a second of delay, so it's best for " +
                "music and podcasts, not lip-sync video."
            textSize = 12f
            setPadding(0, 16, 0, 0)
        })

        val outer = ScrollView(this).apply { addView(root) }
        setContentView(outer)
    }

    private fun renderSpeakers(speakers: List<Speaker>) {
        speakersBox.removeAllViews()
        for (sp in speakers) {
            speakersBox.addView(Button(this).apply {
                text = "🔊 ${sp.name}  (${sp.host}:${sp.port})"
                setOnClickListener { beginCast(sp) }
            })
        }
    }

    private fun refreshStatus() {
        val st = CastEngine.state
        val sp = CastEngine.speaker
        statusTv.text = when (st) {
            CastEngine.State.IDLE -> "Not casting"
            CastEngine.State.CONNECTING -> "Connecting to ${sp?.name}…"
            CastEngine.State.STREAMING -> "▶ Casting all audio to ${sp?.name}"
            CastEngine.State.ERROR -> "Error: ${CastEngine.lastError}"
        }
        if (volumeBar.progress != CastEngine.volumePct) {
            volumeBar.progress = CastEngine.volumePct
        }
    }

    private fun renderLog() {
        logTv.text = LogBus.snapshot().joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // Cast flow: permissions -> capture consent -> foreground service
    // ------------------------------------------------------------------

    private fun beginCast(sp: Speaker) {
        pendingSpeaker = sp
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD)
            return
        }
        requestCaptureConsent()
    }

    private fun requestCaptureConsent() {
        val sp = pendingSpeaker ?: return
        LogBus.log("[ui] casting to '${sp.name}' — approve the capture prompt")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                requestCaptureConsent()
            } else {
                LogBus.log("[ui] microphone permission denied — needed by Android for audio capture")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val sp = pendingSpeaker
        if (resultCode == RESULT_OK && data != null && sp != null) {
            startForegroundService(CastService.startIntent(this, resultCode, data, sp))
        } else {
            LogBus.log("[ui] capture consent denied — cannot cast without it")
        }
        pendingSpeaker = null
    }

    private fun stopCasting() {
        try {
            startService(CastService.stopIntent(this))
        } catch (_: Exception) {
        }
        CastEngine.stop()
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // Volume keys in the foreground drive the speaker too
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && CastEngine.isActive
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5
                CastEngine.setVolume(CastEngine.volumePct + delta)
                refreshStatus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------
    // Log actions
    // ------------------------------------------------------------------

    private fun logText(): String = LogBus.snapshot().joinToString("\n")

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("airvia-log", logText()))
        LogBus.log("[ui] log copied")
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText())
        }
        startActivity(Intent.createChooser(send, "Share Airvia log"))
    }
}
