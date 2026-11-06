# rustdesk-xr Android integration

**Follow-up deployed to Pixel 9 (6 November 2026):** [calibration, cursor and dictation changes](XR_INPUT_AND_CALIBRATION_2026_11_06.md). This supersedes the original drag and immediate-final-text dictation behavior below.

## 6 November 2026 upstream refresh

The XR integration is committed and rebased onto upstream master
`9382ab2607d3262e17492f004664ec47f5db1f9f`. The original commit is preserved by
`backup/xr-before-rebase-20261106`. The rebase required no conflict resolution or
XR runtime changes. The submodule follows upstream at
`fae47e024dd83cef6aaecde0aa7d11731df11649` (trusted-device configuration and UDP
send recovery changes).

The restored generated bridge and ARM64 Rust library come from upstream master
`fdffc37bb067d14389350937effa3269b52b656d`, CI run `42147569859`. Its application
source is identical to the new base: the intervening commit only changes
`.github/workflows/flutter-build.yml`. Artifact provenance:

- [Generated bridge](https://nightly.link/rustdesk/rustdesk/actions/artifacts/13471667176.zip),
  SHA-256 `fe651af8704fefa9b0f2fc68e2722ca61acb28f118a05e90f06484779baea616`.
- [ARM64 Rust library](https://nightly.link/rustdesk/rustdesk/actions/artifacts/13473200051.zip),
  SHA-256 `db6a8828cb71b280caaaf4645852255385e6f48cc4757aba4c13a1e45645ff59`.

Flutter 3.24.5 was restored at `/private/tmp/rustdesk-xr-tools/flutter`, including
the dropdown-menu patch used by Android CI. This refresh uses the installed JDK
21.0.13 at `/Users/berni/.gradle/jdks/jdk-21.0.13+9/Contents/Home`, Rust 1.99.0 for
Cargo metadata, and the existing Android SDK/NDK/CMake versions below. Dependency
resolution leaves the tracked lockfile unchanged. XR analysis passes, and all
37 focused XR/input/cursor tests pass. Logs are in `build/xr-validation/20261106/`.

The ARM64 debug APK built successfully and was installed with `adb install -r`
over USB on the Pixel 9, preserving app data. Cold launch completed in 2.35 seconds;
the process remained running and the sampled launch log contained no fatal,
Flutter, or native-linker errors. Installed package: `dev.rustdesk.xr`, version
1.5.1, version code 2068. This verifies startup, not an end-to-end remote XR session.
The deliverable is `build/rustdesk-xr-arm64-debug.apk`, SHA-256
`f3630a6156ef2ecd8372884650b2572eea4e99bca0c97383307439170d58fedb`.

Refresh regression surface: no XR runtime code changed during the rebase.
`flutter/android/.gitignore` additionally excludes the Kotlin build cache generated
by this build; this has no runtime effect. The existing integration hooks and their
necessity are listed in the regression-surface section below. Android lint was not
rerun for this refresh; the original lint findings below are historical.

## 6 November 2026 live 3DoF integration

This supersedes the manual-only tracking status in the refresh above. The current
viewer starts in **3DoF** when the first remote frame opens XR, or when the glasses
are connected during an existing session. XR must be enabled; explicit Exit XR or
manual viewer close still suppresses automatic reopening.

Use XREAL 1S **Follow**, **Stabilizer off**, **TCP/IP enabled**. The phone keeps its
normal Internet route; the copied tracker binds only its own sockets to the glasses'
USB Ethernet network. Tracking and worn calibration start automatically. The glasses
show the probe's projected amber learning ring, teal refining state, or gray missing
IMU state. The card disappears after fresh, confirmed calibration. Saved bias is
reconfirmed on reconnect. This app has its own private profiles; the probe's learned
calibration is not transferred.

The 4K remote desktop remains a cached GPU texture. The new GLES2 ray/plane shader
renders into the output-sized surface (for example 1920×1200). Centered distance 1
is 1:1; turn your head to reveal the rest of the desktop. FOV defaults to 45° and
prediction is off. This does not negotiate a 4K physical signal. Both actual output
and buffer dimensions remain visible on the phone. New remote frames retain the
existing synchronous BGRA mailbox copy/upload; there is no CPU reprojection or
per-frame bitmap conversion. Head motion redraws even when remote content is idle.
Cursor position, hotspot and shape use the same projection as the remote pixels.

Unlock the phone controls with the existing two-second hold:

- **Recenter** changes the viewing reference, retaining calibration.
- **Recheck** keeps useful tracking/bias while validating independent intervals.
- **Desk calibration** first asks you to place the glasses on a stable surface,
  then runs the probe's explicit two-interval stale-bias recovery. It never runs
  automatically as a substitute for ordinary worn calibration.
- **3DoF: on/off** returns to the existing fit, manual 1:1 pan and full-source-buffer
  controls. These flat comparison settings do not resize the 3DoF render surface.
- **Clear text** remains available on the phone. The flat mode's head-locked Clear
  target is hidden in 3DoF so it cannot consume remote clicks under a different mapping.

Transport stops on viewer pause/close, session end, backgrounding and display
removal. The controller retains tracker ownership across GL surface recreation.
The renderer freezes the last good pose on stale/missing IMU and shows the missing
motion-data card; it never extrapolates old data. A shader/upload failure reports
an error and returns to the ordinary phone view.

Source: `flutter/android/app/src/main/java/com/carriez/flutter_hbb/xr/Xreal3Dof.java`
is the portable probe export with **only its package declaration changed**. Preserve
its embedded notices. To update it, regenerate/check the canonical probe export,
copy it again with the destination package and run the portable verification and
fork tests. Do not edit the copied bundled internals independently. The destination
build now compiles app Java/Kotlin at JVM 17 for records/text blocks; minSdk remains
22 and the external XR activity remains gated to API 29+.

The [Android/XREAL handoff](ANDROID_XREAL_3DOF_PORTING_GUIDE.md) carries the estimator,
calibration and projection contracts. Keep it synchronized with the canonical
workspace guide when updating the integration.

Validation (6 November 2026):

- ARM64 debug app and test APK builds pass; native compilation retains `-Wall -Wextra -Werror`.
- Focused Flutter XR/input/cursor tests: **38 pass**. XR Dart analysis: no issues.
- Canonical export check, standalone compile/API-26 dex, and **71 portable tests pass**.
  The destination copy differs only in its package declaration. The facade now
  explicitly annotates its pre-existing API-26 runtime requirement.
- Android 13 ARM64 emulator / SwiftShader: **18 GPU cases, 73,204 pixel comparisons
  pass**, covering BGRA, centered source coordinates, yaw/pitch/roll/mixed poses,
  looking away/plane edges, 4K and smaller sources, HUD phases and cursor composition.
  The geometry suite uses continuous ramps and a two-channel-value tolerance.
- **Strict filtering stress checks still fail on this emulator.** `-e sharp true`
  reproduces a rotated wrap-edge channel mismatch (expected 31, actual 34 at
  output 609,7). Explicit highp samplers made no difference and were reverted.
  `-e checker true` reproduces centered one-pixel-checker filtering (expected 0,
  actual 8 at 3,3). Crucially, the **unchanged native flat shader also produces 8**
  for the same checker. These are separate retained stress tests, not waived passes;
  the exact optical/physical-GPU behavior remains unverified. Tolerances were not
  widened to conceal the failures.
- Android lint: **zero XR errors**, five errors in untouched `AudioRecordHandle.kt`,
  `DocumentLogs.kt`, `MainService.kt` (two), and `RdClipboardManager.kt`.
- Emulator install/cold launch succeeds (3.110 s), process remains running and
  the crash buffer is empty. This is not a real remote-session or glasses test.

The deliverable is `build/rustdesk-xr-arm64-3dof-debug.apk`, SHA-256
`8436dac7a54cf94800fc93e5de5e64923426a0c36167ed3ffdd8c3d663d56188`.
Compact evidence is in [the validation record](validation/xr-3dof-2026-11-06.txt);
full local build/test/lint logs are in ignored `build/xr-validation/20261106-3dof/`.
Reproduce GPU checks with the Gradle build setup below, adding
`:app:assembleDebugAndroidTest`, then install app and test APKs and run:

```sh
adb -s DEVICE shell am instrument -w \
  dev.rustdesk.xr.test/com.carriez.flutter_hbb.xr.XrProjectionInstrumentation
# Retained strict stress checks, separately:
adb -s DEVICE shell am instrument -w -e sharp true \
  dev.rustdesk.xr.test/com.carriez.flutter_hbb.xr.XrProjectionInstrumentation
adb -s DEVICE shell am instrument -w -e checker true \
  dev.rustdesk.xr.test/com.carriez.flutter_hbb.xr.XrProjectionInstrumentation
```

Inspect the runner's PASS/FAIL text; shell status alone is insufficient.
No physical phone/glasses was attached during this work. Optical quality, live
calibration, stationary/on-head drift, reconnect/endurance and 4K decoder load
remain device acceptance work. The probe's previous physical measurements do not
validate this new RustDesk content/lifecycle path. The renderer uses coalesced
Choreographer redraw requests, not the probe's EGL presentation-feedback scheduler;
no motion-to-photon or performance improvement is claimed.

Follow-up deployment, 6 November 2026: installed the 3DoF APK above on the
user's Pixel 9 over Wi-Fi debugging with `adb install -r`, preserving app data.
Package verification reports version 1.5.1 / code 2068; cold launch returned OK
in 3.363 s and the app process remained running. This confirms installation and
startup only; no remote-session, glasses calibration or optical measurement was
performed in this deployment step.

Regression surface for this change:

- `XrViewerActivity.java`: default 3DoF branch, output-sized buffer, pose redraws,
  tracker lifecycle and status ticks are necessary for head tracking with idle video.
- `XrController.java`: owns tracker, stops transport on XR/session/display teardown,
  routes three explicit controls and a separate 2 Hz tracking event. Tracking events
  do not resend session/cursor state or reconfigure video.
- `XrRenderer.java` / `cpp/xr/renderer.cpp`: additive JNI upload accessor exposes the
  existing owned texture to the Java GL projection pass. The old flat draw function
  is unchanged; its small upload block is deliberately duplicated for this new path.
- `XrOverlay.java`: skips the flat cursor and Clear target in 3DoF because the cursor
  is now projected on the GPU. Lock/dictation overlays retain their existing behavior.
- Dart `xr_controller.dart` / `xr_remote_view.dart`: 3DoF default, controls/status,
  flat comparison visibility, and bypass of flat Clear hit testing in 3DoF. Relative
  remote input remains in source pixels; the source monitor origin is unchanged.
- Android app/root Gradle files: app Java/Kotlin 17 compatibility and the dependency-free
  GPU instrumentation runner. Plugin JVM targets and ordinary Android minSdk stay unchanged.
- New tracker, projection renderer and tests are XR-specific. No Rust, decoder,
  protocol, generated bridge, submodule, or ordinary mobile video path changes.

The following original implementation sections are **historical** unless explicitly
updated above; their manual-only tracking limitations and full-resolution-buffer
default describe the old flat renderer, not the new 3DoF default.

## Original implementation checkpoint

Implementation checkpoint: 23 October 2026, based on upstream
`828d1101afd2e656eb1c1d18511009bdf5336238` and [the probe handoff](../../rustdesk-xr-probe/docs/XR_MODE_HANDOFF.md).
This is the real Flutter RustDesk client, with its existing Rust connection and decoder.
Changes are local in this checkout; `origin` still points upstream. No hosted fork,
PR or release has been published.
The standalone probe and its design assets remain intact.

## Using this build

The launcher label is **rustdesk-xr**, Android package **dev.rustdesk.xr**. It can coexist
with official RustDesk and the probe. Its connection history/settings are separate;
enter the remote host in this app using the usual RustDesk connection flow.

1. Unlock the phone, attach the glasses, and select the accepted **21:9** output mode
   using the glasses/Android controls.
2. Connect to the remote computer. After the first remote frame, the app waits one
   second for Android's desktop startup, then opens an external viewer. The phone
   switches to the touchpad when the viewer's renderer is ready.
3. Controls start locked. Hold the top-right lock still for two seconds to unlock.
   Early release, movement, cancellation or another finger cancels the hold.
4. The phone retains RustDesk's toolbar and keyboard. **1:1 / pan** switches from
   whole-desktop fit to unscaled source pixels; the horizontal/vertical sliders
   select the crop without changing the host's resolution.
   **Full-res buffer: on** is the new default in fit mode: the GL surface uses the
   remote source dimensions (3840×2160 for a 4K host), reproducing the probe's fixed
   buffer experiment. Its view is letterboxed to preserve the source aspect ratio.
   Toggle it off to compare with the original output-sized GL surface. 1:1/pan
   always uses the output-sized surface; enabling the full-res buffer returns to fit.
   The phone shows **source**, **buffer**, and **output** dimensions separately.
   Android can still downscale this buffer to its physical output mode; this option
   does not itself negotiate a 4K DisplayPort signal or prove improved optical detail.
5. **Exit XR** disables automatic XR opening persistently. Re-enable it from the
   ordinary session toolbar's **More → Enable XR mode**. **Show in external display**
   retries a manually closed viewer. The ordinary mobile view remains available
   without a supported external display.

XR currently requires Android 10/API 29 or later for the concurrent activity setup.
The existing application's minimum SDK remains 22. With multiple external displays,
the first Android presentation-eligible display is selected. Selection of another
external display is not implemented.

## Implemented behavior

- A separate, non-exported Android activity owns the external GLSurfaceView. It
  requests wake/fullscreen, stays out of the IME target, and keeps the screen on.
  A manual close pauses automatic reopening; display removal restores mobile mode.
- One RustDesk session, one decoder. The decoded **BGRA** buffer is borrowed through
  the existing `session_get_rgba` symbol and copied synchronously into a native
  latest-frame mailbox before `sessionNextRgba` releases it. No video passes through
  a MethodChannel. The GL thread uploads that copy and swaps red/blue in its shader.
- While XR accepts frames, the phone skips its normal image decode/upload. Existing
  first-frame/reconnect notification handling still runs. XR off uses the original
  mobile pixel path. No Rust, protocol, FFI generator, or submodule changes were needed.
- The mailbox holds at most one pending and one upload buffer; a newer pending frame
  replaces an older one. There is no queue that grows when rendering is slower than
  incoming video. This is a copy-and-upload prototype, **not zero-copy video**.
- Source dimensions come from the current remote display metadata and are checked
  against the received byte count. Physical output and GL surface dimensions are
  reported separately. Fit/1:1 are local viewport operations.
- Cursor positions include the remote monitor origin. Remote cursor shape and
  hotspot are displayed separately unless the cursor is embedded in the video.
- Relative one-finger motion uses the probe's acceleration curve once, followed by
  persistent 0.5×–8× sensitivity (default 3×). Tap/double-tap, hold/tap-then-move drag,
  deferred secondary tap, two-finger scrolling and momentum are implemented.
- Two-finger double-tap toggles dictation and suppresses the secondary click. Pinch
  is distinguished from scrolling but has no assigned action yet; three/four-finger
  shortcuts are also intentionally unassigned.
- Locked app-surface touches feed the trackpad, including touches over controls.
  Both displays show the unlock ring and a brief open-lock result. The two-second
  hold remains two seconds even with system animations disabled.
- SpeechRecognizer prefers on-device recognition when available. Partial hypotheses
  replace the local preview. A final utterance is sent once through RustDesk's
  existing text input path, subject to view-only, keyboard-permission and keyboard
  confirmation gates. Clear cancels recognition and clears only the local draft.
  It does not delete text already committed remotely. The external Clear button is
  placed inside the reachable viewport so it also works with the phone's pointer.
- Local remote clicks, physical keys, opening keyboard/toolbar controls, session
  errors, backgrounding and teardown cancel pending speech. Held pointer buttons
  are released and momentum stops on cancellation/teardown. No transcripts or audio
  are written to diagnostics.

## Build setup used here

- Flutter **3.24.5**, Dart **3.5.4** (the repository's Android CI version).
- JDK **17.0.21**, Gradle **8.11.1**, existing AGP **8.10.1**.
- Android SDK platform **36**, NDK **28.2.13676358 (r28c)**, CMake **3.22.1**.
- Rust **1.99.0** for Cargo metadata. The machine's default 2023 nightly cannot
  resolve the current dependencies. Select a modern installed toolchain explicitly.
- The native Rust library and generated bridge were restored from CI for the exact
  baseline commit above. **No bridge code generation was run.** The app's Flutter,
  Java/Kotlin and XR C++ code were built locally. A full local Rust cross-build is
  not claimed.

Exact baseline CI artifacts (public artifact mirror; CI artifacts can expire):

- [Generated bridge](https://nightly.link/rustdesk/rustdesk/actions/artifacts/12421251135.zip),
  SHA-256 `613b3cb95e71329ca22ae2ea88c5599be10712185ba749e99b209683d76ce4c3`.
- [ARM64 Rust library](https://nightly.link/rustdesk/rustdesk/actions/artifacts/12422345367.zip),
  SHA-256 `76c68b0bc1d869e55ba0c2e5f2a191e1c7039593ccbd6df8d62c9ac377e2f0a5`.

Extract the bridge's `flutter/lib/*` and `src/*` files into the checkout. Copy
`liblibrustdesk.so` from the ARM64 artifact to
`flutter/android/app/src/main/jniLibs/arm64-v8a/librustdesk.so`. Copy
`libc++_shared.so` from the NDK's
`toolchains/llvm/prebuilt/<host>/sysroot/usr/lib/aarch64-linux-android/` alongside it.
These generated files and native libraries are gitignored. Do not reuse this core
artifact after changing Rust code or switching to an incompatible bridge revision.

Local tool paths used for this checkpoint (temporary paths may disappear):

```sh
export FLUTTER_ROOT=/private/tmp/rustdesk-xr-tools/flutter
export JAVA_HOME=/private/tmp/rustdesk-xr-probe-jdk17/Contents/Home
export ANDROID_HOME=/Users/berni/Library/Android/sdk
export RUSTUP_TOOLCHAIN=1.99.0
export PATH="$FLUTTER_ROOT/bin:$JAVA_HOME/bin:$PATH"
cd flutter
flutter pub get
flutter analyze --no-pub lib/mobile/xr
flutter test --no-pub test/xr_gestures_test.dart test/xr_lock_test.dart \
  test/input_modifier_utils_test.dart test/cursor_replay_test.dart \
  test/cursor_size_test.dart test/cursor_default_test.dart
cd android
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.jvmargs=-Xmx4g \
  --max-workers=2 --no-daemon :app:assembleDebug :app:lintDebug \
  -Ptarget-platform=android-arm64 -Psplit-per-abi=true
```

The ignored `android/local.properties` must name `sdk.dir` and `flutter.sdk`. Flutter
creates the ignored Gradle wrapper on its first Android build; it is also bundled
under `$FLUTTER_ROOT/bin/cache/artifacts/gradle_wrapper/`. The first build downloads
Cargo metadata, Flutter plugins and Gradle dependencies. Flutter 3.24.5 resolves some
SDK-bound package versions differently from the checked-in lockfile; that local
resolution is not part of the XR source change. Android Studio's bundled Java 25 was
incompatible with this Gradle, so the successful invocation selected JDK 17 explicitly.
The default 1 GB Gradle heap was too small for Jetifier's debug engine processing.

The APK is `flutter/build/app/outputs/apk/debug/app-arm64-v8a-debug.apk`. It is a **debug ARM64
validation build**, not a signed production release or a performance benchmark.

```sh
adb -P 5038 devices -l
adb -P 5038 -s DEVICE install -r flutter/build/app/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -P 5038 -s DEVICE shell am start -W -n dev.rustdesk.xr/com.carriez.flutter_hbb.MainActivity
```

## Verification and remaining acceptance work

- XR Dart analysis: no issues.
- Initial integration: focused XR gestures/viewport tests plus existing input/cursor
  tests: **36 passed**. Full-source buffer update: **6 XR tests passed**, including
  a new widget test of the buffer toggle and return from 1:1/pan to full-source fit.
- C++ ARM64 compilation with `-Wall -Wextra -Werror`: passed.
- Android SDK Java compilation of the new XR classes: passed.
- Full debug APK build: passed. Packaged ARM64 Rust, Flutter and XR libraries checked.
- Android lint: **0 errors in new XR code**; the full task fails on **4 pre-existing
  errors** in untouched `AudioRecordHandle.kt`, `MainService.kt` (two calls), and
  `RdClipboardManager.kt`. Those unrelated issues were not changed or suppressed.
- The lock widget tests confirm the hold cannot complete early with reduced motion
  enabled, and that early release/movement keep the controls locked. Completion is
  observed on the first rendered frame after the two-second threshold.
- Installed successfully on the Pixel alongside official RustDesk and the probe.
  Cold launch completed successfully (about 3.1 seconds), the app process remained
  running, and the sampled app log contained no fatal, Flutter or native-linker
  errors. Installed version: 1.5.1, ARM64 split version code 2068. This is a startup
  smoke check, not remote-session validation.
- Deliverable: `build/rustdesk-xr-arm64-debug.apk` (SHA-256
  `a18aaec9e9b9d81a0677cbe44d3e3784db39df7cf829c1d0f75b3538364af8c5`).
  Build/test/lint logs are retained in the ignored `build/xr-validation/` directory.

The user confirmed a working remote session, then reported blur with a 3840×2160
source and 1920×1200 output. Live Android diagnostics confirmed the glasses' physical
mode and fullscreen viewer were both 1920×1200. The full-source buffer update was
built and installed at the user's request to repeat the probe's fixed-buffer
experiment without changing the glasses to UltraWide. Optical improvement and
performance of this update still need a real-session comparison.
Live SurfaceFlinger inspection after installation confirmed the running XR video
layer takes a **3840×2160** source crop and places it in **1920×1080** bounds at
`(0,60)` on the **1920×1200** output. Its compositor transform is **0.5× on both
axes**. This verifies the full-source buffer reaches Android, and also verifies
that Android still downsamples it for the current physical display mode. The
diagnostic dump is saved locally as `/private/tmp/xr-full-buffer-surfaces.txt`.

### Sharpness investigation after the fixed-buffer comparison

The user reported that the full-source buffer still looked blurry. Re-reading the
original probe conversation confirmed an earlier positive sharpness observation in
normal mode, before the separate 32:9 crop test. The normal-mode probe had a
1920×1200 window and a 0.5× fit of its synthetic 4K pattern; it did not establish a
3840×2160 physical signal. Its local Canvas pattern also bypassed remote compression.

- A temporary, non-persistent `3840 2160 60` user-preferred-mode request on the
  connected external display was accepted as a preference, but the active mode
  stayed 1920×1200. The original null preference was restored and verified.
- A standalone EGL pbuffer check on the Pixel's Mali-G715 rendered a 3840-pixel-wide
  alternating one-pixel pattern through the XR shader. Both the original mediump
  and highp variants produced zero differing pixels (channel tolerance 1), despite
  reported precision of 10 and 23 bits respectively. No shader change was justified
  by this result. Test source/executable: `/private/tmp/xr-shader-check.c` and
  `/private/tmp/xr-shader-check`; this is not shipped app code or a new test framework.
- The host's saved settings were Balanced, custom quality 50, automatic codec,
  and no 4:4:4 preference. For the next comparison, the existing peer configuration
  was changed to custom quality 100, VP9, and `i444 = 'Y'`; the frame-rate setting
  remains 30. Original configuration is backed up beside the peer TOML on the
  device with suffix `.before-xr-quality`. These are requested settings; actual
  codec/chroma negotiation and subjective sharpness still require reconnection.

This investigation changes no renderer, decoder, or shared runtime code. Its only
runtime change is the saved quality preference for the existing peer on the test
phone. The source-buffer toggle remains available for comparison.

The user subsequently confirmed **sharp only in 1:1/pan**, after the quality
comparison. A further system-level test set the external logical desktop itself
to 3840×2160 (`wm size`, external display only), alongside a temporary physical
3840×2160 / 60 Hz preference. After settling, Android reported:

| Stage | Observed dimensions |
| --- | --- |
| XR video surface and visible layer | 3840×2160, no layer scaling |
| Android logical desktop / layer-stack projection | 3840×2160 |
| Physical display mode | 1920×1200 at approximately 60 Hz |
| Final destination rectangle | `(0,60)–(1920,1140)`, a 1920×1080 image |

Thus forcing the desktop size moves scaling to the final display projection; it
does not remove it or produce a 4K physical signal. Original state was no size
override and a null preferred mode. Diagnostics are in
`/private/tmp/xr-desktop-4k-{display,SurfaceFlinger,window}.txt`. Direct kernel
DisplayPort modes/EDID access was not available to the shell; no root or driver
changes were attempted.
The temporary desktop-size override and mode preference were removed after the
test; restoration to no size override and null preferred mode was verified. This
test introduced no production code changes or lasting display-policy changes.

When back, validate in this order:

1. Ordinary remote connection with XR disabled; keyboard, cursor and a click.
2. Enable XR with glasses in 21:9. Confirm actual remote frames and a working click,
   monitor offsets/cursor hotspot, correct colors, and phone-only Gboard/toolbar.
3. Confirm source **3840×2160**, physical **2560×1080**, fit and all 1:1 crop edges.
4. Lock hold/cancellation/multitouch; two-finger single/double-tap arbitration.
5. Unicode/multiline dictation into a disposable editor, permission denial, Clear,
   stop, cancellation and final-text delivery. Test a terminal only after editor
   behavior is verified. A focus change made independently on the remote computer
   cannot be reliably detected; final text targets the app focused at finalization.
6. Unplug/replug, manual viewer close/reopen, phone background/foreground, monitor
   switch, disconnect/reconnect and teardown during drag/speech.
7. Measure frame pacing, copying/upload cost and thermals in a 30-minute 4K session.

Known limits: no glasses pose/head tracking; no app-only 32:9 workaround; no explicit
XREAL microphone routing; no arbitrary-editor revision of speech partials; no native
Mac pinch/Spaces gestures. If Android declines fullscreen, the viewer reports that
and remains a desktop window that can be maximized manually; the probe's independent
Presentation fallback has not been ported. XR labels are initially English. The
phone layout uses the reference's orange/white palette and outlined lock but does
not reproduce its full visual design; compact landscape layout still needs device QA.

## Regression surface / minimization pass

Full-source buffer update (relative to the first installed XR build):

- `XrViewerActivity.java`: source-sized GL surface in fit mode, aspect-preserving
  centered view with black letterboxing, and separate viewport dimensions. Required
  to submit all source pixels to Android while preserving cursor/overlay placement.
- `xr_controller.dart`: buffer option and viewport dimensions for Clear hit-testing.
  Render-buffer dimensions can no longer stand in for the overlay's window size.
- `xr_remote_view.dart`: buffer toggle and explicit source/buffer/output labels.
- `xr_lock_test.dart`: one user-visible buffer-control regression test.

The output-sized fit and 1:1 rendering paths remain available. No protocol, decoder,
ordinary mobile rendering, physical display preference or probe code changed in this
update. Diff minimization removed no required changes; docs reflect the new build.

Existing files changed, and why:

- `flutter/lib/mobile/pages/remote_page.dart`: Android-only XR controller lifecycle,
  first-frame start, a separate XR page branch, and two entries in the existing More
  menu. The normal mobile widget tree and input handlers remain intact.
- `flutter/lib/models/model.dart`: an optional XR session-event observer releases
  input/speech on errors; an Android-only frame hook copies into the XR renderer and
  acknowledges the frame while retaining normal first-frame notifications. Without
  an active XR destination, the original pixel path runs unchanged.
- `MainActivity.kt`: thin channel, activity-lifecycle and microphone-result hooks.
- Android `AndroidManifest.xml`: separate viewer activity, speech-service visibility
  and fork launcher label; no new microphone permission (it already existed).
- Android `app/build.gradle`: distinct application ID and native XR renderer build.
- Android `app/proguard-rules`: preserve the renderer's JNI entry points.
- Android `.gitignore`: exclude the new native-build intermediates and Gradle reports;
  no runtime behavior changes.

All substantial implementation is in new `flutter/lib/mobile/xr/`, Android
`.../java/com/carriez/flutter_hbb/xr/`, and `.../cpp/xr/` files. No existing Rust paths,
shared structs/traits, protocol messages, generated bridge sources or submodule
revisions changed. Dependency-lock/build-cache changes are excluded from the patch.

The pre-integration probe/docs were additionally archived locally at
`/private/tmp/rustdesk-xr-checkpoint/probe.tar.gz` (SHA-256
`90a2f9ebc9bf5ce7a6acafad561068d8b70113b127eefa7bdcd4dc55308b4519`).
The original untracked probe and handoff assets remain in the working tree; this
archive is a temporary backup, not a hosted Git checkpoint.
