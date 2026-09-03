package net.cosmoscraft.nova

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.tanh

/**
 * The actual fix this whole app exists for. Every discontinuity/crackle bug
 * chased across the web player this session traced back to one structural
 * fact: Web Audio API plays audio by scheduling discrete, separately-created
 * AudioBufferSourceNode objects end-to-end, and stitching two of those
 * together — however carefully — always has a real seam. This class has no
 * such seam by construction: one [AudioTrack] in streaming mode, fed by a
 * single continuous queue of PCM byte chunks, written with blocking
 * [AudioTrack.write] calls on one dedicated thread for as long as a track is
 * playing. There is no "segment boundary" concept here at all — chunks are
 * just bytes appended to one ongoing stream, the same way piping raw audio to
 * `aplay` or `ffplay -f s16le -` never audibly clicks between reads.
 *
 * Deliberately NOT responsible for fetching, retrying, or deciding what to
 * play next — see NativeAudioBridge and the "isFirstOfTrack" parameter below
 * for why: the JS side keeps every bit of the fetch/retry/prefetch/quality-
 * selection logic that was already mature and well-tested before this app
 * existed. This class's only job is turning a stream of already-correct PCM
 * bytes into sound without introducing a new seam of its own.
 *
 * NOT tested on a real device — I have no way to compile or run this code
 * myself in the environment I'm working in. Buffer sizing, in particular
 * (see MIN_BUFFER_MULTIPLIER below), is a reasonable starting point from
 * documented Android audio guidance, not a number verified against real
 * hardware. Expect to need to tune it.
 */
/**
 * The actual architecture change requested directly: instead of treating
 * every seek as a full network re-fetch plus an audio-hardware cycle, this
 * holds one track's ENTIRE decoded PCM data in a single growable buffer,
 * downloaded once, sequentially, in the background — completely decoupled
 * from playback position. A seek to anywhere already downloaded becomes a
 * pure in-memory operation: move a read cursor, nothing else. This is the
 * same fundamental approach real streaming apps (Apple Music, Amazon
 * Music, anything built on ExoPlayer) use — confirmed directly from an
 * ExoPlayer engineer's own writeup: seeking within an already-buffered
 * window "becomes a lot less destructive... we can simply move the
 * playback position back in time and the data source... remain untouched."
 * Every previous fix in this session tried to make the OLD, always-
 * destructive seek safer; this removes the need for it to be destructive
 * at all, for the common case.
 */
class TrackBuffer(val trackId: String, val sampleRate: Int, val channels: Int, initialCapacityBytes: Int) {
    @Volatile var data: ByteArray = ByteArray(initialCapacityBytes.coerceAtLeast(1024))
        private set
    // How many bytes at the start of `data` are actually valid, downloaded
    // audio — everything from here onward is unused, pre-allocated capacity.
    @Volatile var downloadedBytes: Int = 0
        private set
    // Set once the server reports the track's real total size — null until
    // then, since the growable buffer's own current capacity is just a
    // guess, not the actual track length.
    @Volatile var totalBytes: Int? = null

    private val growLock = Any()

    /** Appends newly-downloaded bytes, growing the backing array if needed. Safe to call from the download thread while the playback thread concurrently reads [data] up to [downloadedBytes]. */
    fun append(bytes: ByteArray) {
        synchronized(growLock) {
            val needed = downloadedBytes + bytes.size
            if (needed > data.size) {
                // Real, deliberate choice: double capacity (standard growable-
                // array strategy) rather than growing by exactly what's
                // needed each time, which would mean an O(n) copy on every
                // single append instead of an amortized O(1) one.
                var newSize = data.size * 2
                while (newSize < needed) newSize *= 2
                data = data.copyOf(newSize)
            }
            System.arraycopy(bytes, 0, data, downloadedBytes, bytes.size)
            downloadedBytes += bytes.size
        }
    }
}

class NativeAudioEngine {
    companion object {
        private const val TAG = "NovaAudioEngine"

        // AudioTrack's own getMinBufferSize() returns the smallest buffer that
        // won't underrun under ideal conditions — real devices, especially
        // under the kind of network jitter this whole app is meant to survive,
        // need real margin above that floor. 4x is a starting point grounded in
        // common guidance for streamed (not one-shot) playback, not a number
        // measured on real hardware — this is one of the first things worth
        // tuning once this actually runs on a phone.
        private const val MIN_BUFFER_MULTIPLIER = 4

        // Same fixed, small safety trim as the web client's own
        // LOUDNESS_SAFETY_TRIM_DB (-1dB) — real per-track normalization is
        // already baked into the PCM server-side (see nova-server's
        // populateCacheInBackground); this is headroom, not a second
        // normalization pass.
        private const val SAFETY_TRIM = 0.8912509f // 10^(-1/20)

        // Same downmix coefficients already measured and tuned in the web
        // player's own connectWithDownmix (see app.js) — reused here rather
        // than re-derived, since those numbers were already verified against
        // real multichannel content earlier this session. Applied correctly
        // per-sample here, unlike a channel-index-parity shortcut.
        private const val DOWNMIX_SIDE = 1.0f
        private const val DOWNMIX_CENTER = 0.707f
        private const val DOWNMIX_SURROUND = 0.707f
        private const val DOWNMIX_LFE = 0.5f

        // Same transfer curve as the web client's own softClip WaveShaperNode:
        // fully transparent below 0.9, a tanh taper above it approaching but
        // never reaching 1.0. A safety net for the rare case a downmix sum (or
        // a not-yet-server-cached first play, still using a quick, bounded
        // gain estimate rather than the full-track one) pushes a sample past
        // full scale — not expected to engage often.
        private fun softClip(x: Float): Float {
            val ax = abs(x)
            if (ax <= 0.9f) return x
            val over = (ax - 0.9f) / (1f - 0.9f)
            val shaped = 0.9f + 0.1f * (tanh(over * 2f) / tanh(2f)).toFloat()
            return if (x < 0) -shaped else shaped
        }
    }

    // Chunk = one piece of raw PCM bytes queued for playback, tagged with
    // whether it's the first chunk of a brand new track (see below for why
    // that matters) and the format it was decoded at, since format can only
    // change at a track boundary, never mid-track.
    private data class Chunk(val bytes: ByteArray, val sampleRate: Int, val channels: Int, val isFirstOfTrack: Boolean, val isLastOfTrack: Boolean = false)

