# NOVA Android wrapper — native audio engine

## What this actually is, honestly

This is a WebView wrapper around the existing music.cosmoscraft.net player —
same UI, same login, same library, same queue. The one thing that's
different: when running inside this app (not a regular mobile browser),
actual audio playback is handed off to native Android code using
`AudioTrack` in streaming mode, instead of the Web Audio API.

**Why that's the actual fix, not just a guess:** every crackle/discontinuity
bug chased across the web player this whole debugging effort traced back to
one structural fact — Web Audio API plays audio by scheduling separate,
discrete buffer objects end-to-end, and stitching two of those together
always has a real seam, however carefully it's smoothed. `AudioTrack` in
streaming mode has no such concept at all: it's one continuous stream that
bytes get written into, the same way piping raw audio to `aplay` never
clicks between reads. This isn't a tweak to reduce the crackling — it
removes the mechanism that was causing it.

## What I could NOT do, and why

**I have not compiled or run this code.** The environment I built it in has
a JDK but no Android SDK, no Gradle, and no way to download either (the
domains needed are outside what I can reach). I syntax-checked every Kotlin
file with a plain (old, 1.3.31) Kotlin compiler as a partial check — that
catches genuine syntax errors (and did catch one real one, a trailing-comma
issue, already fixed) — but it cannot verify anything that depends on the
actual Android SDK, since that classpath isn't available to it either.
**This needs to be built and tested on a real device before it's trustworthy.**

Specific things I'd expect to need adjusting once it actually runs:

- **Buffer sizing** (`NativeAudioEngine.MIN_BUFFER_MULTIPLIER`) — 4x
  `AudioTrack.getMinBufferSize()` is a reasonable starting point from general
  Android audio guidance, not a number measured against your actual network
  conditions or device. If you still hear underruns (a different, "empty
  buffer" kind of glitch, not the discontinuity-click kind this was built to
  fix), raise this.
- **Seek bar / time display / pause-resume in native mode are not built.**
  Audio plays front-to-back gaplessly and advances through the queue
  correctly, but the existing seek bar and elapsed-time UI read from
  `currentBuffer.duration`, which doesn't exist in native mode (there's no
  client-side audio buffer at all — that's the point). Wiring native
  playback position back into that UI is real, scoped-out work, not done
  here.
- **Track-to-track gaps when the format changes** (different sample rate,
  e.g. 44.1kHz -> 48kHz between two tracks) still have a small, real gap —
  a brand new `AudioTrack` needs its own hardware startup. Real bit-perfect
  Android players report roughly 7-10ms for this same case; it will not be
  literally zero. Within one track, there is no gap at all, by construction.
- **Quality tier selection is not adaptive in native mode** — see
  `playTrackNative` in app.js; it uses whatever quality is currently
  selected in Settings rather than the web player's own throughput-based
  auto-selection. Deliberately simplified for a first pass, not an
  oversight.
- **The launcher icon is a placeholder** (`ic_launcher_foreground.xml`) — a
  flat circle, not real artwork. Android Studio's Image Asset Studio is the
  easy way to replace it.

## How to actually build this

You'll need [Android Studio](https://developer.android.com/studio) (free) —
it bundles its own Gradle and can download the Android SDK itself, so this
is the path of least friction, not a suggestion to make it harder on
yourself:

1. Open Android Studio -> **Open** -> select this `nova-android` folder.
2. Let it sync (first sync will download the Android SDK/build tools if you
   don't already have them — this needs internet access, which is exactly
   what I don't have here).
3. Plug in a real device (USB debugging enabled) or start an emulator.
4. Click Run. Android Studio's own Logcat panel is where you'll see
   `NovaAudioEngine` / `NovaBridge` log lines if something goes wrong — I
   deliberately logged the AudioTrack creation parameters and every write
   error specifically so a real failure is visible there, not silent.

## Files worth knowing about

- `NativeAudioEngine.kt` — the actual audio fix. One continuous `AudioTrack`,
  a bounded queue, a dedicated playback thread doing blocking `write()`
  calls. Also does the multichannel-to-stereo downmix (reusing the same
  coefficients already tuned in the web player) and the same fixed -1dB
  safety trim + soft-clip curve as the web client.
- `NativeAudioBridge.kt` — the JS<->native boundary. Deliberately thin: JS
  still owns every decision about what to fetch and when (all the retry/
  backoff/prefetch logic that was already proven this session stays exactly
  where it was); this class only does the actual HTTP fetch and streams
  bytes into the engine as they arrive.
- `MainActivity.kt` — WebView setup, restricted to loading only
  `music.cosmoscraft.net`, using the current Android-recommended
  origin-restricted bridge API (`WebViewCompat.addWebMessageListener`)
  rather than the older, unrestricted `addJavascriptInterface`.
- `PlaybackService.kt` — foreground service + media session, so a
  screen-off phone doesn't get its network activity throttled the way a
  background browser tab would, and so a wired/Bluetooth headset's
  play-pause button and the lock screen's transport controls have something
  to reach. Forwards those events into the same JS transport functions the
  on-screen buttons already call — does not duplicate that logic natively.
- The `app.js` changes (in the nova-server zip, not here) — a new,
  self-contained `playTrackNative()` function, reached only when
  `window.NovaNative` exists. Every existing web-browser code path is
  completely untouched.
