package net.cosmoscraft.nova

import android.webkit.CookieManager
import android.webkit.WebView
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The JS<->native boundary. Uses WebViewCompat.addWebMessageListener with an
 * explicit allowedOriginRules restriction (see MainActivity) rather than the
 * older addJavascriptInterface — this is the currently-recommended approach
 * specifically because addJavascriptInterface has no origin verification at
 * all (exposed to every frame, including any iframe) where this listener is
 * scoped to https://music.cosmoscraft.net only. This app only ever loads
 * that one origin, but there's no reason to use the less-safe API when this
 * one is a drop-in replacement.
 *
 * Deliberately thin: JS decides WHAT to fetch and WHEN (all the existing
 * retry/backoff/quality-selection logic stays exactly where it already was,
 * proven, in app.js) — this class's only two jobs are (1) doing the actual
 * HTTP fetch, since routing bytes through the JS bridge as base64 strings
 * would add real overhead for no benefit, and (2) reporting success/failure
 * back so JS's own retry logic can act on it, the same way it already reacts
 * to a failed fetch() today.
 */
class NativeAudioBridge(
    private val webView: WebView,
    private val engine: NativeAudioEngine
) : WebViewCompat.WebMessageListener {

    companion object {
        private const val TAG = "NovaBridge"
        const val JS_OBJECT_NAME = "NovaNative"
    }

    private val fetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Real, direct fix for a reported "media info isn't updating on next
    // track / on play-pause" complaint: artwork fetches used to share
    // fetchExecutor with PCM segment downloads. A 60-second audio segment
    // can take a real, noticeable amount of time to download — every
    // media-info update (which needs this same thread to fetch the artwork
    // image) was queued up behind whatever PCM fetch happened to be running,
    // sometimes for as long as that fetch took. Separate executor means a
    // now-playing update is never blocked by an unrelated, much larger
    // audio download.
    private val metadataExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Dedicated thread for prefetching the NEXT track's first segment while
    // the current one is still playing — see handlePrefetchSegment's own
    // comment for the full design. Separate from fetchExecutor specifically
    // so a prefetch runs genuinely CONCURRENTLY with whatever segment fetch
    // the current, still-playing track is doing, rather than queuing up
    // behind it on the same single thread (which would defeat the point —
    // "start loading in the background" means actually running at the same
    // time, not just starting slightly earlier in a single-file line).
    private val prefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // The actual architecture fix's own dedicated executor — downloads one
    // track's ENTIRE PCM data, sequentially, in the background, completely
    // decoupled from playback position. Separate from fetchExecutor/
    // prefetchExecutor so this long-running download never blocks or gets
    // blocked by either of those (fetchExecutor is effectively retired by
    // this new path for normal playback, kept only for the fallback case
    // where a seek lands beyond what's been downloaded yet).
    private val trackDownloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Incremented on every new startTrackDownload call — lets an in-progress
    // download for a NOW-abandoned track notice and stop appending on its
    // own next segment boundary, the same self-terminating-on-staleness
    // principle as playbackEpoch, applied to the download side instead of
    // the playback side.
    @Volatile private var trackDownloadGeneration = 0
    // Real, direct addition for true gapless playback — a SEPARATE
    // generation counter for the next-track prefetch download, so a seek
    // or restart of the CURRENT track (which bumps trackDownloadGeneration)
    // can never accidentally cancel an in-progress prefetch of the track
    // after it. The two downloads are otherwise independent.
    @Volatile private var nextTrackDownloadGeneration = 0
    // Holds one prefetched segment's raw bytes + format, keyed by the track
    // id it belongs to — cleared once consumed (or replaced by a newer
    // prefetch). Never touches the playback queue directly; only
    // consumePrefetch (triggered once the real track transition actually
    // happens, with the correct generation established) does that.
    private data class PrefetchedData(val bytes: ByteArray, val sampleRate: Int, val channels: Int, val totalFrames: Long, val skipSamples: Long)
    @Volatile private var prefetchedTrackId: String? = null
    @Volatile private var prefetchedData: PrefetchedData? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Real, direct architectural gap found from a reported garbled/static
    // audio complaint, alongside the gain-overshoot issue: nothing here ever
    // told an in-flight fetch that it had been superseded by a newer track.
    // stopAndFlush (used on pause) clears the playback queue, but the fetch
    // itself — running on this executor, independent of that — kept
    // reading bytes and calling engine.enqueue() regardless, re-populating
    // the queue with a STALE track's data even after a flush. Every
    // 'start'/'fetchSegment' message now carries the JS side's own
    // generation counter (nativeGenerationToken, which JS already
    // increments per playTrackNative call) — handleFetchSegment checks it
    // against the current one on every read iteration and abandons the
    // fetch the moment it's no longer current, rather than continuing to
    // enqueue audio for a track that isn't playing anymore.
    @Volatile private var currentGeneration = -1

    init {
        // Real, direct fix for a reported "timeline started moving 4
        // seconds before any sound was actually audible" complaint. This
        // callback already existed for exactly this purpose — fires the
        // moment the first chunk of a new track actually reaches the
        // playback thread — but was never wired to anything at all, dead
        // code since it was written. The JS side was establishing its
        // wall-clock timeline basis the instant a track was CLICKED, before
        // any network fetch had even started, let alone before real audio
        // was reaching the speaker. Wiring this through means JS can wait
        // for actual playback to begin before starting that clock, instead
        // of assuming click-time and playback-time are the same moment.
        // Placed here, after mainHandler's own declaration above — an
        // earlier version of this same fix put the init block before that
        // property was declared, which is a real ordering bug in Kotlin
        // (properties and init blocks run in file order); caught and fixed
        // before it ever compiled.
        engine.onTrackBoundaryReached = {
            mainHandler.post {
                webView.evaluateJavascript("window.__novaTrackAudioStarted && window.__novaTrackAudioStarted();", null)
            }
        }
        // The actual fix for "gapless but wait until the song before it is
        // done" — see onTrackEndReached's own comment in
        // NativeAudioEngine.kt for what this actually signals.
        engine.onTrackEndReached = {
            mainHandler.post {
                webView.evaluateJavascript("window.__novaTrackEndReached && window.__novaTrackEndReached();", null)
            }
        }
        // The actual gapless-swap notification — fires only from inside
        // the guarantee described at the swap's own call site (the
        // current track's real last byte has already been written). JS
        // needs this to update which track is now considered "current"
        // (metadata, queue position, now-playing) — the audio itself
        // needed no help from JS at all to be seamless, this is purely
        // state bookkeeping catching up to what already happened.
        engine.onSeamlessTrackSwap = { newTrackId, sampleRate, channels, totalBytes ->
            mainHandler.post {
                val totalFrames = if (totalBytes != null && channels > 0) (totalBytes.toLong() / (channels * 2)) else null
                val payload = JSONObject().apply {
                    put("trackId", newTrackId)
                    put("sampleRate", sampleRate)
                    put("channels", channels)
                    if (totalFrames != null) put("totalFrames", totalFrames.toString())
                }
                webView.evaluateJavascript("window.__novaSeamlessTrackSwap && window.__novaSeamlessTrackSwap(${JSONObject.quote(payload.toString())});", null)
            }
        }
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: android.net.Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val raw = message.data ?: return
        try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "fetchSegment" -> handleFetchSegment(json)
                "prefetchSegment" -> handlePrefetchSegment(json)
                "consumePrefetch" -> handleConsumePrefetch(json)
                "startTrackDownload" -> handleStartTrackDownload(json)
                "prepareNextTrackDownload" -> handleStartTrackDownload(json, forNextTrack = true)
                "stopTrackDownload" -> {
                    // Real, direct fix for a confirmed server-overload
                    // issue, traced from actual HTTP 502s and a null
                    // exception in the log — when a seek falls back from
                    // the buffered path to the old fetch path, the
                    // background whole-track download was never stopped,
                    // so both systems ended up simultaneously hitting the
                    // server for the SAME file at the SAME time. Bumping
                    // the generation here is the same self-terminating
                    // mechanism the download loop already checks on every
                    // segment boundary — it just needed an external trigger
                    // for this specific case, which never existed before.
                    trackDownloadGeneration++
                    DebugOverlay.emit(TAG, "stopTrackDownload: generation now $trackDownloadGeneration")
                }
                "seekWithinBuffer" -> handleSeekWithinBuffer(json)
                "debugLog" -> DebugOverlay.emit("JS", json.optString("message"))
                "updateNowPlaying" -> handleUpdateNowPlaying(json)
                "setVolume" -> engine.setVolume(json.optDouble("value", 1.0).toFloat())
                "stopAndFlush" -> {
                    // The other half of the fix for a confirmed gap: a
                    // pause used to send this with no generation at all,
                    // so currentGeneration never changed on pause — only
                    // 'start' ever touched it. An in-flight fetch's own
                    // staleness check (myGeneration != currentGeneration,
                    // used everywhere else already) never caught a pause
                    // specifically because of this — it kept running and
                    // enqueueing its data in the background even after the
                    // user paused, contaminating the queue for whatever
                    // plays next. Applying the generation here, the same
                    // way 'start' already does, is what makes that
                    // existing check catch this case too.
                    json.optInt("generation", -1).let { if (it >= 0) currentGeneration = it }
                    engine.stopAndFlush(json.optString("reason", "unspecified"))
                }
                "start" -> {
                    // Real, direct fix for a reported garbled/static-audio
                    // complaint, refined after a follow-up question about
                    // gapless playback surfaced a real side effect of that
                    // first fix: flushing unconditionally on every 'start'
                    // meant EVERY track transition destroyed and recreated
                    // the AudioTrack — including a normal queue advance,
                    // which never had any stale-data risk in the first place
                    // (the previous track's fetch loop had already returned
                    // cleanly by the time this fires). That guaranteed a
                    // real, audible gap at every single track boundary, not
                    // just the superseded ones this was meant to guard
                    // against — the opposite of what a native rewrite was
                    // supposed to achieve. The generation check in
                    // handleFetchSegment (checked on every read, not just
                    // once) is already sufficient on its own to stop a
                    // superseded fetch from enqueueing stale audio — it
                    // doesn't need a flush alongside it to do that job.
                    // Flushing is still genuinely needed for a hard switch
                    // (skip button, clicking a different track while one is
                    // still playing) specifically to clear out audio already
                    // sitting in the queue from the old track that the
                    // generation check can't retroactively un-enqueue — so
                    // JS marks which kind this is, and only a hard switch
                    // flushes; a gapless continuation does not.
                    currentGeneration = json.optInt("generation", currentGeneration + 1)
                    if (!json.optBoolean("gaplessContinuation", false)) engine.stopAndFlush()
                    engine.start()
                }
                else -> Log.w(TAG, "Unknown message type: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from JS: $raw", e)
            DebugOverlay.emit(TAG, "Failed to parse message from JS: $raw")
        }
    }

    /**
     * Fetches one segment's PCM bytes and streams them straight into the
     * playback queue as they arrive off the network — not buffered fully
     * first. This preserves the same "start playing before the whole segment
     * has downloaded" behavior the web player already relies on for its own
     * latency, rather than accidentally making things worse by waiting for a
     * complete response.
     *
     * Real gap, stated plainly: this does not replicate the web client's
     * X-PCM-Skip-Samples / X-PCM-Total-Frames header parsing, its quality
     * auto-selection, or its throughput measurement — those all still need
     * to happen JS-side (they already do) and get passed in as plain
     * parameters (sampleRate, channels) rather than re-derived natively.
     * This function trusts what JS tells it about the stream format; it
     * does not independently verify the response headers match.
     */
    /**
     * The other half of the real, direct fix for a reported "no media info
     * in the lock screen" complaint — see PlaybackService.updateNowPlaying's
     * own comment for the full story (both metadata AND playback state were
     * missing there, not just unconnected). Called from playTrackNative in
     * app.js whenever a track starts, and from the transport handlers when
     * play/pause state changes, so the lock screen stays in sync with what's
     * actually playing rather than showing stale or default info.
     */
    private fun handleUpdateNowPlaying(json: JSONObject) {
        val title = json.optString("title", "NOVA")
        val artist = json.optString("artist", "")
        val album = json.optString("album", null)
        val durationMs = (json.optDouble("durationSeconds", 0.0) * 1000).toLong()
        val positionMs = (json.optDouble("positionSeconds", 0.0) * 1000).toLong()
        val isPlaying = json.optBoolean("isPlaying", true)
        val isBuffering = json.optBoolean("isBuffering", false)
        val artworkUrl = json.optString("artworkUrl", null)
        if (artworkUrl.isNullOrEmpty()) {
            PlaybackService.instance?.updateNowPlaying(title, artist, album, durationMs, isPlaying, positionMs, null, isBuffering)
            return
        }
        // Real, direct fix — see metadataExecutor's own comment for the
        // "media info isn't updating" bug this addresses: no longer shares
        // fetchExecutor with PCM segment downloads.
        metadataExecutor.execute {
            var conn: HttpURLConnection? = null
            var bitmap: android.graphics.Bitmap? = null
            try {
                conn = (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                    val cookie = CookieManager.getInstance().getCookie(artworkUrl)
                    if (cookie != null) setRequestProperty("Cookie", cookie)
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (conn.responseCode in 200..299) {
                    bitmap = android.graphics.BitmapFactory.decodeStream(BufferedInputStream(conn.inputStream))
                }
            } catch (e: Exception) {
                // Non-fatal by design — missing/failed artwork should never
                // block the rest of now-playing info (title/artist/state)
                // from updating; this just means the lock screen shows no
                // image, the same as a track with no artwork at all.
                Log.w(TAG, "Artwork fetch/decode failed: $artworkUrl", e)
            } finally {
                conn?.disconnect()
            }
            mainHandler.post {
                PlaybackService.instance?.updateNowPlaying(title, artist, album, durationMs, isPlaying, positionMs, bitmap, isBuffering)
            }
        }
    }

    /**
     * The actual fix for a reported "no seamless queue tracks, should load
     * the next track in the background like the web UI" complaint. Fetches
     * a segment's bytes on prefetchExecutor (genuinely concurrent with
     * whatever the current track's own fetchExecutor is doing) and HOLDS
     * them in memory — deliberately never calls engine.enqueue() here.
     * That's the key safety property: this can run at any point while a
     * different track is actively playing, using whatever generation
     * happened to be current when it started, without any risk of writing
     * into that other track's live audio stream — because it writes
     * nowhere at all until explicitly told to via consumePrefetch, by
     * which point the real track transition has already established the
     * correct new generation.
     */
    private fun handlePrefetchSegment(json: JSONObject) {
        val urlStr = json.optString("url")
        val trackId = json.optString("trackId")
        val sampleRate = json.optInt("sampleRate", 44100)
        val channels = json.optInt("channels", 2)
        DebugOverlay.emit(TAG, "prefetchSegment requested for trackId=$trackId: $urlStr")
        prefetchExecutor.execute {
            // Real, direct addition: a device log from this exact session
            // showed the main fetch path needing 3 retries (multiple
            // timeouts) before a segment succeeded — a prefetch with no
            // retry logic at all would fail just as often, on exactly the
            // same connection, defeating the point. A few attempts with
            // backoff, same principle as the main fetch loop, just without
            // that loop's own generation-abandonment logic (a prefetch was
            // never enqueued anywhere in the first place, so there's
            // nothing stale to abandon — it either lands in prefetchedData
            // in time to be useful, or it doesn't, and the normal fetch
            // path picks up the slack either way).
            var lastError: Exception? = null
            for (attempt in 0 until 3) {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL(urlStr)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        val cookie = CookieManager.getInstance().getCookie(urlStr)
                        if (cookie != null) setRequestProperty("Cookie", cookie)
                        connectTimeout = 15000
                        readTimeout = 15000
                        requestMethod = "GET"
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        DebugOverlay.emit(TAG, "prefetchSegment failed: HTTP $code for trackId=$trackId (attempt ${attempt + 1}/3)")
                        lastError = Exception("HTTP $code")
                        continue
                    }
                    val headerSampleRate = conn.getHeaderField("X-PCM-Sample-Rate")?.toIntOrNull() ?: sampleRate
                    val headerChannels = conn.getHeaderField("X-PCM-Channels")?.toIntOrNull() ?: channels
                    val totalFrames = conn.getHeaderField("X-PCM-Total-Frames")?.toLongOrNull() ?: 0L
                    val skipSamples = conn.getHeaderField("X-PCM-Skip-Samples")?.toLongOrNull() ?: 0L
                    val bytes = BufferedInputStream(conn.inputStream).readBytes()
                    prefetchedTrackId = trackId
                    prefetchedData = PrefetchedData(bytes, headerSampleRate, headerChannels, totalFrames, skipSamples)
                    DebugOverlay.emit(TAG, "prefetchSegment complete for trackId=$trackId: ${bytes.size} bytes, ${headerSampleRate}Hz, ${headerChannels}ch (attempt ${attempt + 1}/3)")
                    return@execute
                } catch (e: Exception) {
                    lastError = e
                    DebugOverlay.emit(TAG, "prefetchSegment exception for trackId=$trackId: ${e.message} (attempt ${attempt + 1}/3)")
                } finally {
                    conn?.disconnect()
                }
                try { Thread.sleep(1000L * (attempt + 1)) } catch (e: InterruptedException) { return@execute }
            }
            DebugOverlay.emit(TAG, "prefetchSegment gave up for trackId=$trackId after 3 attempts: ${lastError?.message} — normal fetch path will handle this track's first segment instead")
        }
    }

    /**
     * Called once the real track transition happens and the new generation
     * is already established — hands the held prefetch bytes straight to
     * the engine as a single chunk, exactly as if they'd just arrived over
     * the network, then clears the holding buffer. If no matching prefetch
     * is ready (too slow, or none was ever requested), this simply does
     * nothing and the normal fetchSegment path handles it as it always did
     * — a prefetch is purely an optimization, never a requirement for
     * playback to proceed.
     */
    /**
     * The actual architecture fix's entry point. Downloads one track's
     * ENTIRE PCM data, sequentially, in fixed-size segments (for the same
     * resilience reasoning the server's own segmented design already
     * uses — a single hiccup costs one small retry, not a redo of
     * everything downloaded so far) — completely decoupled from playback
     * position. Every segment's bytes get appended to the same growable
     * TrackBuffer as they arrive; engine.startTrackBuffer has already
     * been called separately (see app.js) before this runs, so playback
     * can begin from the very first bytes while this continues in the
     * background.
     */
    private fun handleStartTrackDownload(json: JSONObject, forNextTrack: Boolean = false) {
        val basePath = json.optString("path")
        val quality = json.optString("quality", "reduced")
        val trackId = json.optString("trackId")
        val sampleRate = json.optInt("sampleRate", 44100)
        val channels = json.optInt("channels", 2)
        // Real, direct architecture fix requested explicitly: a seek
        // landing beyond what's downloaded so far must NEVER fall back to
        // the old queue-based engine — that engine's own AudioTrack
        // cycling is the actual, confirmed source of the static this
        // whole buffered system was built to eliminate in the first
        // place. Falling back to it, even occasionally, reintroduces
        // exactly the bug this was supposed to fix. Instead, a deep seek
        // restarts THIS SAME buffered system from the new position —
        // startAtSeconds lets the download begin from there directly
        // instead of always starting at 0, reusing the exact mechanism a
        // fresh track start already uses, never touching the old engine.
        val startAtSeconds: Int = json.optInt("startAtSeconds", 0)
        // forNextTrack: the same download logic, prefetching the NEXT
        // track's audio into a separate buffer while the current one
        // keeps playing untouched — see prepareNextTrackBuffer's own
        // comment. Uses its own independent generation counter so a seek
        // of the CURRENT track can never cancel this.
        val myGeneration: Int
        if (forNextTrack) {
            nextTrackDownloadGeneration++
            myGeneration = nextTrackDownloadGeneration
        } else {
            trackDownloadGeneration++
            myGeneration = trackDownloadGeneration
        }
        DebugOverlay.emit(TAG, "startTrackDownload for trackId=$trackId, generation=$myGeneration, startAtSeconds=$startAtSeconds, forNextTrack=$forNextTrack")
        trackDownloadExecutor.execute {
            try {
            val segmentSeconds = 60
            var skipToSeconds = startAtSeconds
            var reportedTotal = false
            var bufferStarted = false
            segmentLoop@ while (if (forNextTrack) myGeneration == nextTrackDownloadGeneration else myGeneration == trackDownloadGeneration) {
                var succeeded = false
                for (attempt in 0 until 5) {
                    if (if (forNextTrack) myGeneration != nextTrackDownloadGeneration else myGeneration != trackDownloadGeneration) break@segmentLoop
                    var conn: HttpURLConnection? = null
                    try {
                        val urlStr = "https://music.cosmoscraft.net/api/stream/pcm?path=" +
                            java.net.URLEncoder.encode(basePath, "UTF-8") +
                            "&quality=" + quality + "&skipToSeconds=" + skipToSeconds + "&maxDurationSeconds=" + segmentSeconds
                        val url = URL(urlStr)
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            val cookie = CookieManager.getInstance().getCookie(urlStr)
                            if (cookie != null) setRequestProperty("Cookie", cookie)
                            connectTimeout = 15000
                            readTimeout = 15000
                        }
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            DebugOverlay.emit(TAG, "trackDownload segment failed: HTTP $code at ${skipToSeconds}s (attempt ${attempt + 1}/5)")
                            Thread.sleep(minOf(8000L, 500L * (1 shl attempt)))
                            continue
                        }
                        if (!reportedTotal) {
                            val totalFrames = conn.getHeaderField("X-PCM-Total-Frames")?.toLongOrNull()
                            val skipSamples = conn.getHeaderField("X-PCM-Skip-Samples")?.toLongOrNull() ?: 0L
                            val headerSampleRate = conn.getHeaderField("X-PCM-Sample-Rate")?.toIntOrNull() ?: sampleRate
                            val headerChannels = conn.getHeaderField("X-PCM-Channels")?.toIntOrNull() ?: channels
                            // Real, direct fix needed for the startAtSeconds
                            // support above: X-PCM-Total-Frames is always the
                            // FULL track's frame count, regardless of where
                            // this request started — but THIS buffer's own
                            // byte space starts fresh at 0 for whatever
                            // position was requested. Using the full-track
                            // total directly here would mean the "reached the
                            // end" check could never actually trigger for a
                            // buffer that started partway through — skipSamples
                            // (how much the server itself skipped to reach the
                            // requested start) is what converts the full total
                            // into the REMAINING total this buffer actually
                            // needs to reach.
                            if (!bufferStarted) {
                                // The actual bridge between "download has real
                                // headers now" and "engine has a buffer to
                                // append into" — startTrackBuffer is called
                                // exactly once, right here, the moment the
                                // real format is known, rather than JS having
                                // to guess it upfront the way the old queue
                                // path's isFirstOfTrack chunks did.
                                val estimated = if (totalFrames != null) ((totalFrames - skipSamples).coerceAtLeast(0) * headerChannels * 2).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() } else 8 * 1024 * 1024
                                if (forNextTrack) engine.prepareNextTrackBuffer(trackId, headerSampleRate, headerChannels, estimated)
                                else engine.startTrackBuffer(trackId, headerSampleRate, headerChannels, estimated, 0)
                                bufferStarted = true
                            }
                            // Real, direct ordering bug found and fixed here,
                            // confirmed from a device log showing the timeline
                            // running straight past a track's own reported
                            // duration and never advancing: setting the total
                            // used to happen BEFORE the buffer above was even
                            // created. For a deep-seek RESTART of the same
                            // track, the OLD buffer (same trackId, about to be
                            // replaced) was still sitting in currentTrackBuffer
                            // at that moment — its trackId check passed, so
                            // the total silently landed on the buffer about to
                            // be discarded, and the actual, new, now-playing
                            // buffer never got a total at all. The "reached
                            // the real end" check depends entirely on that
                            // total being set — with it permanently null, the
                            // track could never signal its own end. This now
                            // runs after the buffer above definitely exists.
                            if (totalFrames != null) {
                                val remainingFrames = (totalFrames - skipSamples).coerceAtLeast(0)
                                val totalBytes = (remainingFrames * headerChannels * 2).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
                                if (forNextTrack) engine.setNextTrackBufferTotalBytes(trackId, totalBytes) else engine.setTrackBufferTotalBytes(trackId, totalBytes)
                                if (!forNextTrack) notifyTrackDurationKnown(trackId, totalFrames, headerSampleRate, headerChannels)
                                reportedTotal = true
                            }
                        }
                        val input = BufferedInputStream(conn.inputStream)
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        var gotAnyBytes = false
                        while (input.read(buf).also { n = it } >= 0) {
                            if (if (forNextTrack) myGeneration != nextTrackDownloadGeneration else myGeneration != trackDownloadGeneration) { input.close(); break@segmentLoop }
                            if (n == 0) continue
                            gotAnyBytes = true
                            if (forNextTrack) engine.appendToNextTrackBuffer(trackId, buf.copyOf(n)) else engine.appendToTrackBuffer(trackId, buf.copyOf(n))
                        }
                        input.close()
                        succeeded = true
                        if (!gotAnyBytes) {
                            // Zero bytes at a genuinely valid position means
                            // we've reached the real end of the file — the
                            // server's own "endByte <= startByte" case.
                            DebugOverlay.emit(TAG, "trackDownload reached end of file at ${skipToSeconds}s for trackId=$trackId")
                            break@segmentLoop
                        }
                        break
                    } catch (e: Exception) {
                        DebugOverlay.emit(TAG, "trackDownload segment exception at ${skipToSeconds}s: ${e.message} (attempt ${attempt + 1}/5)")
                        try { Thread.sleep(minOf(8000L, 500L * (1 shl attempt))) } catch (ie: InterruptedException) { break@segmentLoop }
                    } finally {
                        conn?.disconnect()
                    }
                }
                if (!succeeded) {
                    DebugOverlay.emit(TAG, "trackDownload gave up on segment at ${skipToSeconds}s after 5 attempts — stopping this track's background download")
                    break@segmentLoop
                }
                skipToSeconds += segmentSeconds
            }
            DebugOverlay.emit(TAG, "trackDownload finished (or superseded) for trackId=$trackId, generation=$myGeneration")
            } catch (e: Exception) {
                // Real, direct safety net added after a reported app crash
                // with no log to diagnose it from — this whole function
                // (especially the startAtSeconds/forNextTrack additions) is
                // genuinely new and untested on a real device. Catching
                // here turns a bug into a logged, recoverable stop instead
                // of taking the whole app down.
                Log.e(TAG, "trackDownload executor CRASHED (caught, not fatal): ${e.message}", e)
                DebugOverlay.emit(TAG, "trackDownload executor CRASHED (caught, not fatal): ${e}")
            }
        }
    }

    private fun notifyTrackDurationKnown(trackId: String, totalFrames: Long, sampleRate: Int, channels: Int) {
        val payload = JSONObject().apply {
            put("trackId", trackId)
            put("totalFrames", totalFrames.toString())
            put("sampleRate", sampleRate)
            put("channels", channels)
        }
        val js = "window.__novaTrackDurationKnown && window.__novaTrackDurationKnown(${JSONObject.quote(payload.toString())});"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun handleSeekWithinBuffer(json: JSONObject) {
        val trackId = json.optString("trackId")
        val byteOffset = json.optLong("byteOffset", 0L).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
        val requestId = json.optString("requestId")
        val succeeded = engine.seekWithinBuffer(trackId, byteOffset)
        DebugOverlay.emit(TAG, "seekWithinBuffer trackId=$trackId byteOffset=$byteOffset -> $succeeded")
        val payload = JSONObject().apply {
            put("requestId", requestId)
            put("succeeded", succeeded)
        }
        val js = "window.__novaSeekWithinBufferResult && window.__novaSeekWithinBufferResult(${JSONObject.quote(payload.toString())});"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun handleConsumePrefetch(json: JSONObject) {
        val trackId = json.optString("trackId")
        val requestId = json.optString("requestId")
        val generation = json.optInt("generation", currentGeneration)
        val data = if (prefetchedTrackId == trackId) prefetchedData else null
        if (data == null) {
            DebugOverlay.emit(TAG, "consumePrefetch: no ready prefetch for trackId=$trackId, falling back to a normal fetch")
            notifyJs(requestId, false, "no prefetch available", null)
            return
        }
        prefetchedTrackId = null
        prefetchedData = null
        if (generation != currentGeneration) {
            DebugOverlay.emit(TAG, "consumePrefetch: generation changed before this could be used (had $generation, now $currentGeneration) — discarding")
            notifyJs(requestId, false, "superseded before prefetch could be consumed", null)
            return
        }
        // Real, direct fix for a reported "corrupted the song" complaint.
        // Every other chunk that ever reaches the engine — from the normal
        // fetch path — arrives in small ~32KB pieces, streamed in as the
        // network delivers them. This was the one exception: the entire
        // prefetched segment (7+ MB) handed to the engine as a single
        // chunk. The playback loop treats a chunk as one atomic unit —
        // downmix and limiter processing run over the WHOLE thing before
        // any of it is written to the AudioTrack — so a multi-megabyte
        // chunk meant a real, noticeable stall computing that pass before
        // playback of the new track could even begin, right at the
        // transition point this was supposed to make seamless. Splitting
        // into the same 32KB pieces the normal path already uses makes
        // this genuinely indistinguishable from a normal streamed fetch by
        // the time it reaches the engine, instead of a special case with
        // its own untested size behavior.
        // Real, direct extension of the "gapless but wait until done" fix
        // to a rarer edge case: a track short enough that the prefetch
        // alone (up to PREFETCH_DURATION_SECONDS, see app.js) covers the
        // whole thing. onTrackEndReached needs the actual last chunk
        // marked — computed here from the prefetch's own byte count
        // against the track's total frame count, since JS already
        // separately detects this same condition (see its own "Edge case"
        // comment) and needs a genuine end-of-track signal to wait on
        // there too, not just for the normal, longer-than-one-prefetch
        // case.
        val bytesPerFrame = data.channels * 2
        val prefetchFrameCount = data.bytes.size / bytesPerFrame
        val coversWholeTrack = data.totalFrames > 0 && prefetchFrameCount.toLong() >= data.totalFrames
        val chunkSize = 32 * 1024
        var offset = 0
        var isFirst = true
        while (offset < data.bytes.size) {
            val end = minOf(offset + chunkSize, data.bytes.size)
            val isLastChunkOverall = end >= data.bytes.size
            engine.enqueue(data.bytes.copyOfRange(offset, end), data.sampleRate, data.channels, isFirst, false, coversWholeTrack && isLastChunkOverall)
            isFirst = false
            offset = end
        }
        val headers = JSONObject().apply {
            put("sampleRate", data.sampleRate)
            put("channels", data.channels)
            put("totalFrames", data.totalFrames.toString())
            put("skipSamples", data.skipSamples.toString())
        }
        DebugOverlay.emit(TAG, "consumePrefetch: used prefetched data for trackId=$trackId, ${data.bytes.size} bytes")
        notifyJsHeaders(requestId, headers)
        notifyJs(requestId, true, null, headers)
    }

    private fun handleFetchSegment(json: JSONObject) {
        val urlStr = json.optString("url")
        val sampleRate = json.optInt("sampleRate", 44100)
        val channels = json.optInt("channels", 2)
        val isFirstOfTrack = json.optBoolean("isFirstOfTrack", false)
        val isRetry = json.optBoolean("isRetry", false)
        val isLastSegmentOfTrack = json.optBoolean("isLastSegmentOfTrack", false)
        val requestId = json.optString("requestId")
        val myGeneration = json.optInt("generation", currentGeneration)
        Log.i(TAG, "fetchSegment requested: $urlStr (requestId=$requestId, isFirstOfTrack=$isFirstOfTrack, isRetry=$isRetry)")
        DebugOverlay.emit(TAG, "fetchSegment requested: $urlStr (requestId=$requestId, isFirstOfTrack=$isFirstOfTrack, isRetry=$isRetry)")
        fetchExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    // The web app's own session is cookie-based (see
                    // routes/auth.js) — reusing WebView's own CookieManager
                    // means this fetch is authenticated exactly the same way
                    // the page itself is, no separate login/token handling
                    // needed in native code at all.
                    val cookie = CookieManager.getInstance().getCookie(urlStr)
                    if (cookie != null) setRequestProperty("Cookie", cookie)
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                Log.i(TAG, "fetchSegment response code=$code for requestId=$requestId")
                DebugOverlay.emit(TAG, "fetchSegment response code=$code for requestId=$requestId")
                if (code !in 200..299) {
                    notifyJs(requestId, false, "HTTP $code", null)
                    return@execute
                }
                // Same headers the web client's own fetch path already reads
                // (see getDecodedBufferFromServerPCM in app.js) — reported back
                // here so JS can still know total track duration / sample rate
                // from the first segment's response, the same way it already
                // does today, without needing to guess or duplicate server-side
                // logic to compute them independently.
                //
                // Real bug caught while writing this, worth stating plainly:
                // the first version of this function used the sampleRate/
                // channels PARAMETERS (passed in from JS) to enqueue chunks as
                // they streamed in — but for a track's very first segment, JS
                // genuinely cannot know the real format yet; that information
                // only exists in THIS response's own headers, which haven't
                // been read at the point playback of that segment needs to
                // start. Enqueuing with the JS-supplied value would have
                // played a brand-new track's opening bytes at whatever
                // (possibly wrong) format JS guessed, before ever correcting
                // itself. Fixed by always preferring the header-reported
                // values when present — the same authoritative-source
                // approach the web client itself already uses (it never
                // assumes a format either, always reads it from these same
                // headers).
                val headerSampleRate = conn.getHeaderField("X-PCM-Sample-Rate")?.toIntOrNull() ?: sampleRate
                val headerChannels = conn.getHeaderField("X-PCM-Channels")?.toIntOrNull() ?: channels
                val headers = JSONObject().apply {
                    put("sampleRate", headerSampleRate)
                    put("channels", headerChannels)
                    put("totalFrames", conn.getHeaderField("X-PCM-Total-Frames"))
                    put("skipSamples", conn.getHeaderField("X-PCM-Skip-Samples"))
                }
                // Real, direct fix for a reported "seek keeps getting
                // silently blocked / no media-info timestamps" complaint,
                // confirmed directly: HTTP response headers are available
                // essentially instantly, as soon as the response begins —
                // but JS was never told about them until notifyJs fired
                // at the very end of this function, AFTER the entire
                // segment's body (tens of megabytes for 8-channel audio)
                // had finished downloading. The track's real duration sat
                // in `headers` this whole time, just never communicated
                // early enough to be useful — every seek attempt made
                // before that full download finished had no known duration
                // to compute a target position from, and was correctly,
                // but unhelpfully, refusing to do anything. Sent separately
                // and immediately here, before the body loop even starts,
                // so JS can establish the real duration right away instead
                // of waiting on however long the full download happens to
                // take.
                notifyJsHeaders(requestId, headers)
                val input = BufferedInputStream(conn.inputStream)
                val buf = ByteArray(32 * 1024) // 32KB read chunks — small enough that the first audio reaches the engine quickly, large enough not to be dominated by per-read overhead
                var firstChunk = isFirstOfTrack
                var n: Int
                var abandoned = false
                // Real, direct fix for "gapless but wait until the song
                // before it is done" — a look-ahead by exactly one read is
                // required here: there's no way to know a chunk is the
                // LAST one for this segment until the stream actually ends
                // (the next read returns -1), so the most recently read
                // chunk is held back one iteration rather than enqueued
                // immediately, and only enqueued once it's known whether
                // another one is coming. isLastSegmentOfTrack (set by JS
                // only on the segment it already knows ends the track) is
                // what makes that held-back final chunk carry
                // isLastOfTrack — engine.onTrackEndReached fires once that
                // exact chunk is actually, fully written out.
                var pending: ByteArray? = null
                while (input.read(buf).also { n = it } >= 0) {
                    // The actual fix for stale-audio-mixing: checked on every
                    // single read, not just once at the start — a track
                    // switch can happen at any point mid-download, and this
                    // is what stops a superseded fetch from continuing to
                    // feed bytes into what's now a different track's stream.
                    if (myGeneration != currentGeneration) {
                        DebugOverlay.emit(TAG, "Abandoning stale fetch (generation $myGeneration, current is $currentGeneration): $urlStr")
                        abandoned = true
                        break
                    }
                    if (n == 0) continue
                    pending?.let { engine.enqueue(it, headerSampleRate, headerChannels, firstChunk, isRetry) }
                    firstChunk = false // only the very first bytes of a track's very first segment carry this flag
                    pending = buf.copyOf(n)
                }
                input.close()
                if (!abandoned) {
                    pending?.let { engine.enqueue(it, headerSampleRate, headerChannels, firstChunk, isRetry, isLastSegmentOfTrack) }
                    notifyJs(requestId, true, null, headers)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Segment fetch failed: $urlStr", e)
                notifyJs(requestId, false, e.message ?: e.javaClass.simpleName, null)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Reports a fetch's outcome back to JS by calling a well-known callback
     * function the page defines (see the native-mode branch added to app.js).
     * evaluateJavascript must run on the main thread; this is invoked from
     * the background fetch thread, hence the handler post.
     */
    private fun notifyJs(requestId: String, success: Boolean, error: String?, headers: JSONObject?) {
        Log.i(TAG, "notifyJs: requestId=$requestId success=$success error=$error")
        DebugOverlay.emit(TAG, "notifyJs: requestId=$requestId success=$success error=$error")
        val payload = JSONObject().apply {
            put("requestId", requestId)
            put("success", success)
            if (error != null) put("error", error)
            if (headers != null) put("headers", headers)
        }
        val js = "window.__novaNativeCallback && window.__novaNativeCallback(${JSONObject.quote(payload.toString())});"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * The other half of the headers-arrive-early fix — a separate,
     * dedicated callback JS listens for independently of the main
     * fetchSegment completion path, since that path only resolves once an
     * entire (potentially large) segment body has finished downloading.
     * Fire-and-forget by design: JS treats this purely as "now I know the
     * duration," nothing here waits on or reacts to whether JS actually
     * received it.
     */
    private fun notifyJsHeaders(requestId: String, headers: JSONObject) {
        DebugOverlay.emit(TAG, "notifyJsHeaders: requestId=$requestId headers=$headers")
        val payload = JSONObject().apply {
            put("requestId", requestId)
            put("headers", headers)
        }
        val js = "window.__novaHeadersCallback && window.__novaHeadersCallback(${JSONObject.quote(payload.toString())});"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun shutdown() {
        fetchExecutor.shutdownNow()
        metadataExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
    }
}