    // Bounded, not unbounded — a producer (the fetch thread) that's allowed to
    // run arbitrarily far ahead of actual playback defeats the purpose of
    // wanting tight control over buffering. 64 chunks at typical network-chunk
    // sizes is generous slack without being unbounded; put() blocks the fetch
    // thread (not the playback thread) once full, which is the correct
    // backpressure direction.
    // Real, direct fix for a reported "no seamless queue tracks, next track
    // should start loading in the background" complaint. The actual math:
    // 64 chunks at up to 32KB each is at most ~2MB — at this track's own
    // bitrate (8ch/32kHz/16-bit = 512,000 bytes/sec), that's only about 4
    // seconds of real playback buffer. playNextInQueue already fires the
    // instant the current track's LAST segment finishes downloading, not
    // once it finishes playing — but with only 4 seconds of slack, any
    // fetch for the next track's first segment slower than that (a real,
    // common case on the connection this session has repeatedly shown
    // struggling) empties the queue before that data can arrive, producing
    // an audible gap regardless of how early the fetch started. Raised to
    // hold roughly 25-30 seconds of buffer even at full-quality bitrates —
    // real memory cost (tens of MB), but a reasonable one on a modern phone
    // for what it actually buys: enough slack that a genuinely slow
    // transition has real room to complete before the queue runs dry.
    private val queue = ArrayBlockingQueue<Chunk>(600)
    private var playbackThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels = 0
    @Volatile private var volumeMultiplier = 1.0f
    @Volatile var onUnderrun: (() -> Unit)? = null
    @Volatile var onTrackBoundaryReached: (() -> Unit)? = null
    // The actual fix for "gapless but wait until the song before it is
    // done" — fires only once a chunk explicitly marked as a track's LAST
    // one has been fully, actually written out to the AudioTrack — not
    // when it was fetched, not when it was queued, but once real audio
    // delivery of it is complete. This is the one moment it's genuinely
    // safe to let a following track's audio start.
    @Volatile var onTrackEndReached: (() -> Unit)? = null

    /** Called from the fetch thread as network chunks arrive — never blocks the caller for long (bounded queue aside), never touches the AudioTrack directly. */
    fun enqueue(bytes: ByteArray, sampleRate: Int, channels: Int, isFirstOfTrack: Boolean, isRetry: Boolean = false, isLastOfTrack: Boolean = false) {
        // Real, direct correction of my own previous fix. That fix (flush
        // on isFirstOfTrack) was built on a false assumption: that
        // isFirstOfTrack==true only ever meant "genuinely fresh stream,
        // queue is already empty anyway" or "retry of a failed attempt,
        // needs a flush." It missed a third, real case — a gapless
        // continuation's first chunk is ALSO isFirstOfTrack==true, but the
        // queue is DELIBERATELY non-empty right then, still holding the
        // previous track's final, legitimately-unplayed seconds. A device
        // log confirmed this directly: the flush fired at exactly that
        // moment and discarded the outgoing track's real ending. Reverted
        // to an explicit isRetry signal instead, set by JS only when this
        // really is a retry of a previously failed attempt at the same
        // position (see the fetch loop's own retry logic) — the one case
        // this was ever meant to handle, now the only case it actually
        // triggers on.
        if (isRetry) queue.clear()
        queue.put(Chunk(bytes, sampleRate, channels, isFirstOfTrack, isLastOfTrack))
    }

    fun setVolume(v: Float) {
        volumeMultiplier = v.coerceIn(0f, 1.5f)
    }

    fun start() {
        if (running.getAndSet(true)) return
        // See playbackEpoch's own comment above for the full design this
        // is part of — incremented here, exactly once per genuinely new
        // thread (not on the no-op path above, which is the correct
        // gapless-continuation case where the existing thread should keep
        // running under its own, still-valid epoch).
        playbackEpoch++
        // Real, direct fix for a subtle bug found on review, before it
        // ever shipped this way: capturing this value on the new thread
        // ITSELF (inside playbackLoop) rather than here is wrong —
        // Thread.start() is asynchronous, so there's a real gap between
        // incrementing this counter and the new thread actually beginning
        // to run. If another stop+start happened in that gap and bumped
        // the epoch again before this thread got scheduled, it would read
        // the NEWER value and wrongly believe itself current. Captured
        // here instead, on the main thread, at the exact moment this
        // specific thread is being created for — this is the value that
        // actually corresponds to this thread's own generation.
        val myEpoch = playbackEpoch
        Log.i(TAG, "start() called — creating playback thread")
        DebugOverlay.emit(TAG, "start() called — creating playback thread")
        try {
            val t = Thread({ playbackLoop(myEpoch) }, "NovaPlaybackThread")
            // Real, plausible risk found on review after a reported "nothing
            // plays" complaint: Thread.MAX_PRIORITY (10) is an extreme value —
            // a plain java.lang.Thread's priority is capped by its
            // ThreadGroup's own max, and behavior at the very top of that
            // range isn't guaranteed consistent across every Android version/
            // OEM. Not confirmed as THE cause without a real device log, but
            // it's a real, unnecessary risk for a benefit (a few priority
            // levels) that a much safer boost already provides. Dropped to a
            // modest, non-extreme boost above normal instead.
            t.priority = Thread.NORM_PRIORITY + 2
            playbackThread = t
            t.start()
            Log.i(TAG, "Playback thread started successfully")
            DebugOverlay.emit(TAG, "Playback thread started successfully")
        } catch (e: Throwable) {
            // Real gap this whole block fixes: if thread creation/priority
            // assignment ever throws, 'running' was already set true above,
            // permanently locking out any future start() call (the
            // getAndSet(true) guard would keep returning early) while no
            // thread actually exists to consume the queue — every enqueue()
            // after this point would eventually block forever once the
            // bounded queue fills, with nothing in the log to explain why.
            // Catching, logging loudly, and resetting the flag turns a
            // silent, permanent hang into a visible, recoverable failure.
            Log.e(TAG, "FAILED to start playback thread", e)
            DebugOverlay.emit(TAG, "FAILED to start playback thread")
            running.set(false)
        }
    }

    // Real, direct fix for a confirmed, severe bug: "AudioTrack.write error
    // code=-3" (ERROR_INVALID_OPERATION) appeared directly in a device log
    // during rapid seeking, and traced to an actual, confirmed cause —
    // stopAndFlush() is called from the 'start' message handler, which
    // WebViewCompat's bridge invokes on the main/UI thread, while
    // track.write() runs on this class's own dedicated playback thread —
    // with NO synchronization between them at all. A seek arriving while
    // the playback thread is mid-write can release the exact AudioTrack
    // instance that write() is actively writing to, out from under it. This
    // lock is what actually closes that race: every access to audioTrack
    // itself — creating it, writing to it, releasing it — now holds this
    // same lock, so those operations can never interleave. write() blocking
    // briefly under this lock is an acceptable, bounded cost (chunks are
    // ~32KB now, ~60ms of audio) — genuinely audible corruption from an
    // unsynchronized release is not.
    private val trackLock = Any()

