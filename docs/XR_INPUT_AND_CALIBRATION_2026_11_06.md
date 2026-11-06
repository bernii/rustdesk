# XR input and calibration follow-up — 6 November 2026

User feedback: worn calibration/refinement in RustDesk took approximately 30–60 s,
while Mac XR usually took a few seconds. This is subjective timing, not a captured
IMU trace. The phone was unavailable during implementation; deployment was completed
subsequently on 6 November 2026 as recorded below.

## Calibration comparison and fix

Mac's `Sources/XRCore/AdaptiveTracking.swift` keeps trying roughly 2-second quiet
and 3-second gravity fits. Its saved-baseline check uses 1.25/2.25-second windows.
Android shares those estimator/holdout concepts, but its worn-recovery extension
previously **replaced** the 3-second fit with 12/18-second fits after eight seconds.
Two disjoint long intervals naturally take tens of seconds, even if motion later
settles. Mac and RustDesk also have separate private calibration stores: the newly
installed RustDesk cannot reuse the Mac/probe app's learned device profile. Saved
profile/temperature coverage can explain additional differences, but we have no
trace identifying the cause of this particular on-head report.

The Android change retains the 3-second attempt after eight seconds **only when
heading standard error is at most 0.0002 rad/s**. Long fits remain the fallback.
Two independent agreeing intervals, temperature, total uncertainty, sustained-turn,
trusted-bias replacement and stationary recovery gates remain unchanged. The
existing transition that clears a candidate when entering extended validation
also remains unchanged. Fit cadence stays bounded to once per second there.

A synthetic regression includes 10 s of sustained yaw, then gentle tilt with the
existing simulated accelerometer noise. The previous code had not calibrated at
19 s. The new code confirms at **16 s total / 6 s after settling**, with a short
independent holdout. This is a replay result, not a promise of six-second on-head
calibration. Existing reversible-sway tests still require 25/25/37 s for the 2/4/6 s
sway periods, with bias errors 0.000005645 / 0.000007620 / 0.000014218 rad/s.
Their original duration and accuracy assertions remain intact.

Rejected experiment: simply restoring all short fits and retaining the old candidate
at the eight-second transition could accept a mixed short/long pair at 19 s with
0.0001752 rad/s error, failing the existing 0.00015 limit. A gate on *total* fit
uncertainty also blocked useful tilted-head recovery. The final change gates the
new shortcut on heading uncertainty and preserves the original transition and
long-sway protections. No existing acceptance tolerance was loosened.

Canonical changes are in the probe's `AdaptiveHeadPose.java` and
`GravityCalibration.java`; `WornCalibrationTest.java` reproduces the settled-motion
case. The portable export was regenerated/checked and copied into RustDesk with
only the package changed. Mac source was inspected, not changed.

## Cursor and gestures

- XR activation sends the source-center position to the remote host and updates the
  local cursor model. It waits for usable display metadata/input permission and
  repeats for a source-monitor change. Ordinary metadata updates do not recenter.
- Both 450 ms hold-to-drag and tap-then-move dragging are removed from the XR pad.
  Taps still produce immediate down/up click pairs; double-clicks remain possible.
- Subsequent requested gesture: move three fingers together to hold the remote
  left button and drag with the existing pointer acceleration/sensitivity. The
  gesture starts after 5 dp of centroid travel; placing fingers alone does not
  click. Lifting any finger drops immediately. Remaining fingers cannot click or
  scroll until the touch sequence ends. Cancellation/teardown releases the button;
  entering three-finger mode clears pending right-click and scroll momentum.
  One-finger hold and tap-then-move dragging remain disabled.
- Touch-glass's smoothed pointer speed (`0.6 previous + 0.4 current`) now drives its
  existing 0.25–3× smooth gain curve, then RustDesk's saved sensitivity multiplier.
- The follow-up sensitivity bar is prominently placed beneath the phone's source
  information, labeled “Trackpad sensitivity” with a live 0.5–8× value. Hold the
  lock to unlock it; changes use the existing persisted sensitivity preference.
- Two-finger scroll momentum uses display-scheduled ticks, the reference's ±2500 dp/s
  component cap, `exp(-6 dt)` decay, 50 ms step cap and 15 dp/s stopping threshold.
  A new touch, cancellation, permission loss or teardown stops it. The reference
  supplies inertia for scrolling; it does not fling the pointer after finger-up.

Reference: `mac-xr/touch-glass-ui/.../TrackpadGesture.kt` and `GlassView.kt`.
This is that project's tuned approximation, not Apple's proprietary transfer function.

## Dictation contract confirmed by the user

1. Two two-finger taps within 300 ms start dictation, suppressing right-click.
2. Recognized phrases accumulate in a **local preview**. Natural speech-service
   utterance boundaries restart listening while the mode is active. No text is
   sent remotely yet. Unsupported-language/audio/service errors stop recognition
   and retain the preview; they do not restart endlessly.
3. The next two-finger touch stops recognition. On release, the text is sent once.
4. While that third touch stays down, pinch inward to send the text followed by
   **Enter**, or pinch outward to discard the entire current preview. A deliberate
   span change must exceed both 10 dp and 20% of the starting span. The decision
   happens once and cannot also scroll, click or fire again on release.
