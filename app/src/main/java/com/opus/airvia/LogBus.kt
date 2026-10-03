package com.opus.airvia

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app log bus: the AP2 stack and the cast engine log here; the UI
 * renders the lines and offers copy/share (our diagnostic lifeline).
 */
object LogBus {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val buffer = ArrayList<String>()
    private const val MAX_LINES = 400
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val line = "${fmt.format(Date())} $message"
        try {
            android.util.Log.i("airvia", message)
        } catch (_: Exception) {
        }
        synchronized(buffer) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) buffer.removeAt(0)
        }
        for (l in listeners) {
            try {
                l(line)
            } catch (_: Exception) {
            }
        }
    }

    fun logBlock(header: String, block: String) {
        log(header)
        for (line in block.lines()) log(line)
    }

    fun addListener(l: (String) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    fun snapshot(): List<String> = synchronized(buffer) { ArrayList(buffer) }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }
}
