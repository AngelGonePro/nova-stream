package net.cosmoscraft.nova

import android.os.Handler
import android.os.Looper

/**
 * A direct response to a real, repeated obstacle: Logcat access through
 * Android Studio's UI has been hard to find across several rounds of this.
 * This sidesteps that entirely — every diagnostic line that already goes to
 * Log.i/Log.e also lands here, and MainActivity displays it directly on the
 * phone screen. No IDE navigation needed at all; just look at the app.
 *
 * Deliberately tiny — a fixed-size ring buffer and a single listener
 * callback, nothing more. This exists purely as a stopgap for getting real
 * diagnostic visibility without fighting Android Studio's UI; Logcat (via
 * Alt+6) is still the better tool once it's actually reachable.
 */
object DebugOverlay {
    // Raised from 60 — with console messages and every bufferClientError
    // call now automatically flowing here too (see MainActivity's
    // WebChromeClient and app.js's bufferClientError, both wired to this
    // same sink), a real diagnostic sequence needs more room than 60 lines
    // held, or exactly the earliest, most relevant context could get pushed
    // out before anyone gets a chance to read it.
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private var listener: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        if (l != null) l(currentText())
    }

    fun emit(tag: String, message: String) {
        val line = "[$tag] $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val snapshot = currentText()
        mainHandler.post { listener?.invoke(snapshot) }
    }

    private fun currentText(): String = synchronized(lines) { lines.joinToString("\n") }
}