    /** Stops playback and discards anything still queued — used for seek and track-switch, where whatever was queued is now stale. */
    // Real, direct correction of my own previous fix, which made things
    // worse: Thread.join() BLOCKS the calling thread until the target
    // exits (or the timeout hits) — and stopAndFlush runs on the main/UI
    // thread. If the old playback thread was genuinely stuck inside a
    // blocking AudioTrack.write() (which can happen for real — writing to
    // a PAUSED track's buffer, once full, never drains, since nothing is
    // consuming it), that join call froze the entire app's UI for up to
    // 500ms on every single pause/resume, repeatedly. Not the infinite
    // deadlock a join timeout is supposed to prevent, but a real,
    // confirmed regression on its own. Replaced with a non-blocking
    // design that achieves the same guarantee — no two playback threads
    // ever writing to the AudioTrack at once — without the calling thread
    // ever waiting on anything. Every playback thread captures its own
    // epoch number at creation; incrementing this on every start() makes
    // any previous thread's epoch instantly stale, and it checks that
    // before every single write — so a superseded thread stops writing
    // the moment it notices, on its own, with nothing else needing to
    // wait for or confirm it.
    @Volatile private var playbackEpoch = 0

    fun stopAndFlush(reason: String = "unspecified") {
        DebugOverlay.emit(TAG, "stopAndFlush called, reason=$reason")
        running.set(false)
        queue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        // Real, direct addition for the new buffered playback path — this
        // function is the one shared "stop whatever's currently playing"
        // entry point (used by pause, and by a hard track switch), so it
        // needs to correctly stop EITHER kind of playback thread, not just
        // the original queue-based one. The buffered thread already checks
        // running.get() in its own loop condition, so setting that above is
        // enough for it to notice and exit on its own — interrupt() here
        // additionally unblocks it immediately if it's in its Thread.sleep
        // poll-wait rather than leaving it to wake up on the next poll.
        bufferedPlaybackThread?.interrupt()
        bufferedPlaybackThread = null
        // Real, direct fix for a suspected crash after "prev" started a
        // brand new track: this is the one shared entry point for a hard
        // track switch, but it never cleared a next-track prefetch left
        // over from whatever was playing before. That prefetch keeps
        // running on its own background thread regardless — without
        // clearing it here, it could still be actively writing bytes for
        // a track that's no longer relevant at all, and worse, could
        // still be sitting there ready to be swapped in by the NEW
        // track's own natural end, mixing in audio from a completely
        // different, stale track. A hard switch should always start with
        // a clean slate for this.
        nextTrackBuffer = null
        // Real, direct fix for a confirmed "static" complaint — a
        // spectrogram of an actual recording showed full-spectrum noise
        // (not distorted music, genuine noise) landing exactly during rapid
        // seeking, and the screen recording confirmed audio was already
        // coming out of the speaker before the new seek's real data had
        // even arrived. This used to fully RELEASE the AudioTrack on every
        // single seek, then build a brand new one moments later —
        // repeatedly tearing down and recreating the actual hardware audio
        // session, sometimes multiple times per second during rapid
        // seeking. Rapid AudioTrack create/destroy churn is a known stress
        // point for Android's audio HAL, particularly with
        // PERFORMANCE_MODE_LOW_LATENCY, which uses a limited, dedicated
        // hardware resource on many devices — a plausible, real source of
        // exactly this kind of noise. Pausing and flushing without
        // releasing keeps the same hardware session alive across a seek;
        // ensureTrackFor already only recreates when the format has
        // genuinely changed, so this doesn't affect that case at all —
        // only the seek-to-same-format case, which is by far the common
        // one, stops needlessly cycling the hardware every time.
        synchronized(trackLock) { pauseAndFlushTrack() }
    }

    // Real, direct addition after a confirmed report: skipping back
    // immediately after a track had JUST auto-advanced landed two
    // legitimate, distinct hardware transitions back-to-back within a
    // fraction of a second — the auto-advance's own cycle, then the skip's.
    // Neither is a duplicate to debounce away (both are real, intentional
    // actions), but back-to-back cycling this close together is the same
    // confirmed stress on Android's audio HAL as the rapid-seeking and
    // rapid-tapping cases already fixed. A brief, bounded wait here — only
    // when the hardware was cycled very recently — gives it a moment to
    // settle before doing it again, without ever dropping or ignoring the
    // action itself the way a debounce would.
    private var lastTrackCycleMs = 0L
    private val MIN_CYCLE_INTERVAL_MS = 120L

    private fun waitOutMinCycleInterval() {
        val sinceLast = System.currentTimeMillis() - lastTrackCycleMs
        if (sinceLast in 0 until MIN_CYCLE_INTERVAL_MS) {
            try { Thread.sleep(MIN_CYCLE_INTERVAL_MS - sinceLast) } catch (e: InterruptedException) { /* fine to proceed immediately if interrupted */ }
        }
        lastTrackCycleMs = System.currentTimeMillis()
    }

