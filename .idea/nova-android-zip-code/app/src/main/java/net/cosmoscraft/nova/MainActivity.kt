package net.cosmoscraft.nova

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * NOT tested — I have no Android SDK or emulator available in the environment
 * I wrote this in, so nothing past a syntax-level read-through has verified
 * this. Real device testing (which I cannot do myself) will very likely
 * surface things that need adjusting — buffer sizes in NativeAudioEngine
 * especially, per that file's own comment.
 */
class MainActivity : AppCompatActivity() {

    private val siteOrigin = "https://music.cosmoscraft.net"

    private lateinit var webView: WebView
    private lateinit var engine: NativeAudioEngine
    private var bridge: NativeAudioBridge? = null
    private lateinit var debugText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Real gap: I'd suggested chrome://inspect without ever actually
        // enabling it here — WebView debugging is off by default and has to
        // be turned on explicitly, or a desktop Chrome browser has nothing
        // to connect to at all. Fixed now, so that suggestion is actually
        // usable if wanted later: with the phone plugged in via USB (same
        // setup already used for Android Studio) and this app running, a
        // DESKTOP Chrome browser (not the phone) can go to chrome://inspect
        // in its own address bar and see this WebView listed there, with a
        // real DevTools console/network tab. Genuinely optional — the
        // on-device debug panel already covers the same ground now that
        // it's comprehensive.
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)

        // Direct response to a real, repeated obstacle: Logcat access
        // through Android Studio's UI has been hard to find across several
        // rounds of troubleshooting. This on-screen panel sidesteps that
        // entirely — every diagnostic line the native code already logs also
        // shows up here, directly on the phone, no IDE needed at all. A
        // small, scrollable text strip docked at the top; the WebView takes
        // the rest of the screen below it. Meant purely as a stopgap for
        // getting real visibility quickly — not a permanent UI feature.
        debugText = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(12, 8, 12, 8)
            movementMethod = ScrollingMovementMethod()
            maxLines = 8
            text = "Debug log will appear here once playback starts… (long-press to copy)"
            // Direct fix for a reported "no way to copy them" complaint —
            // long-press copies the full log text (not just what's visible
            // in this small 8-line window) to the clipboard, so it can be
            // pasted anywhere without retyping or screenshotting.
            setOnLongClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nova debug log", text.toString()))
                android.widget.Toast.makeText(this@MainActivity, "Debug log copied", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
        webView = WebView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(debugText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(140)))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        DebugOverlay.setListener { text ->
            debugText.text = text
            // Auto-scroll to the bottom so the newest line is always visible
            // without the person needing to manually scroll a tiny panel.
            val layout = debugText.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(debugText.lineCount) - debugText.height
                debugText.scrollTo(0, if (scrollAmount > 0) scrollAmount else 0)
            }
        }

        engine = NativeAudioEngine()
        engine.start()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Real, direct fix for a reported "I deployed the fix but it's
            // still not showing up" complaint. The server already sends
            // Cache-Control: no-store on every response (confirmed directly
            // in server.js, added specifically for this exact scenario) —
            // but WebView has its OWN separate cache setting, independent of
            // HTTP headers, and never explicitly setting it here left it at
            // whatever the platform default happens to be, which is not
            // guaranteed consistent across every Android version/OEM skin.
            // Set explicitly rather than trusted to line up with the
            // server's own headers — this WebView never has a legitimate
            // reason to serve a locally cached copy of a page that changes
            // as often as this one does.
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith(siteOrigin)) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            // Real, direct diagnostic added from a reported pattern: two
            // full sequences of startup/entry logs appearing back-to-back,
            // each restarting from the very beginning, with the second one
            // getting one step further (reaching the loop condition check)
            // before going silent again — and DebugOverlay (a process-wide
            // singleton, unlike the WebView's own JS state) showing BOTH
            // sequences accumulated together rather than one being
            // overwritten. That specific pattern is consistent with the
            // WebView's own renderer process crashing and Android silently
            // reloading the page — the host app process (and this
            // singleton) survives, but the page's JS runtime (generation
            // tokens, in-flight state, all of it) does not. This callback
            // exists specifically to detect that scenario; if it never
            // fires, this theory is wrong and something else is happening
            // instead — either way, this makes it visible instead of guessed.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                DebugOverlay.emit("MainActivity", "WEBVIEW RENDERER PROCESS CRASHED — didCrash=${detail.didCrash()}, rendererPriorityAtExit=${detail.rendererPriorityAtExit()}")
                // Returning true here means "I've handled it" — without a
                // new WebView instance, the OS would otherwise kill the
                // whole host app. Reloading the same URL is the simplest
                // recovery; it's also exactly why the page's JS state
                // resets silently instead of the app visibly closing.
                view.loadUrl(siteOrigin)
                return true
            }
        }

        // Real, direct response to a fair, completely valid complaint: every
        // previous round of diagnosis required adding one specific log line,
        // rebuilding, and waiting for the next report — reactive, one blind
        // spot at a time. This is the other half of the fix (the JS-side
        // half routes through bufferClientError, already wired to
        // nativeDebugLog) — WebChromeClient.onConsoleMessage catches every
        // console.log/warn/error the page ever produces at the WebView
        // level itself, including ones that might never reach the JS-side
        // interception at all. Between this and the bufferClientError
        // change, essentially everything the page does that's worth seeing
        // now shows up on-device automatically, with no future patch needed
        // per new investigation.
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                DebugOverlay.emit("Console", "[${message.messageLevel()}] ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }

        registerBridgeIfSupported()

        PlaybackService.evaluateJs = { js -> runOnUiThread { webView.evaluateJavascript(js, null) } }
        startForegroundService(Intent(this, PlaybackService::class.java))

        webView.loadUrl(siteOrigin)
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun registerBridgeIfSupported() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val b = NativeAudioBridge(webView, engine)
            bridge = b
            WebViewCompat.addWebMessageListener(
                webView,
                NativeAudioBridge.JS_OBJECT_NAME,
                setOf(siteOrigin),
                b
            )
        } else {
            DebugOverlay.emit("MainActivity", "WEB_MESSAGE_LISTENER not supported on this WebView — native audio bridge NOT registered, falling back to normal web playback")
        }
    }

    override fun onDestroy() {
        DebugOverlay.setListener(null)
        bridge?.shutdown()
        engine.stopAndFlush()
        stopService(Intent(this, PlaybackService::class.java))
        super.onDestroy()
    }
}