5. Sending waits for the final recognition response if it arrives after the gesture.
   Discard/cancel invalidates late events. Per-dictation generations also prevent
   old native callbacks from modifying a newer preview. Stopping cancels any pending
   microphone-permission start. The toolbar Stop action sends without Enter.
6. Acceptance or discard immediately hides the preview, including while an accepted
   utterance waits for its final recognition response. Late final text remains
   available for sending but does not redisplay the preview. Sending clears the
   retained text; the same dismissal applies to release-to-send and toolbar Stop.

The preview is now a bounded card around 60% of the output height, capped at four
visible lines with the newest text retained. Its scale fits the output pixels,
with a gesture hint beneath it. The central microphone graphic is suppressed while
that card is active. Pinch-out never deletes unrelated text on the remote computer;
it discards text that has not been sent. Existing local focus/click/key/lifecycle
cancellation remains in place. A focus change performed independently on the remote
host cannot be detected reliably; sent text targets its focused app.

## Validation and deployment

- 122 probe JVM tests passed, including the new before/after regression.
- 72 extracted portable tests passed; standalone renamed-package compilation,
  API-26 dex, canonical export and destination-copy equality checks passed.
- 43 focused Flutter XR/input/cursor tests passed; XR Dart analysis is clean.
- ARM64 debug APK build passes. Android lint has no XR errors and the same five
  errors in untouched Kotlin files documented in the main integration record.
- API-33 ARM64 emulator (SwiftShader): 18 default GPU projection cases / 73,204
  sampled pixels and two dictation overlay layouts passed. The 1920×1200 and
  2560×1080 previews were also visually inspected after fixing word wrapping.
  This does not test real microphone recognition,
  optical visibility, worn calibration, USB network lifecycle or remote Enter.
- Deployed to Pixel 9 over wireless ADB using `install -r`, preserving app data.
  Installation returned Success; MainActivity cold launch returned Status: ok
  (2,609 ms). The installed APK checksum matches the artifact below. This does
  not yet validate worn calibration, speech recognition or remote input.

Prepared APK: `build/rustdesk-xr-arm64-input-calibration-debug.apk`.
SHA-256: `9448685392ab43dfaaca9ea87ebfe249b8f5ff4ee320bd34d39d5f6881eba111`.
Compact evidence: [validation record](validation/xr-input-2026-11-06.txt).
Local build logs and preview images are retained under
`build/xr-validation/20261106-input/` (ignored build artifacts).
The previously documented optional sharp-edge/checker stress limitations remain;
the default geometry-suite pass does not supersede them.

## Regression surface

Three-finger drag follow-up (6 November): changes are confined to
`xr_gestures.dart` (explicit left-button hold/release and gesture arbitration),
`xr_remote_view.dart` (gesture help), and two regression tests in
`xr_gestures_test.dart`. This uses the existing remote mouse path and does not
change native tracking/rendering or other platform input. XR analysis is clean;
14 focused gesture/dictation/control tests pass. No new APK deployment or physical
remote-drag measurement is claimed for this source change.

Subsequent slider/preview update (6 November): analysis clean; 12 existing focused
XR controls/gestures/dictation tests passed, including immediate preview dismissal
assertions for either final-result ordering. ARM64 build passed and was installed
on Pixel 9 with data preserved; activity launch returned Status: ok (existing top
instance). Installed SHA-256 matches
`6ee83e535f97c8baa5b9fc328b27857a36ddda094953f81bb66e9717705506bc` for
`build/rustdesk-xr-arm64-sensitivity-preview-debug.apk`.
Logs: `build/xr-validation/20261106-sensitivity-preview/`.
This incremental change touches only `xr_remote_view.dart` (slider placement/style),
`xr_dictation.dart` (hide decided preview while retaining pending send payload),
and `xr_controller.dart` (publish preview visibility immediately), plus the
dictation regression assertions. Tracking, native rendering and sensitivity
storage/range remain unchanged. Live gesture/speech validation remains pending.

- Probe `AdaptiveHeadPose.java` / `GravityCalibration.java`: short-fit scheduling
  and heading uncertainty metadata, necessary to avoid unnecessary long holds.
  Generated `portable/Xreal3Dof.java` and RustDesk's copy carry that exact change.
- Dart `xr_controller.dart`: one-time remote cursor centering, preview-only dictation,
  explicit send/Enter/discard and native event generations. Relative input coordinates
  and monitor origin behavior remain in the existing path.
- Dart `xr_gestures.dart`: removes drag recognition, smooths pointer speed, arbitrates
  the third two-finger touch and pinch actions. `xr_remote_view.dart` removes the hold
  timer, schedules scroll inertia with a ticker and updates help/Stop state.
- New `xr_dictation.dart`: local preview/decision state, independent of Android speech
  callbacks, so either final-result/gesture ordering sends once.
- Android `DictationController.java` / `XrController.java`: explicit continuous mode,
  final-result lifecycle, cancellation and generation-tagged speech events.
- Android `XrOverlay.java`: relocates/bounds the transcript and adds the gesture hint.
- Existing XR tests and instrumentation extend only those requested behaviors.
  No Rust, protocol, decoder, projection shader, ordinary mobile gesture path,
  external-display discovery or Mac runtime changes.