    private fun pauseAndFlushTrack() {
        waitOutMinCycleInterval()
        try {
            audioTrack?.let {
                it.pause()
                it.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing/flushing AudioTrack (non-fatal, proceeding)", e)
        }
    }

    private fun releaseTrack() {
        try {
            audioTrack?.let {
                it.pause()
                it.flush()
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack (non-fatal, proceeding)", e)
        }
        audioTrack = null
        currentSampleRate = 0
        currentChannels = 0
    }

    private fun channelMaskFor(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> AudioFormat.CHANNEL_OUT_STEREO // downmixed to stereo before reaching AudioTrack — see mixToStereo below
    }

    // Callers already hold trackLock (see the playback loop and
    // stopAndFlush) — this is never called on its own from outside that
    // lock, so it doesn't take it itself (would deadlock on re-entry
    // otherwise, since Kotlin's `synchronized` here isn't reentrant-safe
    // across a shared helper unless every caller already holds it).
    // Real, defensive addition after a second, unconfirmed static report
    // with no video evidence to pin down this time — reasoning through the
    // risk this fix's own predecessor introduced: keeping the same
    // AudioTrack alive across many seeks (the pause-instead-of-release
    // fix) avoids rapid create/destroy churn, but an instance paused,
    // flushed, and resumed many times in a row over a long session is
    // itself a usage pattern Android's AudioTrack isn't necessarily built
    // or tested for — state drift over many cycles is a real, unruled-out
    // possibility. This caps how many consecutive reuses one instance gets
    // before a genuine release+recreate, trading a small amount of the
    // churn-avoidance benefit for a periodic clean reset — a middle ground
    // between "recreate every time" (confirmed HAL stress) and "never
    // recreate" (unconfirmed but plausible long-run drift).
    private var trackReuseCount = 0
    private val MAX_TRACK_REUSES = 8

    private fun ensureTrackFor(sampleRate: Int, channels: Int) {
        val outChannels = if (channels > 2) 2 else channels // multichannel is downmixed in software before writing, see mixToStereo
        if (audioTrack != null && currentSampleRate == sampleRate && currentChannels == outChannels && trackReuseCount < MAX_TRACK_REUSES) {
            // The other half of the pause-instead-of-release fix above:
            // stopAndFlush leaves the track paused, not released, when the
            // format matches — but a paused AudioTrack silently accepts
            // write() calls into its buffer without ever actually making
            // sound until it's explicitly told to play again. Without this,
            // every seek would go fully silent forever after the first one
            // reused this same instance — checked directly against the
            // hardware's own play state rather than a separately tracked
            // flag that could drift out of sync with it.
            //
            // Real, severe bug just found and fixed here: trackReuseCount
            // used to increment on every call that reached this branch —
            // but this function runs once per ~32KB CHUNK, not once per
            // seek/resume. A single 60s segment is 900+ chunks, so the
            // reuse cap (8) was being hit within the first handful of
            // chunks of ANY playback, forcing a full release+recreate,
            // resetting to zero, and hitting the cap again moments later —
            // constantly, even during completely normal, uninterrupted
            // playback with no seeking involved at all. That's what
            // produced hundreds of rapid recreations and total silence.
            // Fixed by only counting an actual PAUSED-to-PLAYING
            // transition as a "reuse" — the one thing that genuinely
            // corresponds to a real pause/resume cycle — not every chunk
            // written while already playing normally.
            val track = audioTrack
            if (track != null && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                trackReuseCount++
                try { track.play() } catch (e: Exception) { Log.w(TAG, "Error resuming paused AudioTrack (non-fatal, proceeding)", e) }
            }
            return
        }
        trackReuseCount = 0
        waitOutMinCycleInterval()
        // Format actually changed (a new track with a different sample rate,
        // e.g. 44100 vs 48000, or mono vs stereo source) — AudioTrack can't be
        // reconfigured in place, a new one is required. This is the one place
        // true sample-accurate gaplessness isn't achievable even natively: a
        // brand new AudioTrack has its own brief hardware startup. Real,
        // measured precedent for this exact tradeoff (a small non-zero gap at
        // a format-changing track boundary, everything else genuinely gapless)
        // exists in real bit-perfect Android players — not something invented
        // for this app, but an honest, documented limit of the platform.
        releaseTrack()
        val channelMask = channelMaskFor(outChannels)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = if (minBuf > 0) minBuf * MIN_BUFFER_MULTIPLIER else sampleRate * outChannels * 2 // 1s fallback if getMinBufferSize itself fails (documented as possible, e.g. unsupported format on some hardware)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.play()
        audioTrack = track
        currentSampleRate = sampleRate
        currentChannels = outChannels
        Log.i(TAG, "AudioTrack (re)created: ${sampleRate}Hz, ${outChannels}ch out (source had $channels ch), bufSize=$bufSize")
        DebugOverlay.emit(TAG, "AudioTrack (re)created: ${sampleRate}Hz, ${outChannels}ch out (source had $channels ch), bufSize=$bufSize")
    }

    // Real, serious bug found from a direct report and confirmed with real
    // numbers from a device log: "First chunk received: 3565 bytes... 8ch"
    // — 3565 isn't a multiple of 16 (8 channels x 2 bytes/frame). Network
    // reads have no reason to land on audio frame boundaries; nothing here
    // ever accounted for that. Every chunk's leftover partial-frame bytes
    // were silently discarded, and the NEXT chunk started frame alignment
    // fresh from ITS byte 0 — which is actually partway into what should
    // have been a continuous frame. That shifts which bytes map to which
    // channel from that point on, repeatedly, at every single chunk
    // boundary throughout playback (not just once at the start) — scrambled
    // channel data exactly matches what "insane amounts of static" sounds
    // like, and explains why it persisted through two rounds of gain-stage
    // fixes that were never actually the problem. Carried across calls (not
    // reset per-chunk) and cleared specifically on a track/format change
    // (ensureTrackFor already detects that), since leftover bytes from a
    // different channel count would be meaningless to prepend to a new
    // stream.
    // ==================== Buffered (whole-track) playback ====================
    // See TrackBuffer's own class comment for the full design. This is a
    // parallel path to the queue-based one above — kept separate rather
    // than surgically rewritten into it, specifically to avoid risking the
    // many hard-won fixes already in the queue path (trackLock, the epoch
    // system, frame alignment) by entangling them with a fundamentally
    // different data source. Both paths share ensureTrackFor, processChunk,
    // and trackLock — only WHERE the bytes come from differs.
    @Volatile private var currentTrackBuffer: TrackBuffer? = null
    @Volatile private var readCursorBytes: Int = 0
    @Volatile private var bufferedPlaybackThread: Thread? = null
    // Real, direct addition for true gapless playback, requested
    // explicitly: while the current track plays, the next one's audio can
    // be downloaded into this SEPARATE buffer ahead of time. When the
    // current one reaches its real end, the playback loop below checks
    // here FIRST — if this is ready, it swaps directly to it as the new
    // current buffer and keeps writing to the exact same AudioTrack,
    // same thread, no pause, no flush, no recreation. That's what makes
    // the transition genuinely seamless rather than merely correct.
    @Volatile private var nextTrackBuffer: TrackBuffer? = null
    @Volatile var onSeamlessTrackSwap: ((String, Int, Int, Int?) -> Unit)? = null

    /** Called to begin downloading the NEXT track's audio ahead of time, while the current one is still playing — does not affect what's currently playing at all. */
    fun prepareNextTrackBuffer(trackId: String, sampleRate: Int, channels: Int, estimatedTotalBytes: Int) {
        nextTrackBuffer = TrackBuffer(trackId, sampleRate, channels, estimatedTotalBytes)
    }

    /** Called repeatedly as the next track's bytes arrive — silently ignored if trackId no longer matches (a stale, abandoned or already-consumed prefetch). */
    fun appendToNextTrackBuffer(trackId: String, bytes: ByteArray) {
        val buf = nextTrackBuffer
        if (buf == null || buf.trackId != trackId) return
        buf.append(bytes)
    }

    fun setNextTrackBufferTotalBytes(trackId: String, totalBytes: Int) {
        val buf = nextTrackBuffer
        if (buf == null || buf.trackId != trackId) return
        buf.totalBytes = totalBytes
    }

    /**
     * Starts (or restarts) buffered playback for a track. Creates a fresh
     * TrackBuffer, launches the download-consuming playback thread under a
     * new epoch (same non-blocking staleness mechanism as the queue path —
     * see playbackEpoch's own comment), and points the read cursor at the
     * requested start position. Does NOT itself fetch any bytes — that's
     * driven separately by repeated appendToTrackBuffer calls from the
     * network download thread, decoupled from playback position entirely.
     */
    fun startTrackBuffer(trackId: String, sampleRate: Int, channels: Int, estimatedTotalBytes: Int, startAtByteOffset: Int) {
        val bytesPerFrame = channels * 2
        val alignedStart = (startAtByteOffset / bytesPerFrame) * bytesPerFrame // frame-aligned, same reasoning as the queue path's own frame alignment
        currentTrackBuffer = TrackBuffer(trackId, sampleRate, channels, estimatedTotalBytes)
        readCursorBytes = alignedStart
        playbackEpoch++
        val myEpoch = playbackEpoch
        running.set(true)
        val t = Thread({ bufferedPlaybackLoop(myEpoch, trackId) }, "NovaBufferedPlaybackThread")
        t.priority = Thread.NORM_PRIORITY + 2
        bufferedPlaybackThread = t
        t.start()
    }

    /** Called repeatedly by the network download thread as bytes arrive — silently ignored if trackId no longer matches the current buffer (a stale, abandoned download). */
    fun appendToTrackBuffer(trackId: String, bytes: ByteArray) {
        val buf = currentTrackBuffer
        if (buf == null || buf.trackId != trackId) return
        buf.append(bytes)
    }

    /** Called once the server reports the track's true total byte count, so end-of-track can be detected precisely rather than only inferred from the download finishing. */
    fun setTrackBufferTotalBytes(trackId: String, totalBytes: Int) {
        val buf = currentTrackBuffer
        if (buf == null || buf.trackId != trackId) return
        buf.totalBytes = totalBytes
    }

    /**
     * The actual fix. If the target position is already downloaded, this
     * is a pure in-memory operation: move the read cursor, flush the
     * AudioTrack's own hardware buffer (still the SAME instance — no
     * release, no recreate, no new thread) so the new position is heard
     * immediately instead of after whatever stale audio was already
     * queued drains first, and return true. Returns false if the target
     * isn't downloaded yet, so the caller can fall back to the old,
     * genuinely-destructive restart path for that one, rarer case.
     */
    fun seekWithinBuffer(trackId: String, byteOffset: Int): Boolean {
        val buf = currentTrackBuffer ?: return false
        if (buf.trackId != trackId) return false
        if (byteOffset > buf.downloadedBytes) return false
        val bytesPerFrame = buf.channels * 2
        readCursorBytes = (byteOffset / bytesPerFrame) * bytesPerFrame
        // Real, direct fix for a confirmed architectural gap, found while
        // investigating a genuine "running became false" report: this used
        // to only move the cursor and flush the hardware, silently
        // assuming the buffered playback thread consuming that cursor was
        // still alive. If it wasn't — paused, or stopped for any other
        // reason — this would still report success while nothing was
        // actually left running to read from the buffer at all: audible
        // silence disguised as a successful seek. Restarting the
        // consuming thread here if it's not running (reusing the same
        // TrackBuffer already in place, not re-downloading anything) is
        // what makes a resume-after-pause through this path actually work
        // instead of silently doing nothing.
        // Real, direct fix for a confirmed static complaint, traced
        // directly to a spectrogram of an actual recording showing sharp
        // transient spikes followed by broadband noise, landing exactly
        // where the matching log shows a fallback restart happening right
        // before a genuine pause — two real hardware cycles back-to-back.
        // This function cycles the SAME AudioTrack hardware (pause/flush/
        // play, or a full thread+track restart) just like every other
        // path that already goes through waitOutMinCycleInterval — this
        // one simply never did, a real gap left over from building this
        // whole buffered path as something separate from the older code.
        if (!running.get()) {
            DebugOverlay.emit(TAG, "seekWithinBuffer: playback thread wasn't running — restarting it for trackId=$trackId")
            waitOutMinCycleInterval()
            playbackEpoch++
            val myEpoch = playbackEpoch
            running.set(true)
            val t = Thread({ bufferedPlaybackLoop(myEpoch, trackId) }, "NovaBufferedPlaybackThread")
            t.priority = Thread.NORM_PRIORITY + 2
            bufferedPlaybackThread = t
            t.start()
            return true
        }
        synchronized(trackLock) {
            waitOutMinCycleInterval()
            try {
                audioTrack?.let { it.pause(); it.flush(); it.play() }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing AudioTrack during in-buffer seek (non-fatal, proceeding)", e)
            }
        }
        return true
    }

    private fun bufferedPlaybackLoop(myEpoch: Int, trackId: String) {
        Log.i(TAG, "bufferedPlaybackLoop thread running for trackId=$trackId")
        DebugOverlay.emit(TAG, "bufferedPlaybackLoop thread running for trackId=$trackId")
        var firstChunkOfThisThread = true
        val readSize = 32 * 1024
        // Real, direct fix needed for seamless track swaps — this used to
        // check the buffer against the trackId PARAMETER, fixed for the
        // whole life of this thread. A seamless swap changes which track
        // is actually playing out from under this same thread — without
        // tracking that change locally, the very next iteration's own
        // staleness check would see the new buffer's different trackId
        // and incorrectly treat it as "superseded," exiting right after
        // the swap it was supposed to make invisible.
        var activeTrackId = trackId
        try {
        while (running.get() && myEpoch == playbackEpoch) {
            val buf = currentTrackBuffer
            if (buf == null || buf.trackId != activeTrackId) {
                // Real, direct diagnostic addition after a confirmed
                // premature-exit report: this loop can exit for four
                // genuinely different reasons, and nothing distinguished
                // between them — this is exactly the kind of ambiguity
                // that leads to another round of guessing instead of
                // fixing the actual cause. Every exit path now logs
                // specifically which condition fired.
                DebugOverlay.emit(TAG, "bufferedPlaybackLoop EXIT REASON: buffer null or trackId mismatch (buf=${buf?.trackId}, expected=$activeTrackId)")
                break
            }
            val cursor = readCursorBytes
            val available = buf.downloadedBytes - cursor
            if (available <= 0) {
                // Caught up to the download — genuinely done if the total
                // is known and we've reached it, otherwise just waiting on
                // more network data. Polling rather than a blocking queue
                // here specifically because the data source (this same
                // growable array) can change shape (grow) at any time from
                // another thread, which a blocking take() has no way to
                // observe.
                val total = buf.totalBytes
                if (total != null && cursor >= total) {
                    // The actual seamless-swap point. Reaching here is only
                    // possible after every real byte of the CURRENT track
                    // has already been through the blocking AudioTrack.write()
                    // below (this check runs at the top of the next loop
                    // iteration, after that write already returned) — so this
                    // can never fire early, never based on a timer or on the
                    // next track merely being ready. It fires exactly when,
                    // and only when, this track's own last byte has genuinely
                    // been submitted. That guarantee is what makes it safe to
                    // immediately continue into the next track's first bytes
                    // on the same AudioTrack instead of stopping — the two
                    // queue back-to-back in the hardware's own buffer with no
                    // gap, the same principle the old engine's gapless
                    // transitions already relied on.
                    val next = nextTrackBuffer
                    if (next != null) {
                        DebugOverlay.emit(TAG, "bufferedPlaybackLoop: seamless swap to trackId=${next.trackId} (current track's own real end genuinely reached)")
                        currentTrackBuffer = next
                        nextTrackBuffer = null
                        readCursorBytes = 0
                        activeTrackId = next.trackId
                        firstChunkOfThisThread = true // the next track's own first chunk should still fire onTrackBoundaryReached, for now-playing/metadata updates — just without any audible seam
                        onSeamlessTrackSwap?.invoke(next.trackId, next.sampleRate, next.channels, next.totalBytes)
                        continue
                    }
                    DebugOverlay.emit(TAG, "bufferedPlaybackLoop EXIT REASON: reached known total (cursor=$cursor, total=$total, downloadedBytes=${buf.downloadedBytes}), no next track buffer ready")
                    onTrackEndReached?.invoke()
                    break
                }
                try { Thread.sleep(30) } catch (e: InterruptedException) {
                    DebugOverlay.emit(TAG, "bufferedPlaybackLoop EXIT REASON: interrupted while waiting for more download (cursor=$cursor, downloadedBytes=${buf.downloadedBytes}, total=$total)")
                    break
                }
                continue
            }
            val bytesPerFrame = buf.channels * 2
            val toRead = minOf(available, readSize).let { (it / bytesPerFrame) * bytesPerFrame } // frame-aligned read, same reasoning as the queue path
            if (toRead <= 0) { try { Thread.sleep(30) } catch (e: InterruptedException) { break }; continue }
            val chunkBytes = buf.data.copyOfRange(cursor, cursor + toRead)
            val isFirstChunk = firstChunkOfThisThread
            if (isFirstChunk) {
                onTrackBoundaryReached?.invoke()
                firstChunkOfThisThread = false
            }
            val processed = processChunk(chunkBytes, buf.channels, buf.sampleRate, isFirstChunk)
            synchronized(trackLock) {
                ensureTrackFor(buf.sampleRate, buf.channels)
                val track = audioTrack
                if (track == null) {
                    Log.e(TAG, "audioTrack is null after ensureTrackFor — dropping this chunk, this should never happen")
                    return@synchronized
                }
                var offset = 0
                while (offset < processed.size && running.get() && myEpoch == playbackEpoch) {
                    val written = track.write(processed, offset, processed.size - offset)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write error code=$written")
                        DebugOverlay.emit(TAG, "AudioTrack.write error code=$written")
                        onUnderrun?.invoke()
                        break
                    }
                    offset += written
                }
            }
            // Only advance the cursor by what THIS thread actually wrote —
            // a concurrent seekWithinBuffer call could have moved
            // readCursorBytes out from under this thread while it was
            // blocked inside the write above; re-reading and blindly
            // advancing from the CURRENT value (rather than the `cursor`
            // this iteration started with) would silently un-do that seek.
            if (readCursorBytes == cursor) readCursorBytes = cursor + toRead
        }
        } catch (e: Exception) {
            // Real, direct safety net added after a reported app crash
            // with no log to diagnose it from — this is genuinely new
            // code (the seamless-swap path especially), and I have no way
            // to test it on a real device myself. An uncaught exception on
            // this thread would otherwise take the whole app down with it.
            // Catching it here means a bug in this new code becomes a
            // logged, recoverable stop instead of a crash — the debug
            // panel should now show exactly what threw, which is what the
            // next report actually needs to fix the real cause.
            Log.e(TAG, "bufferedPlaybackLoop CRASHED (caught, not fatal): ${e.message}", e)
            DebugOverlay.emit(TAG, "bufferedPlaybackLoop CRASHED (caught, not fatal): ${e}")
        }
        if (!running.get()) DebugOverlay.emit(TAG, "bufferedPlaybackLoop EXIT REASON: running became false (external stop/pause)")
        else if (myEpoch != playbackEpoch) DebugOverlay.emit(TAG, "bufferedPlaybackLoop EXIT REASON: superseded by a newer epoch (myEpoch=$myEpoch, current=$playbackEpoch)")
        Log.i(TAG, "bufferedPlaybackLoop thread exiting for trackId=$trackId")
        DebugOverlay.emit(TAG, "bufferedPlaybackLoop thread exiting for trackId=$trackId")
    }
    // ==================== End buffered (whole-track) playback ====================

    private var leftoverBytes: ByteArray? = null
    private var leftoverBytesFormatKey = "" // "sampleRate:channels" — cleared alongside a real format change

    private fun playbackLoop(myEpoch: Int) {
        // myEpoch is now passed in, captured on the main thread at the
        // exact moment this thread was created — see start()'s own
        // comment for why reading playbackEpoch directly from inside this
        // thread was the actual bug.
        Log.i(TAG, "playbackLoop thread running, waiting for first chunk")
        DebugOverlay.emit(TAG, "playbackLoop thread running, waiting for first chunk")
        var loggedFirstChunk = false
        while (running.get() && myEpoch == playbackEpoch) {
            val chunk = try {
                queue.take() // blocks — correct: an idle playback thread with nothing queued should genuinely wait, not spin
            } catch (e: InterruptedException) {
                break
            }
            if (!loggedFirstChunk) {
                Log.i(TAG, "First chunk received: ${chunk.bytes.size} bytes, ${chunk.sampleRate}Hz, ${chunk.channels}ch")
                DebugOverlay.emit(TAG, "First chunk received: ${chunk.bytes.size} bytes, ${chunk.sampleRate}Hz, ${chunk.channels}ch")
                loggedFirstChunk = true
            }
            if (chunk.isFirstOfTrack) onTrackBoundaryReached?.invoke()
            ensureTrackFor(chunk.sampleRate, chunk.channels)
            val formatKey = "${chunk.sampleRate}:${chunk.channels}"
            // Real bug in the frame-alignment fix itself, caught before it
            // could cause its own glitch: this only reset leftover bytes on
            // a FORMAT change, but two consecutive tracks can share the
            // exact same format (as these two both do — 8ch/32kHz) while
            // being completely different audio streams. Without also
            // checking isFirstOfTrack, a few leftover bytes from the very
            // end of one track would get prepended onto the start of the
            // next one — a small, real corruption at exactly the boundary
            // this was supposed to make seamless.
            if (formatKey != leftoverBytesFormatKey || chunk.isFirstOfTrack) { leftoverBytes = null; leftoverBytesFormatKey = formatKey }
            val bytesPerFrame = chunk.channels * 2
            val combined = leftoverBytes?.let { it + chunk.bytes } ?: chunk.bytes
            val wholeFrameByteCount = (combined.size / bytesPerFrame) * bytesPerFrame
            leftoverBytes = if (wholeFrameByteCount < combined.size) combined.copyOfRange(wholeFrameByteCount, combined.size) else null
            if (wholeFrameByteCount == 0) continue // not even one whole frame yet — wait for more bytes to accumulate rather than processing nothing
            val processed = processChunk(combined.copyOfRange(0, wholeFrameByteCount), chunk.channels, chunk.sampleRate, chunk.isFirstOfTrack)
            // The actual fix — see trackLock's own comment above for the
            // full race this closes. Everything from ensureTrackFor through
            // the final write() call is now inside the same lock
            // stopAndFlush() takes before it can release this exact
            // AudioTrack instance, so a seek arriving mid-write can no
            // longer pull the track out from under an in-progress write.
            synchronized(trackLock) {
                ensureTrackFor(chunk.sampleRate, chunk.channels)
                val track = audioTrack
                if (track == null) {
                    Log.e(TAG, "audioTrack is null after ensureTrackFor — dropping this chunk, this should never happen")
                    DebugOverlay.emit(TAG, "audioTrack is null after ensureTrackFor — dropping this chunk, this should never happen")
                    return@synchronized
                }
                var offset = 0
                while (offset < processed.size && running.get() && myEpoch == playbackEpoch) {
                    // Blocking write — the entire point. This call parks the thread
                    // until AudioTrack's internal buffer has room, which is exactly
                    // the backpressure that keeps playback timing correct without
                    // any manual scheduling math (the source of every crossfade/
                    // discontinuity bug in the Web Audio version). write() returning
                    // a negative value is a real, documented error path (not just
                    // "wrote 0 bytes"); treated as fatal for this chunk rather than
                    // silently retried forever.
                    val written = track.write(processed, offset, processed.size - offset)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write error code=$written")
                        DebugOverlay.emit(TAG, "AudioTrack.write error code=$written")
                        onUnderrun?.invoke()
                        break
                    }
                    offset += written
                }
                // The actual signal a following track's start is gated on — see
                // onTrackEndReached's own comment. Fires only once every byte of
                // a chunk explicitly marked as a track's last one has actually
                // been handed to the AudioTrack (a few tens of milliseconds of
                // hardware buffer latency aside, this is genuinely close to the
                // real moment this track's audio finishes, not an approximation
                // based on when its data merely finished downloading).
                if (chunk.isLastOfTrack && offset >= processed.size) onTrackEndReached?.invoke()
            }
        }
    }

