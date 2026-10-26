# Android silence investigation: Aoede, Vitreo and LibrePods

Investigation date: 2026-10-26. **The reported loss of audible output has not been reproduced on a physical device.** This change corrects independently testable analyser lifecycle defects and prepares diagnostics; it does not establish the cause of the reported Bluetooth silence.

## Versions and evidence boundaries

| Component | Inspected source | Installed on the affected phone |
|---|---|---|
| Aoede | `2ef37c63ae426e48796b13a67f0247028f6e3d8b`, Android 1.1.19 / 25. This was also HEAD at the start: no intervening source differences. Candidate changes below build 1.1.20 / 26. | **Unverified** |
| Vitreo | [`a109f7ae0519bed2a13db7b452902c47be6699e2`](https://github.com/sPROFFEs/vitreo-launcher/tree/a109f7ae0519bed2a13db7b452902c47be6699e2), `version.properties` declares 0.10.2 / 1002. | **Unverified** |
| LibrePods | [`89356c08cea3db0b6b54792d9329d09a87cc85fa`](https://github.com/librepods-org/librepods/tree/89356c08cea3db0b6b54792d9329d09a87cc85fa), Android source declares 1.0.4 stable. Build/flavor and installed app may differ. | **Unverified** |
| Android / GrapheneOS | No phone attached; `adb devices -l` returned no devices. | **Unverified** |
| WebView provider / version | No phone attached. | **Unverified** |
| AirPods model / firmware | Cannot infer this from a Bluetooth display name or these repositories. | **Unverified** |

A text request for the installed versions was left pending. The capture helper retrieves the installed package versions, Android release/SDK/build/security patch and selected WebView provider when an authorized phone is available. Confirm GrapheneOS in Settings and the AirPods model/firmware in LibrePods or the device's own information. A repository version is not proof of an installed version.

## What the code shows

### Confirmed code defects

1. The previous `closeSpectrum()` set `spectrum = null` and called asynchronous `AudioContext.close()` without waiting for disposal. A following sample could allocate another graph while the previous context was still closing or its resume promise was unresolved. A controlled test with a deferred close demonstrates this overlap. This is a resource lifecycle defect; **the test does not demonstrate a loss of Bluetooth audio**.
2. Optional graph cleanup could abort at `disconnect()` or an individual track's `stop()`, skipping subsequent cleanup. Forced cleanup failures reproduce that behavior. Cleanup now attempts all owned resources independently.
3. Native sampling callbacks already had a generation check, but the JavaScript request accepted only a boolean. A deliberately reordered old request could recreate or dispose a newer graph. Requests now carry a monotonically increasing generation, checked before side effects. **The test reorders calls intentionally; Android/WebView delivering that order on the affected phone has not been observed.**

The existing identity check on a rejected `resume()` already prevented that old rejection from closing a different current graph. It is retained. Closure is now serialized as well; unavailable, pending or failed analysis uses the existing animation fallback. If disposal fails, analysis stays disabled for the rest of that document, including subsequent song changes, rather than allocating more graphs while the previous context's release is uncertain. The playing element is left untouched.

### Aoede lifecycle, focus and service

- `AoedeApp.attachPlayer()` retains one WebView; returning to the app reattaches it. `MainActivity.onStop()` collapses the island and closes its settings, without calling WebView `onPause()`, `pauseTimers()`, `loadUrl()` or `destroy()`. Screen/keyguard transitions change island sampling/visibility, not the media source.
- Player destruction occurs for a server/connection configuration change, explicit destruction, or renderer termination. Renderer termination has a pre-existing recovery path. The diagnostic change records these boundaries; it adds no renderer/process restart path.
- The native MediaSession mirrors JavaScript's state and clock. `audible` in the existing shim means a playback event/readiness condition, **not a measurement of audible output**. It is logged as `eventPlaying`, not as proof that sound reaches the headphones.
- `PlaybackService` uses a `mediaPlayback` foreground service. Its partial wake lock is held while playing/buffering, acquired with a ten-minute timeout and renewed after nine minutes. The noisy receiver is registered while playback needs foreground protection; `ACTION_AUDIO_BECOMING_NOISY` explicitly pauses locally. State changes, foreground entry, wake-lock state and noisy events are now observable.
- Aoede's native wrapper does not add its own audio-focus request. Chromium manages the WebView's audio focus. Adding a second focus owner during this investigation would change the behavior under test. Android can enforce muting/fading after focus loss, especially on newer releases, even when an application's UI still reports playback. See [Android focus rules](https://developer.android.com/media/optimize/audio-focus) and [Chromium's audio-focus delegate](https://chromium.googlesource.com/chromium/src/+/master/content/public/android/java/src/org/chromium/content/browser/AudioFocusDelegate.java).
- The ordinary stream-stall recovery and idle/buffering service timeouts predate this change. They are not changed or used as a remedy for this silence. No new reconnect, reload, pause/play or resume loop is introduced.

### Two different AudioContexts must be distinguished

The island samples `audio.captureStream()` through `createMediaStreamSource()` and an analyser, with **no connection to the destination**. This is a copy, unlike the optional audible ReplayGain/crossfade graph in `audio_engine.js`, which uses `createMediaElementSource()` and connects to the destination. Stopping a copied track does not itself demonstrate that the source element's output stops; see the [media capture draft](https://www.w3.org/TR/mediacapture-fromelement/).

The diagnostics distinguish the island context from `playbackContext`, and report the optional playback graph's gain/fade values and settings. An element clock may advance while an audible graph is suspended or its gain is low. This is an additional **hypothesis**, not an observed state on the affected phone. Reduce effects disables island sampling but does not disable ReplayGain/crossfade; record those playback preferences separately and hold them constant in each comparison.

### Vitreo and LibrePods

In the pinned Vitreo source, [`LockScreenRuntime`](https://github.com/sPROFFEs/vitreo-launcher/blob/a109f7ae0519bed2a13db7b452902c47be6699e2/app/src/main/java/dev/vitreo/launcher/lock/LockScreenRuntime.kt) reads audio mode to decide whether it may present the custom lock screen. It does not request focus or change audio mode/Bluetooth routing there. [`ControlCenterController`](https://github.com/sPROFFEs/vitreo-launcher/blob/a109f7ae0519bed2a13db7b452902c47be6699e2/app/src/main/java/dev/vitreo/launcher/data/ControlCenterController.kt) observes playback and reads volume; volume/media control methods are interaction-driven. This supports the previous source review, but does not rule out an indirect lifecycle or platform interaction.

LibrePods' [`MediaController`](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/utils/MediaController.kt) observes global playback configurations and `isMusicActive`. Depending on its state/settings, it sends media information, requests takeover, controls playback and adjusts music volume for conversational awareness. Its [`AirPodsService`](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt) contains connection/ownership logic. An extra graph's lifecycle **could** affect platform observations, but no capture currently demonstrates this chain or attributes the reported silence to LibrePods.

Reconnection restoring sound is compatible with a routing/ownership issue; restarting Aoede restoring sound is also compatible with resetting a WebView graph. Neither recovery identifies the cause.

## Bounded diagnostic capture

The source candidate is **1.1.20 / 26**. The existing production release is not replaced as part of this investigation. Install a candidate signed with the existing distribution key when testing an update; do not uninstall the working app or replace its signing identity to install a diagnostic build.

With ADB authorized and candidate Aoede already running/playing, from the repository:

```sh
python3 android/capture_audio.py --seconds 300 --output /tmp/aoede-audio-case-01
```

Pass `--adb /path/to/adb` if necessary. `ANDROID_SERIAL` can select a device. The standalone Android source archive also includes `capture_audio.py`. Each output directory must be new.

The helper temporarily enables `log.tag.AoedeAudio=DEBUG`, then restores its previous value on completion or interruption. It starts/stops only its own log reader, not the application, audio, launcher, LibrePods or Bluetooth. Device disconnection can prevent restoring a device property; after reconnecting use `adb shell setprop log.tag.AoedeAudio INFO` to turn the tag off. The app independently stops recording after ten minutes or 600 records; the collector accepts at most 600 app records plus periodic focus summaries.

Capture includes:

- Native wall and monotonic timestamps; JavaScript wall/monotonic timestamps and probe response delay.
- Activity/WebView/page/renderer/service boundaries, island sampling generations, wake-lock status and noisy events.
- Actual media-element position/readiness/network state, paused/ended/muted/volume/rate/error code and decoded-byte count when WebView exposes it.
- Island and optional playback AudioContext states, per-context IDs and observed old-context states, close/resume errors, live copied tracks and playback gain settings.
- Android's global music-active/mode/music-volume/mute state, global playback content/usage categories and available output **types**. These are not an authoritative selection or health check of the A2DP route.
- Sanitized five-second focus-stack summaries from `dumpsys audio`, labeling owners only as Aoede/Vitreo/LibrePods/other. Unsupported/denied dump formats can yield no entries; short transient focus changes between samples can be missed.

It excludes URLs, tokens/cookies, source strings/hashes, metadata, song titles, account names, exception text, Bluetooth names/addresses/serials and unrestricted system dumps. The JavaScript/native bridge and the collector both filter fields. Source changes use a local increasing resource number. The tag is off by default; no microphone, recording, Bluetooth-control or accessibility permission is added. Reading diagnostics does not resume a suspended context or change playback.

At the instant sound disappears, leave the state untouched for at least ten seconds to capture clocks/context/platform changes. Record the case/cycle and approximate time separately. A second listener confirming actual audible output remains necessary: these software fields cannot prove that sound is physically produced.

## Physical-device matrix: pending

**No row below has been run on hardware in this environment.** Do not replace these pending results with the automated tests below.

Use the same device, backend/source and AirPods, stable volume, and unchanged ReplayGain/crossfade, conversational-awareness, ear-detection and ownership settings. Keep ear placement and other paired devices consistent. Set Reduce effects before beginning each playback session. For LibrePods-stopped rows, stop it manually before the session and verify it remains stopped; this is an experimental condition, not a recovery action.

For each lock-screen mode (native keyguard / Vitreo custom), run all eight combinations:

| Island | Reduce effects before playback | LibrePods | Native keyguard | Vitreo custom |
|---|---|---|---|---|
| Off | Off | Active | Pending | Pending |
| Off | Off | Stopped | Pending | Pending |
| Off | On | Active | Pending | Pending |
| Off | On | Stopped | Pending | Pending |
| On | Off | Active | Pending | Pending |
| On | Off | Stopped | Pending | Pending |
| On | On | Active | Pending | Pending |
| On | On | Stopped | Pending | Pending |

For each cell, start a fresh labeled capture, return to Vitreo Home and perform at least ten lock/unlock cycles, leaving the screen locked for approximately twenty seconds in some cycles and using rapid lock/unlock in others. Repeat any failing condition and its nearest one-variable control for twenty cycles. Test an actual song transition while locked and while the custom lock screen is visible; expand/collapse the island and open Aoede from it between lock cycles. Check controls and actual sound separately.

Repeat the baseline with another application in foreground before locking. This is a control for the user's observation, not evidence that another foreground application prevents the defect. Compare one factor at a time, not an update of all components at once. If a failure occurs, do not reconnect, restart or toggle playback until the unchanged failing state has been captured.

## Checks executed and practical limits

- JavaScript regressions: delayed `close()`/`resume()`, deliberately reordered native generations, stale stop/start rejection, independent cleanup failures, source changes, no pause/play recovery, typed privacy filtering, the 600-event cap, and read-only distinction between audible/island contexts.
- Android/Robolectric: 77 tests, including privacy/opt-in/600-record/ten-minute limits and repeated lock/unlock plus expansion/collapse with Reduce effects on/off. Existing lifecycle, retained-WebView, noisy-disconnection, service and media-control tests remain included. These simulated tests do not decode Bluetooth audio or run Vitreo/LibrePods.
- Real desktop Chromium: a generated WAV's media clock advanced during 30 analyser stop/start/reordered-request cycles and a source change; no media errors or recovery play/pause commands were observed. This checks real Web Audio API behavior, **not Android focus, actual audible output or the AirPods route**.
- Android release lint/build and the repository's frontend/style checks are run for this candidate; CI provides the complete server/frontend checks.
- Capture helper: typed sanitization self-checks and a no-device invocation; the latter exits without starting/stopping an application or creating a capture. Actual ADB capture, platform focus correlation and the physical matrix remain pending.

Runnable repository checks:

```sh
node --experimental-vm-modules scripts/test_android.mjs
node android/test_frontend.mjs
python3 android/capture_audio.py --self-test
cd android
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease
```

A mocked ADB run additionally exercised partial log lines, timed exit, redaction, installed-version/provider parsing and restoration of the prior log tag. This is a collector integration check, not a connected-device result.

The next decisive evidence is a timestamped failing-device capture with verified installed versions, compared against the closest one-variable control. Until that exists, the code defects above are fixed, while the original silent-output incident remains **unresolved**.