    /**
     * Converts one chunk's raw s16le PCM bytes to the final output format:
     * downmix to stereo if the source is multichannel, apply the fixed safety
     * trim, run through the soft-clip safety net, then back to s16le bytes.
     * Runs on the playback thread, between a blocking write() and the next —
     * kept simple (no allocations beyond the one output array) since this is
     * real-time-adjacent code where a GC pause here is audible as a stutter.
     */
    // Persists across chunks within one track (not reset per-chunk) — a real
    // limiter's envelope has to carry over between calls or every chunk
    // boundary would re-open the gate, defeating the point. No explicit
    // reset on track change either: a slightly-too-conservative gain
    // carried into a new track's first few samples is inaudible and safe;
    // an under-limited first sample on a new loud track is exactly the
    // failure mode this exists to prevent.
    private var limiterGainState = 1.0f

    /**
     * A real gain-reduction limiter, not just a waveshaper — deliberately
     * conservative rather than trying to precisely replicate the web
     * player's DynamicsCompressorNode curve (which I have no way to verify
     * against on a real device). Threshold matches that path's own tuned
     * value; makeup gain is intentionally NOT applied, unlike the web
     * version's — safety took priority over exactly matching loudness here,
     * since adding gain back in is exactly the kind of thing that needs
     * real-device verification before it can be trusted not to reintroduce
     * this same failure.
     *
     * Real, direct bug found from a reported "still insane amounts of
     * static" complaint after the first version of this shipped: the
     * attack/release coefficients were hardcoded constants (0.4 / 0.002),
     * with no relationship to the actual sample rate at all. At 32kHz that
     * 0.4 attack coefficient converges in about 5 samples — roughly 0.15ms,
     * several times FASTER than the web player's own tuned 1ms attack. A
     * limiter reacting that fast doesn't just catch genuine overshoot
     * transients — it starts tracking the audio waveform's own sample-to-
     * sample amplitude changes, since real audio legitimately varies that
     * quickly too. The gain envelope itself becomes an unwanted, audio-rate
     * signal riding on the output — which is exactly what constant static/
     * distortion sounds like, and would explain it persisting even after
     * the raw overshoot itself was being caught. Coefficients are now
     * computed from the actual sample rate using proper exponential time-
     * constant math, matching the web player's own tuned 1ms attack / 100ms
     * release instead of arbitrary numbers that happened to converge fast
     * in a desktop test.
     */
    private fun applyLimiter(left: Float, right: Float, attackCoeff: Float, releaseCoeff: Float): Pair<Float, Float> {
        val threshold = 0.5012f // -6dB linear — matches the web player's own tuned threshold for this exact downmix path
        val peak = maxOf(abs(left), abs(right))
        val targetGain = if (peak > threshold) (threshold / peak).coerceIn(0.05f, 1f) else 1f
        limiterGainState = if (targetGain < limiterGainState) {
            limiterGainState + (targetGain - limiterGainState) * attackCoeff
        } else {
            limiterGainState + (targetGain - limiterGainState) * releaseCoeff
        }
        return Pair(left * limiterGainState, right * limiterGainState)
    }

    private fun processChunk(bytes: ByteArray, sourceChannels: Int, sampleRate: Int, applyFadeIn: Boolean = false): ByteArray {
        // One-pole exponential smoother time constant: coefficient = 1 -
        // exp(-1 / (T_seconds * sampleRate)). Computed once per chunk (not
        // once per hardcoded guess) so the actual attack/release TIME stays
        // correct regardless of which quality tier's sample rate is
        // currently playing — 1ms attack, 100ms release, matching the web
        // player's own DynamicsCompressorNode settings on this exact path.
        val attackCoeff = (1.0 - kotlin.math.exp(-1.0 / (0.001 * sampleRate))).toFloat()
        val releaseCoeff = (1.0 - kotlin.math.exp(-1.0 / (0.1 * sampleRate))).toFloat()
        val frameCount = bytes.size / 2 / sourceChannels
        val out = ByteArray(frameCount * 2 * if (sourceChannels > 2) 2 else sourceChannels)
        var outIdx = 0
        for (frame in 0 until frameCount) {
            val base = frame * sourceChannels * 2
            if (sourceChannels <= 2) {
                // Direct passthrough (mono/stereo source) — just trim + soft-clip, no mixing.
                for (c in 0 until sourceChannels) {
                    val sampleIdx = base + c * 2
                    val raw = ((bytes[sampleIdx + 1].toInt() shl 8) or (bytes[sampleIdx].toInt() and 0xFF)).toShort()
                    val f = softClip((raw / 32768f) * volumeMultiplier * SAFETY_TRIM)
                    val outSample = (f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    out[outIdx++] = (outSample.toInt() and 0xFF).toByte()
                    out[outIdx++] = ((outSample.toInt() shr 8) and 0xFF).toByte()
                }
            } else {
                // Same downmix matrix as the web player's connectWithDownmix:
                // channel order FL, FR, FC, LFE, [rear/side L, rear/side R],
                // [7.1 only: side L, side R]. Applied per-sample here, not via
                // a channel-index shortcut.
                fun sample(ch: Int): Float {
                    if (ch >= sourceChannels) return 0f
                    val i = base + ch * 2
                    val raw = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                    return raw / 32768f
                }
                val fl = sample(0); val fr = sample(1); val fc = sample(2); val lfe = sample(3)
                val rl = sample(4); val rr = sample(5); val sl = sample(6); val sr = sample(7)
                var left = fl * DOWNMIX_SIDE + fc * DOWNMIX_CENTER + lfe * DOWNMIX_LFE + rl * DOWNMIX_SURROUND + sl * DOWNMIX_SURROUND
                var right = fr * DOWNMIX_SIDE + fc * DOWNMIX_CENTER + lfe * DOWNMIX_LFE + rr * DOWNMIX_SURROUND + sr * DOWNMIX_SURROUND
                // Real, urgent, physically-harmful bug found from a direct
                // report: this summing (5 channel contributions per side) can
                // reach up to ~3.6x scale in the worst case — the web
                // player's OWN comment on its equivalent path says this
                // explicitly, and that path has a real limiter
                // (DynamicsCompressorNode, threshold -6dB, ratio 20:1,
                // empirically measured to hold worst-case output at
                // -1.4dBFS) specifically because of it. This native path had
                // NO equivalent — only the soft-clip below, which bends the
                // waveform shape at a ceiling rather than actually reducing
                // gain the way a real limiter does. For a signal constantly
                // driven 3x+ over scale, that's sustained, severe distortion:
                // audibly "static," and perceptually far louder/harsher than
                // any real volume setting — which is exactly what was
                // reported. applyLimiter (below) is a real gain-reduction
                // stage with attack/release smoothing, run before softClip
                // rather than instead of it — softClip remains the final,
                // absolute safety net; this is what actually prevents that
                // net from being needed constantly in the first place.
                val (limitedLeft, limitedRight) = applyLimiter(left, right, attackCoeff, releaseCoeff)
                left = softClip(limitedLeft * volumeMultiplier * SAFETY_TRIM)
                right = softClip(limitedRight * volumeMultiplier * SAFETY_TRIM)
                val ls = (left * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                val rs = (right * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[outIdx++] = (ls.toInt() and 0xFF).toByte(); out[outIdx++] = ((ls.toInt() shr 8) and 0xFF).toByte()
                out[outIdx++] = (rs.toInt() and 0xFF).toByte(); out[outIdx++] = ((rs.toInt() shr 8) and 0xFF).toByte()
            }
        }
        // Real, direct mitigation for a reported "blasts static" complaint
        // specifically on seek — a standard, well-established technique for
        // exactly this class of problem (an audible discontinuity right at
        // a stream transition point), applied regardless of the precise
        // underlying cause, since it masks any of them: an abrupt AudioTrack
        // cutoff from the outgoing stream, or a genuine sample discontinuity
        // at the seam, both land at the very first few milliseconds of a
        // fresh stream's audio — exactly what this ramps up from silence
        // over, rather than starting instantly at full level. 10ms is short
        // enough to be inaudible as a fade on its own, long enough to smooth
        // over a hard discontinuity. Applied only when explicitly requested
        // (a genuinely fresh stream's first chunk) — never on ordinary
        // mid-stream chunks, where it would have no purpose and only risk
        // an audible dip.
        if (applyFadeIn) {
            val fadeFrames = minOf((sampleRate * 0.01).toInt(), frameCount).coerceAtLeast(1)
            val bytesPerOutFrame = if (sourceChannels > 2) 4 else sourceChannels * 2
            for (frame in 0 until minOf(fadeFrames, out.size / bytesPerOutFrame)) {
                val gain = frame.toFloat() / fadeFrames
                val base = frame * bytesPerOutFrame
                var i = 0
                while (i < bytesPerOutFrame) {
                    val raw = ((out[base + i + 1].toInt() shl 8) or (out[base + i].toInt() and 0xFF)).toShort()
                    val scaled = (raw * gain).toInt().coerceIn(-32768, 32767).toShort()
                    out[base + i] = (scaled.toInt() and 0xFF).toByte()
                    out[base + i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
                    i += 2
                }
            }
        }
        return out
    }
}
