# Android → XREAL: 4K virtual screen and glasses IMU implementation guide

**Living implementation handoff. Last updated: 6 November 2026.**

RustDesk carries a [local copy of the portable module](../flutter/android/app/src/main/java/com/carriez/flutter_hbb/xr/Xreal3Dof.java). Historical probe/Mac evidence links below refer to sibling reference checkouts; carry those references when moving this guide again.

This is the starting document for taking the working `rustdesk-xr-probe` projection and tracking implementation into another Android app. It consolidates the Android experiments, applicable `mac-xr` work, failure cases and measured results. It describes **host-rendered rotational 3DoF using the glasses' own IMU**, without requiring an XREAL SDK or firmware modification.

The reference hardware is **Pixel 9, Android 17 build CP3A.260905.009, XREAL 1S**. The app builds against SDK 36; runtime OS version and compile SDK are different facts. Protocol and display findings are specific to this tested combination until validated elsewhere. The source of truth for exact behavior is the current Java/C++ implementation and linked test evidence. Older dated notes sometimes describe features that were not yet implemented at their checkpoint.

## Copyable implementation: start here

A self-contained [**Xreal3Dof.java**](../../rustdesk-xr-probe/portable/Xreal3Dof.java) is now available. **Copy that one file** into your other Android app and change its package declaration if desired. No Gradle library module, probe assets, native library or helper Java files are required. The [integration README](../../rustdesk-xr-probe/portable/README.md) provides manifest requirements, lifecycle/render-loop examples and the public API.

It includes the glasses IMU transport, parser/clock, adaptive tracking, gravity correction, temperature/factory-assisted private calibration, explicit stale-bias recovery, generalized source-size projection geometry, calibration HUD, and an AGSL bitmap renderer with Canvas fallback. Prediction stays off. Your host app owns the external display/window, Surface, render-thread/vsync scheduling, source bitmap or video pipeline, and input UI. The probe's EGL/native presentation-feedback backend is **not** included in this one-file export; the detailed source map below remains useful for that optional integration.

Public entry points are `Xreal3Dof` (tracking/lifecycle/calibration), `Snapshot` (immutable status/pose), `Projection` (source-to-output geometry), and `Renderer` (draw onto your Canvas). Source bitmap dimensions are parameterized instead of fixed at 3840×2160. Requires minSdk 26, compileSdk 33+ and Java 17 with modern Android record desugaring; verified compilation used SDK 36. A destination app starts with its own private calibration store; copying the file does not copy the probe's saved device profile.

The file is reproducibly generated from current probe sources plus a small facade using [export.py](../../rustdesk-xr-probe/tools/portable/export.py). Maintain the canonical sources/facade here, regenerate, and run `--check`; copied destination files are ordinary standalone source. **72 extracted-code/public-API tests passed**, including non-4K geometry and warm-up/stale-bias regressions. The renamed-package file compiled alone and produced API-26 dex. These extraction checks did not repeat hardware networking/rendering: the measured results below describe the original probe, and the destination app still needs live integration validation.

## 1. What works now—and what “4K” means

The app retains a **3840×2160 source image** on the phone and renders a perspective view of a fixed plane into the glasses' **native output-sized surface**. Head rotation reveals different parts of the source. At the centered reference pose and distance 1.0, the mapping is one source pixel per output pixel. Perspective and other distances resample it.

Keep these quantities separate:

| Quantity | Meaning in the reference implementation |
|---|---|
| Source size | 3840×2160 synthetic bitmap; a new app can supply another size or live content |
| Physical output | Latest verified normal mode: 1920×1200 at about 60 Hz |
| Surface/buffer size | Native physical dimensions in the validated run; must be measured, not assumed |
| Visible viewport | Perspective intersection of the output rays with the larger source plane |
| Glasses' internal panel behavior | Separate from host input refresh; optical scanout/latency remains unmeasured |

This is **not a 3840×2160 DisplayPort signal**, simultaneous full-desktop 4K optical resolution, or positional 6DoF. Leaning/walking does not produce positional parallax. A forced 4K Android buffer can simply be downscaled by the compositor. Onboard Anchor cannot reconstruct pixels the phone never transmitted.

Use glasses **Follow mode, Stabilizer off, TCP/IP enabled** for host tracking. Onboard Anchor plus host compensation can apply tracking twice. Onboard screen-size changes can alter the effective optical FOV and require re-tuning the app's FOV.

Latest evidence:

- Stationary calibration replaced a stale bias and completed in **7.036 s**.
- With 3DoF rendering active, maximum quaternion displacement was **0.0281° over 180.830 s**; endpoint displacement was **0.0106°**.
- Saved bias reconfirmed in **2.203 s** after restarting the IMU connection and re-reading physical identity.
- Median renderer cadence was **60 Hz**; prediction was off; no IMU gaps, invalid reports, duplicates, clock resets or calibration rejections occurred.
- This final run was at **35.95–36.27°C**, with the glasses stationary on the floor. It is not a cold-to-hot or on-head drift measurement.
- After the fixes, the user reported **“seems to be working fine now.”** This is useful subjective feedback, separate from the stationary measurement; no quantified subsequent on-head trace was captured.
- **121 JVM tests passed**, debug/test APKs built and lint completed. This does not erase the historical emulator precision limitation described below.

Evidence: [final per-sample run](../../rustdesk-xr-probe/docs/validation/stationary-recovery-2026-11-06.json), [calibration diagnosis and fixes](../../rustdesk-xr-probe/docs/WORN_CALIBRATION.md).

## 2. Architecture to carry into a new app

```text
Glasses USB Ethernet                         Android external display
    │                                                  ▲
    ├─ TCP 52998: receive IMU                           │ native-sized surface
    │     parser → command/sensor gate                 │
    │     → device-time integration + host clock map   │
    │     → adaptive calibration / gravity correction  │
    │     → immutable latest pose ──────────────┐      │
    │                                          ▼      │
    └─ TCP 52999: bounded config getter     XR render thread
          identity + optional factory table    │
          private host-bias store              ├─ late pose read
          separate IO worker                   ├─ ray/plane shader
                                               └─ EGL or hardware Canvas
4K content producer → owned/cached GPU texture ────────┘
Phone activity → settings, recenter, calibration, input; immutable render settings
```

Keep transport, estimator, source content and presentation independent. The probe's pure Java estimator already supports synthetic chunk replay without Android. Do not bind estimator state to an Activity or reset calibration when a surface is recreated.

A useful integration contract (design guidance, not an existing public SDK):

- `PoseSnapshot`: normalized quaternion, bias-corrected angular velocity, device sample time, mapped host-monotonic sample time, receive time, freshness/readiness, calibration state, optional prediction coefficients, epoch/generation.
- `RenderSettings`: source dimensions, output dimensions, FOV, distance, reference orientation, freeze state, prediction on/off and timing parameters.
- `ContentFrame`: GPU texture/buffer handle, dimensions, format, orientation/crop transform, frame sequence, ownership and synchronization contract.
- `CalibrationStatus`: tracking available separately from bias trusted; collecting/independent holdout/confirmed/stale; progress versus confidence wait; temperature and diagnostic counters.

The network/IMU worker exclusively mutates parser and estimator. Render from an immutable snapshot; do not hold an estimator lock across rendering. Perform factory reads and disk IO on a separate worker. Read the newest pose **after expensive resource preparation and immediately before projection**. New content and new pose are independent: a stationary desktop texture still needs redraws as the head moves.

## 3. Minimal source map

All Android Java classes below are in [the probe package](../../rustdesk-xr-probe/app/src/main/java/dev/rustdesk/xr/probe/). Most are package-private; adapt package boundaries deliberately. The one-file export above is the simplest copy path; this table maps its canonical internals and optional probe-specific components. If integrating individual classes instead, copy their tests and dependencies rather than isolated formulas.

| Responsibility | Reference files |
|---|---|
| TCP routing, lifecycle, published pose | `GlassesTracker.java` |
| Wire parser and sample isolation | `GlassesImuParser.java`, `SampleGate.java`, `ImuStream.java` |
| Quaternion math and adaptive estimator | `ImuMath.java`, `AdaptiveHeadPose.java` |
| Bias proposals / tilt updates | `GravityCalibration.java`, `TrackingRefinement.java`, `GravityTiltCorrection.java` |
| Temperature and host model | `TemperatureFilter.java`, `BiasProfile.java`, `FactoryBiasTable.java` |
| Identity, getter, persistence | `FactoryConfigReply.java`, `FactoryCalibrationSession.java`, `CalibrationStore.java` |
| Device ↔ host time | `SampleClock.java` |
| Projection math / CPU oracle | `AnchoredScreen.java`, `Viewport.java` |
| GPU projection | `PlaneShader.java` (AGSL), `EglPlaneRenderer.java` (OpenGL ES) |
| Surface scheduling / render ownership | `PatternView.java`, `RenderInputs.java`, `FramePacer.java`, `PresentationSchedule.java`, `PresentationCadence.java` |
| EGL timestamp bridge | `EglTiming.java`, [egl_timing.cpp](../../rustdesk-xr-probe/app/src/main/cpp/egl_timing.cpp), its `CMakeLists.txt` |
| Optional prediction | `MotionPrediction.java`, `AnchorPredictionFilter.java`, `QuietAnchorPredictionFilter.java`, `PredictionModel.java`, `PredictionSettings.java`, `PredictionPreferences.java` |
| Projected calibration UI | `CalibrationHud.java` |
| External display/window lifecycle | `ViewerActivity.java`, `ViewerWindow.java`, relevant parts of `MainActivity.java` / `ProbeState.java` |
| Core regression suite | [JVM tests](../../rustdesk-xr-probe/app/src/test/java/dev/rustdesk/xr/probe/) |
| GPU, replay, storage and device tests | [Android tests](../../rustdesk-xr-probe/app/src/androidTest/java/dev/rustdesk/xr/probe/) |

`HeadPose` and `ImuCalibration` are older implementations retained for reference/tests. **The live path uses `ImuStream` → `AdaptiveHeadPose`.** `PatternView` includes probe-specific synthetic content and UI; treat it as a lifecycle reference, not a production content API. Review [third-party notices](../../rustdesk-xr-probe/THIRD_PARTY_NOTICES.md) when copying code/reference material.

## 4. Android transport: use the glasses network, not the default route

The tested USB device IDs are vendor **0x3318**, products **0x043e / 0x043d**. Discover the attached device and Android Ethernet `Network`; inspect `LinkProperties` for a local address on `169.254.1.*` or `169.254.2.*`. The corresponding glasses host is `169.254.1.1` or `169.254.2.1`.

Create each socket with **that Network's socket factory**. Do not change the process/default network: the app still needs Wi-Fi/mobile connectivity for its own content or remote session. A reachable Wi-Fi ADB phone is not proof the glasses' USB network still exists. In our failed verification attempt both the USB device and Ethernet interface were missing; physical reconnection restored them.

- **52998:** receive-only raw IMU stream. No subscription/control writes are required by the implemented path. TCP chunks can split or combine reports.
- **52999:** separate bounded, background factory-configuration getter, not the IMU stream. Failure must fall back to host calibration.
- App permissions for this network path: `INTERNET`, `ACCESS_NETWORK_STATE`. This is not a phone `SensorManager` orientation stream and not a direct USB endpoint reader requiring a new USB permission flow.
- Phone motion is not glasses motion. USB descriptor presence alone is not evidence of valid pose.
- Cancel/close sockets on stop/removal and invalidate published freshness immediately. Use session generations so an old worker cannot publish after a reconnect.
- Reconnect creates a new transport epoch and requires a fresh device identity before saved calibration reuse. Do not reuse display IDs, Ethernet names, ADB endpoints or IP addresses as physical identity.

### Normal IMU framing

The parser accepts a six-byte header followed by a 128-byte body, **134 bytes total**. Known header bytes are `28 36 00 00 00 80` (normal) and `27 36 00 00 00 80` (legacy). Header command/length framing and body endian conventions differ. Offsets below are **from the start of the complete 134-byte frame**, not from the body:

| Frame offset | Encoding | Used meaning |
|---|---|---|
| 14 | uint64 little-endian | Device/system sample nanoseconds used for integration |
| 22 | uint64 little-endian | Additional sensor-clock metadata; not substituted blindly for integration time |
| 30 | uint32 little-endian | Main report mask/type; current parser accepts exactly 11 / `0x0b` |
| 34, 38, 42 | float32 little-endian | Gyro x/y/z, rad/s |
| 46, 50, 54 | float32 little-endian | Acceleration x/y/z, m/s² |
| 70 | float32 little-endian | Normal-report temperature, already °C |
| 74 | byte | Normal-report sensor ID; measured main stream ID was 2 |
| 75 | uint32 little-endian | Normal-report wrapping frame counter |

Port framing resynchronization, finite/plausibility checks and report counters. Do not treat magnetic/other records as gyro samples. Legacy metadata is not presumed identical: legacy mode does not get unverified temperature, frame-counter, factory-reuse or clock-mapping semantics.

The stream has measured approximately **1,000 unique gyro samples/s**. Measure accepted samples, not TCP reads or bytes. Select one command/sensor pair per connection. Reject duplicate/backward timestamps without refreshing pose freshness. Counter wrap is not a clock reset. A large rollback needs corroborating advancing reports/counters before creating a new epoch. A gap over 100 ms preserves orientation but clears time-dependent windows/velocity; never integrate across missing time.

## 5. Coordinate and timing contracts

CPU quaternions are Hamilton **WXYZ**. The AGSL/GLSL uniform is **XYZW**; reorder explicitly. Do not infer an external API's quaternion ordering from a similarly shaped array or a firmware calibration comment.

The current math integrates body-frame corrected gyro by right multiplication:

```text
omega = measuredGyro - appliedBias
q = normalize(q × quaternionFromRotationVector(omega × deviceDeltaSeconds))
reference = inverse(q_at_recenter)
relativePose = reference × q
```

Use nanosecond **device timestamp deltas**, not host arrival intervals. Arrival batching would otherwise produce irregular angular integration. Recenter changes only the reference, not bias or accumulated orientation. Keep calibration and prediction separate: a predicted display quaternion must never feed back into the estimator or a calibration fit.

Projection uses camera axes right/down/forward, with the reference implementation's tested sensor signs. Positive sensor yaw looks right; positive pitch looks up. Preserve quaternion order, multiply direction and signs as a set. Verify yaw/pitch/roll and mixed rotations with the geometry tests and physical head turns before changing an axis convention.

Host freshness/prediction needs a common monotonic clock:

- Capture one `System.nanoTime()`-based arrival time per TCP receive and share it across that batch.
- `SampleClock` maps device time to host monotonic time using a lower envelope (32 one-second buckets), with rate fitting after eight seconds and bounded skew/slew.
- It estimates variable excess delivery delay. Unknown minimum transport delay remains in the offset; it is **not** an absolute sensor-latency measurement.
- Use arrival time while mapping warms up. Both receive age and mapped sample age must be under **100 ms** once available. Duplicate packets must not keep a stale pose alive.
- Convert clock domains explicitly when producing UI elapsed-time fields. Never subtract a device timestamp directly from Android host time.

## 6. Calibration: immediate tracking, independent confidence

A usable pose and a trusted bias are different states. The app can respond to head motion before calibration is confirmed. Show that distinction instead of freezing the screen or pretending an elapsed timer guarantees success.

The current sequence:

1. Initialize tracking from plausible gravity; if identity-bound saved bias is available, apply it while checking it in the background.
2. Build **20 ms means**, not a fit over unfiltered single-sample noise.
3. Try roughly **2 s quiet** proposals, then roughly **3 s gravity-assisted** robust fits for gentle motion. Gravity fitting uses bounded windows and Huber weighting.
4. Require a **separate, nonoverlapping agreeing interval** before trusting a new full bias observation. Freeze the first qualified candidate while waiting; continually replacing it with a better overlapping fit can make completion unreachable.
5. After eight seconds, keep the **3 s gravity path** available when its heading standard error is ≤0.0002 rad/s; otherwise try **12 s / 18 s** windows to average sway/heartbeat. The long windows must span the whole interval. Fit cadence falls to once per second; buffers stay bounded. The former Android code replaced the 3 s path entirely, unlike Mac, causing avoidable delays after motion settled. Existing heading/holdout/temperature guards still apply.
6. A saved-baseline health check can use 1.25 / 2.25 / 6.25 / 18.25 s windows. Long checks permit reversible short-term yaw but retain the net-heading gate. Confirmation does not create a new record or magically reduce uncertainty.
7. Keep **Recenter**, **Recheck**, **Desk calibration**, and **Forget** separate. Recheck keeps pose/bias. Forget invalidates pending loads/saves and deletes the selected device/sensor profile.

Gravity constrains tilt, not heading about gravity. A constant slow yaw can resemble gyro bias. Longer windows help average reversible motion; they cannot solve this observability problem. Do not relax all worn thresholds or force success at a timeout.

### RustDesk worn-start feedback and short-window recovery

The user reported 30–60 s worn calibration in RustDesk versus usually a few seconds
in Mac XR. This is subjective feedback, not a captured comparison. Mac retains the
2/3 s paths; Android's extended mode had replaced the 3 s path after eight seconds.
The new guarded short-path fallback above completes a synthetic settle-after-motion
case at 16 s total (6 s after settling), where the prior version had not completed
by 19 s. The 2/4/6-second reversible-sway regressions still take 25/25/37 s and retain
their original accuracy/independence assertions. Saved profiles remain app-private;
Mac's saved coverage may also explain fast startup but was not measured here.

A naive unguarded shortcut failed the original sway accuracy test and was rejected.
Details, evidence and the accompanying cursor/dictation changes are in the
[RustDesk follow-up](XR_INPUT_AND_CALIBRATION_2026_11_06.md).
Do not describe the synthetic timing as a live wearer result or force confirmation
at a timeout. The phone was unavailable during implementation. The follow-up was
subsequently installed on Pixel 9 over wireless ADB on 6 November 2026; APK checksum
and app launch were verified. Worn convergence and speech/gesture behavior still
need live user validation.

### Stationary recovery and the stale-bias failure

A real on-head attempt remained at 95%, then a floor test also failed with `Candidate differs from trusted bias`. The saved records were at 43.13–44.62°C; the stationary measurement was 36.60°C. Its mean gyro disagreed with the selected saved record by **0.0010665 rad/s along measured gravity**, equivalent to **3.666°/minute** residual heading drift. This matched the user's slow rightward drift report. The evidence did not distinguish contaminated original learning from thermal/time-dependent bias change.

The ordinary replacement guard was protecting the stale model. Even the earlier desk path could reject this difference. The fix is a narrow explicit recovery path, not a general worn-threshold relaxation:

- User requests **Desk calibration** with the glasses on a stable surface.
- Two disjoint quiet fits must agree, with known stable temperature.
- Raw gyro RMS <0.003 rad/s, raw acceleration RMS <0.035 m/s²; ordinary quiet-fit and absolute-bias gates still apply.
- Combined uncertainty ≤0.0002 rad/s, overall interval bias difference <0.0004 rad/s, heading difference <0.0002 rad/s, gravity directions within 0.1°.
- Only this confirmed requested desk operation can bypass the stale trusted-bias-change veto. It is a one-shot exception, ending at success.
- Insert the new observation at the current temperature, replacing nearby bins. Retain other temperature observations rather than claiming they were measured wrong at their original temperatures.
- Preserve the displayed pose, but retire the old cancelling residual promptly using the bounded bootstrap correction. Replacing only the stored base can leave the applied bias effectively unchanged.
- Reset the gravity observer to the stationary reference. Persist immediately, bypassing the ordinary 60-second write throttle. Verify the file and reconnect reuse, not just a “calibrated” label.

A second problem appeared during warm-up: already accepted quiet estimates were applied with a 30-second response, leaving **0.526° drift in three minutes**. Strongly agreeing quiet intervals now use a **5-second response**; ordinary fits retain 30 seconds. The trusted-heading gates and 0.0002 rad/s update cap remain. A seeded warming regression reproduced 0.538° before the change and now passes a 0.35° limit. The final live 0.0281° result was obtained later at a narrower, warmer temperature range; these are not a matched cold-start A/B experiment.

For exact current thresholds use [AdaptiveHeadPose](../../rustdesk-xr-probe/app/src/main/java/dev/rustdesk/xr/probe/AdaptiveHeadPose.java), [GravityCalibration](../../rustdesk-xr-probe/app/src/main/java/dev/rustdesk/xr/probe/GravityCalibration.java) and [StationaryRecoveryTest](../../rustdesk-xr-probe/app/src/test/java/dev/rustdesk/xr/probe/StationaryRecoveryTest.java).

## 7. Gravity correction and temperature-assisted reuse

Do not low-pass the entire head orientation to hide drift; that adds movement lag. The useful Mac port was **motion-compensated gravity averaging**:

- Rotate acceleration into the integrated world frame, then average 20 ms blocks over a qualified 0.5–0.6 s window.
- Pause on excessive motion, inconsistent acceleration or gaps.
- Apply bounded perpendicular correction after gyro integration; current angular correction cap is 0.005 rad/s.
- This tolerates normal high-frequency accelerometer noise that could permanently disable an older raw-noise gate.
- Separately refine gyro bias perpendicular to measured gravity through qualified intervals. These tilt-only residuals are session-local and cannot learn heading.

Temperature is available in normal IMU reports. `TemperatureFilter` uses trimmed 250 ms blocks, smoothing and approximately two seconds of qualification. A `BiasProfile` keeps up to 32 independent observations, replaces neighbors within 0.5°C, and interpolates only brackets at most 5°C apart. A nearest record outside its measured temperature remains a fallback, not proof it is correct.

### What the glasses API gives us

`NRGlassesGetConfig` is a verified read-only getter, not a fused-pose API. The exact current request is:

```text
27 1f 00 00 00 06 80 00 00 01 18 00
```

Use the bounded existing `FactoryConfigReply` parser for the outer big-endian command/length, transaction and nested Protobuf/JSON. The live reply was roughly 219 KB. Do not assume a single read receives it. `FactoryCalibrationSession` has an eight-second overall receive deadline and serializes background IO.

The configuration supplies physical identity and a factory gyro-temperature table. Current reuse:

- Bind private host profiles to physical identity, exact configuration hash, algorithm/coordinate convention and transport sensor. IP address or USB path is not sufficient.
- Store privately under Android no-backup storage, atomically, with expiry (currently 30 days), bounded observations and corruption fallback. Do not export raw serials/full configuration in reports.
- Use factory curves only as **guarded thermal deltas anchored to a qualified compatible host observation**. Do not subtract a factory total bias and a host total bias twice.
- Existing factory-delta gates include anchor uncertainty ≤0.0006 rad/s, temperature proximity ≤3°C and delta magnitude ≤0.0005 rad/s, plus table/host compatibility checks. Refer to `FactoryBiasTable` for the full contract.
- Transport sensor ID 2 is not automatically configuration `device_1` by numbering. A verified getter does not establish every sensor-axis/matrix correspondence or expose current online firmware bias.

Remote fused-pose publication exists in inspected firmware, but **no validated fused-pose stream is used here**. `MSG_REQUIRE_POSE` changes an export flag; closing the socket was not proved to undo it. Pose convention, session identity and lifetime remain unresolved. Do not build a new app around guessed subscription packets or internal struct offsets. See the [remote protocol investigation](../../rustdesk-xr-probe/docs/REMOTE_POSE_AND_CALIBRATION_PROTOCOL.md).

## 8. Projection math to port

Prefer the tested CPU homography and GPU inverse-ray implementation together; the CPU path is an independent oracle for the shader.

For output width `W`, height `H`, source size `Sw × Sh`, horizontal field of view `F`, and distance multiplier `d`:

```text
f = W / (2 × tan(F / 2))              // focal length in output pixels; F in radians
sourcePlanePoint(sx,sy) = ((sx-Sw/2)/f, (sy-Sh/2)/f, d)
```

The source plane stays fixed at world `z=d`; the camera rotates. To map a source point to output, rotate it by the **inverse relative head quaternion**, then perspective-divide and center at `(W/2,H/2)`. Clip before division at the near plane and output bounds.

The fragment shader instead maps each output pixel to source coordinates:

```text
cameraRay = ((px-W/2)/f, (py-H/2)/f, 1)
worldRay = rotate(relativeHeadQuaternion, cameraRay)
if worldRay.z <= epsilon: black
rayDepth = d / worldRay.z
if rayDepth < nearPlane: black
sourcePixel = (Sw/2, Sh/2) + f × rayDepth × worldRay.xy
if sourcePixel outside source bounds: black
else sample source texture at sourcePixel
```

At identity pose, `sourcePixel = sourceCenter + d × (outputPixel-outputCenter)`, so `d=1` gives centered 1:1 pixels. At `d=2`, the source appears half as large; at `d=0.5`, twice as large. The probe allows distance 0.25–4 and horizontal FOV 20–100°, with fresh defaults 1.0 and 45°. **45° is a tuning assumption, not a measured optical constant.**

Critical details:

- Do not clamp source edges: looking beyond the plane should reveal black, not a smeared border.
- Do not billboard the plane toward the head; yaw/pitch/roll must alter its perspective.
- Handle looking away, horizon/near-plane crossings and mixed rotations without NaNs or mirrored geometry.
- Use actual **pixel** surface dimensions, not dp or phone dimensions.
- GL fragment coordinates and Canvas coordinates differ vertically; preserve the backend's conversion, texture orientation and pixel-center conventions.
- For arbitrary content sizes, replace all hardcoded `Viewport.SOURCE_WIDTH/HEIGHT` assumptions, including CPU bounds, cursor mapping, shader uniforms and calibration-card placement.
- A projected source bounding rectangle is the bounding box of a polygon; it is not the exact set of transmitted pixels.
- Map a source-space cursor through the same center-row projection. Keep phone/system controls screen-space. Handle off-plane pointer targets and overlays deliberately.
- A simple yaw/pitch translated integer crop is a useful sharpness/debug baseline, but it stays face-on and is not the perspective anchor.

## 9. Rendering and frame ownership

The source is a cached static 4K bitmap in this probe, about **31.6 MiB (33.2 MB) RGBA**. A new video app must supply its own content pipeline; this probe does not prove a complete live 4K decoder integration.

Two single-pass GPU paths exist:

1. **EGL/OpenGL ES 3** (`EglPlaneRenderer`): cached source texture, one fullscreen triangle, explicit surface ownership and presentation feedback. This was the backend used for the final live drift verification. It is a good reference for a texture-based port, not a requirement to copy all probe UI.
2. **AGSL RuntimeShader** (`PlaneShader`, Android 13+): one full-surface hardware-Canvas draw, same ray/plane mapping. Linear child-shader filtering must be set explicitly; Paint filtering alone does not fix it.

The older Canvas homography/strip path remains a fallback/comparison. Single-pass projection replaces approximately 128 clipped bitmap draws with one projection draw. Other overlays and Android composition are separate work.

Use a dedicated render thread, immutable settings snapshots and coalesced redraw requests. Destroy EGL resources on their owning thread before surface destruction returns. Never have Canvas and EGL connected to the same Surface at once; replace the Surface when switching backends. Expose actual backend/fallback state instead of silently claiming the selected backend ran.

For live content (new-app design work, not yet established by the static probe):

- Keep one authoritative decoder/content producer and explicit ownership of buffers/textures/fences. Prefer a GPU-compatible frame path; assess `SurfaceTexture`/external textures or buffer import according to the producer.
- Preserve producer crop/transform/color-space semantics. The static bitmap shader is not automatically an external-video-texture shader.
- Avoid full-frame CPU reprojection and repeated 4K readbacks/uploads. One full-frame RGBA copy at 60 fps moves roughly **1.99 GB/s**; this is memory traffic, not video bitrate.
- Update source content when a new frame arrives; reproject the last valid content at display cadence while the head moves. Drop superseded queued content/poses according to an explicit latency policy.
- Keep input coordinates tied to actual source dimensions, not output size. Release input state on surface/session loss.

### RustDesk live-content consumer (6 November 2026)

The fork now consumes the exported tracker in
[`XrProjectionRenderer`](../flutter/android/app/src/main/java/com/carriez/flutter_hbb/xr/XrProjectionRenderer.java).
Its existing decoder/mailbox supplies one cached BGRA texture; a separate GLES2
single-pass shader samples that texture into the output-sized surface. Source
uploads occur only for new remote frames. Head motion redraws the cached texture
on coalesced Choreographer callbacks, with a fresh pose read after uploads and
framebuffer preparation. Prediction remains off, FOV is 45°, distance is 1.
The source-space cursor (including hotspot) and the exported calibration HUD are
composited in the same projection pass. There is no full-frame Canvas conversion.

The external viewer defaults to 3DoF after a remote session begins, including when
the display is attached later. Tracking starts before the GL surface can first draw;
pause, close, session end and unplug stop it. Surface recreation alone does not
own the estimator. Stale samples hold the last displayed orientation and show the
missing-IMU card. The destination app learns its own private calibration profile;
probe profiles are not copied. Phone controls distinguish recenter, ordinary
recheck and explicitly stationary desk recovery. Flat fit/manual pan remain
available, using the original native draw path. In 3DoF, Clear text is on the phone;
the old flat viewport hit target must not intercept source-coordinate remote clicks.

These are implementation facts, **not new physical-glasses measurements**. The
fork's GLES2 shader, video copy cost, lifecycle and wearer behavior need their own
acceptance evidence. The probe's EGL presentation-feedback scheduler was not ported;
Choreographer scheduling does not establish external photon timing. See the
[fork integration and validation record](XR_MODE_IMPLEMENTATION.md)
and its [GPU oracle](../flutter/android/app/src/androidTest/java/com/carriez/flutter_hbb/xr/XrProjectionInstrumentation.java).

## 10. Android external displays and lifecycle

The tested path uses a separate Activity launched on the selected external display, with screen wake/keep-on/fullscreen handling and phone-owned controls/IME. A `Presentation` fallback exists, but its presence in code does not establish equal hardware reliability.

Discover displays and modes dynamically. Handle removal, replacement, display-mode changes and surface recreation. Do not store Android display IDs as permanent identity. The probe delays automatic reopening approximately one second after attach/start to allow Android desktop startup to settle. Preserve explicit user Close behavior instead of immediately auto-reopening.

Maintain four diagnostics: **actual mode**, **view dimensions**, **buffer dimensions**, and **requested mode**. Android preferences and frame-rate hints are requests, not proof of acceptance. In historical tests:

- Normal: 1920×1200 near 60 Hz worked.
- 21:9: 2560×1080 worked through Android external-display settings.
- 32:9: 3840×1080 was advertised but app-only selection was unreliable; shell-assisted success was diagnostic, not an ordinary-app solution.
- Active Pixel policy and advertised modes constrained the tested connection near 60 Hz. Firmware EDIDs/vendor panel claims do not prove this host negotiated 120 Hz.

A real integration bug found during verification: **Start floating screen must apply the mode directly**, not rely solely on `Spinner.setSelection()` invoking a listener. A hidden Spinner deferred the callback and left the view flat even though the tracker was running. Current `MainActivity.applyViewport()` is shared by the button and selector. Tests must assert actual 3DoF rendering, not just successful UI clicks.

## 11. Prediction and presentation timing

**Default prediction off.** The user observed prediction-related micro-shakes; the final stable measurement used no center or row prediction. Preserve an explicit user's saved setting; do not silently re-enable it when switching models. V2a is the default *model if enabled*, which is different from prediction being enabled by default.

Optional models are V1 constant rate, V2 Anchor, V2a Quiet motion. They predict only the rendered pose. V2a reduces amplification of small noisy rates without low-pass filtering the integrated head pose.

If enabling prediction experimentally:

- Read pose late. Add mapped sample age **once**.
- Use a verified per-surface presentation target where available, plus an optical offset; otherwise use the bounded fixed-horizon path.
- Android's retained optional values are 8 ms optical lead and +16.67 ms top-to-bottom scanout. These are estimates, not measured photon timing. Do not copy Mac 120 Hz assumptions or equate host input period to OLED scan duration.
- Each row evaluates the full bounded polynomial from the same unpredicted quaternion. Do not predict the center, then predict each row again from that already predicted pose.
- Current center horizon is capped at 50 ms; row evaluation is bounded. Disable extrapolation/row correction for stale/frozen tracking and guard plane-crossing cases.
- With scanout zero, evaluate prediction once per draw; bypass per-fragment polynomial/trigonometry/quaternion work. With prediction off, bypass CPU prediction too.

EGL feedback uses public extension capability checks and a small NDK bridge. Correlate frame IDs with GPU completion, compositor latch and actual present. Pending/invalid/failed samples are not measurements. Query later with bounds rather than blocking rendering.

The tested advertised compositor timing initially predicted one refresh early. Observed latch/present phase corrected scheduling. Use a phase accumulator, measured rendering budget and margin; skip obsolete slots, do not submit catch-up bursts. `eglPresentationTimeANDROID(..., 0)` made buffers eligible for earliest presentation in the current experiment; a future requested timestamp had delayed them. This is a measured implementation choice, not a universal zero-latency recipe.

Do not use `glFinish`, busy-waiting or uncapped drawing to conceal queueing. Generic Choreographer expected-present values are not automatically the external Surface's presentation timeline. A CPU draw time or “pose age at draw” is not motion-to-photon latency.

Historical EGL runs measured approximately **20 ms frame-ready→present** and **22–23 ms late-pose-read→present**, while maintaining about 60 fps. The wearer reported “About the same” in that timing comparison. Host pipeline improvement did not prove an optical improvement. See [EGL measurements](../../rustdesk-xr-probe/docs/EGL_PRESENTATION_FEEDBACK.md) and [display timing investigation](../../rustdesk-xr-probe/docs/DISPLAY_TIMING_AND_120HZ.md).

## 12. Calibration HUD and diagnostics

Put the ring and text **inside the projected source plane**, so they move with the screen. `CalibrationHud` implements amber learning, teal active/refining, gray missing-IMU state, an activity arc during confidence waits, and complete removal after confirmation. Labels are cached; the GPU combines the card in the projection pass. Canvas fallback uses source coordinates too.

Progress is confidence, not a countdown. A 95% display that never changes gives the wrong impression. Show independent holdout/averaging status rather than promising completion while the acceptance criteria still fail.

Retain diagnostics needed to distinguish hardware, math, calibration and rendering failures:

- Accepted unique samples/s, command/sensor, timestamps, duplicates/backwards/gaps/resets, framing errors.
- Tracking ready, bias ready, calibrated, candidate rejections/reason, stationary revision, calibration method/uncertainty, temperature and record count.
- Applied bias, quaternion, pose freshness, mapped sample age, clock rate and excess delivery delay.
- Renderer actually used; native buffer/view/mode; active projection; draw/present cadence; surface freshness; fallback error.
- Prediction actually active, horizon and clock source; measured versus assumed timing.

Keep serials, full device factory config, Wi-Fi credentials and unrelated user content out of shared reports. Preserve a compact, reproducible measurement artifact with hardware/software context and failure history.

## 13. Performance lessons and remaining opportunities

Already implemented and useful to port:

- GPU reprojection, cached content texture, one projection pass.
- Late immutable pose reads; coalesced scheduling; separate IO, IMU and render work.
- Pose publication at most every 4 ms while accepting ~1 kHz IMU; phone status around 2 Hz.
- Bounded calibration data and fit cadence: 250 ms normally, 1 s during extended worn fits.
- Shared center pose for projection metadata; prediction off/zero scanout fast paths.
- Cached HUD labels and overlays; no full-frame bitmap construction for each progress tick.

Possible follow-ups **requiring profiling**, not measured gains:

- Reduce array allocation in quaternion/IMU hot loops and repeated temperature-model scans/sorts.
- Decide whether disabled optional prediction filters can stop running; define warm-up/reset semantics before doing so. Mac also maintains filters, so this is not a missing Mac optimization.
- Decouple expensive diagnostics/CPU projected bounds from every display frame while preserving correct input mapping.
- Measure allocations/GC, CPU fitting spikes, GPU time, texture copies, submission queue and thermals separately.

Mac's Metal/CVDisplayLink and desktop-capture scheduling are not drop-in Android changes. A faster shader cannot create a missing display mode or remove glasses-internal processing. Avoid performance claims without a matched measurement and the actual backend/output mode.

## 14. Porting sequence and acceptance tests

1. **External display shell:** native-sized test grid, stable lifecycle, phone IME separate. Confirm actual output and buffer scale.
2. **Transport only:** real glasses ~1 kHz unique samples, correct axes/units, fragmented TCP tests, no default-network change.
3. **Estimator core:** copy pure Java pipeline and regression tests. Verify timestamp gaps, duplicate isolation, temperature handling, gravity observer and recenter continuity.
4. **Static plane:** port shader plus independent CPU oracle. Verify centered 1:1, all axes, mixed rotations, FOV/distance, edges/horizon/behind-plane cases.
5. **Calibration/persistence:** identity-bound profile, ordinary worn checks, explicit stationary replacement, prompt applied correction, immediate save and reconnect confirmation. Never use a fixed completion timeout as success.
6. **Real app content:** explicit frame/texture ownership, actual source size, correct crop/color/coordinate mapping. Measure copy cost and render cadence with decoding active.
7. **Live stationary measurement:** deliberately stationary glasses; test cold/warming and warm holds separately. Record calibrated time, temperature, bias, quaternion displacement and stream counters. Require the 3DoF renderer to be active and updating throughout.
8. **Wearer validation:** turns, stops, roll, recenter, extended head hold, readability and comfort. The user must assess optical behavior; a floor test cannot prove it.
9. **Lifecycle/endurance:** repeated unplug/replug, sleep/wake, mode change, background/foreground, source-session loss, and a long thermal run. Prediction stays off for baseline comparisons.

The current live check allows five seconds after calibration to settle, then measures maximum quaternion displacement from the initial orientation over three minutes, with a 0.5° pass limit. It also restarts the IMU socket and requires saved-baseline reconfirmation. A zero endpoint alone is insufficient: oscillation can return to its origin. Keep maximum displacement and the sample trace.

GPU tests compare against independent homography inversion, not a copy of the shader formula. Historical Pixel validation passed 30 cases / 539,998 pixel comparisons. A later Android 13 SwiftShader full oracle has a reproducible precision mismatch also present in the unmodified baseline; do not loosen tolerance silently or claim the whole latest emulator suite passed. The focused HUD/fast-path suite passed 18 cases / 384,336 comparisons. These are different checkpoints, not counts to add together.

### Reproduce the reference build and live check

Use the probe's Gradle wrapper. Current configuration: minSdk 26, compile/target 36, Java language level 17, NDK 28.2.13676358, CMake 3.22.1. Recent builds used a locally installed JDK 21; do not copy temporary machine-specific JDK paths into the new project.

```sh
cd rustdesk-xr-probe
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

# Discover the current Wi-Fi debugging connection. Pairing and connection ports differ.
adb devices -l
adb mdns services
adb -s WIFI_DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s WIFI_DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Explicitly mutates the real calibration. Glasses must be on a stable surface.
adb -s WIFI_DEVICE shell am instrument -w -e stationary true \
  dev.rustdesk.xr.probe.test/dev.rustdesk.xr.probe.PlaneShaderInstrumentation

adb -s WIFI_DEVICE exec-out run-as dev.rustdesk.xr.probe \
  cat files/stationary-recovery-live.json > stationary-recovery-live.json
```

Use Wi-Fi while glasses occupy USB-C. If a particular ADB server cannot reach the device, a separate `adb -P SERVER_PORT` server was a successful local workaround; carry that flag on every command using it. Rediscover changing endpoints rather than hardcoding our old phone address. Installation must preserve app data unless intentionally testing an empty cache.

Inspect the instrumentation's explicit **PASS/FAIL text and saved evidence**, not shell exit status alone. The custom runner can emit a failed assertion despite an uninformative shell status. Storage/replay/GPU tests and the explicit live modes have different effects; read the runner before invoking them against user calibration.

## 15. Detailed references and historical context

| Read next | What it adds |
|---|---|
| [Probe README](../../rustdesk-xr-probe/README.md) | Controls, setup, renderer switches, reports and build operation |
| [Worn calibration / stationary recovery](../../rustdesk-xr-probe/docs/WORN_CALIBRATION.md) | Latest stale-bias and warm-up diagnosis, exact recovery evidence |
| [Mac tracking port](../../rustdesk-xr-probe/docs/MAC_XR_TRACKING_PORT.md) | Pipeline port, persistence/identity, earlier live reuse results |
| [HUD and performance](../../rustdesk-xr-probe/docs/TRACKING_HUD_AND_PERFORMANCE.md) | Projected ring, gravity observer, prediction-off optimization, emulator caveat |
| [Anchor projection](../../rustdesk-xr-probe/docs/ANCHOR_PROJECTION_AND_STABILITY.md) | Geometry and stability development history |
| [Android V2a](../../rustdesk-xr-probe/docs/HEAD_TRACKING_PREDICTION_V2A_ANDROID.md) | Optional predictor family and timing/persistence contract |
| [EGL presentation feedback](../../rustdesk-xr-probe/docs/EGL_PRESENTATION_FEEDBACK.md) | Native bridge, frame IDs, latch scheduling, measured host latency |
| [Display timing / 120 Hz](../../rustdesk-xr-probe/docs/DISPLAY_TIMING_AND_120HZ.md) | Mode/policy evidence; host refresh versus optical scanout |
| [Resolution findings](../../rustdesk-xr-probe/docs/RESOLUTION_AND_HEAD_TRACKING.md) and [hardware validation](../../rustdesk-xr-probe/VALIDATION.md) | Output-mode experiments and their limitations |
| [IMU transport notes](../../rustdesk-xr-probe/docs/IMU_TRANSPORT_AND_CALIBRATION_NOTES.md) | Raw protocol research and legacy distinctions |
| [Remote pose/config protocol](../../rustdesk-xr-probe/docs/REMOTE_POSE_AND_CALIBRATION_PROTOCOL.md) | Verified getter; unresolved fused-pose transport and lifetime |
| [Official firmware analysis](../../rustdesk-xr-probe/docs/OFFICIAL_1S_FIRMWARE_ANALYSIS.md) and [startup interfaces](../../rustdesk-xr-probe/docs/ANCHOR_STARTUP_AND_POSE_INTERFACES.md) | Offline evidence; distinguish static code from live behavior |
| [Mac IMU porting guide](../../mac-xr/docs/IMU_HEAD_TRACKING_PORTING_GUIDE.md) | Cross-platform estimator background; check Android deltas in this guide |
| [Mac V2a](../../mac-xr/docs/HEAD_TRACKING_PREDICTION_V2A.md) and [roll drift](../../mac-xr/docs/ROLL_DRIFT_0.3.8.md) | Predictor/gravity rationale; do not copy Mac display timing assumptions |
| [Original XR handoff](../../rustdesk-xr-probe/docs/XR_MODE_HANDOFF.md) | Historical app integration/input ownership; its “no pose reader” and build/test status are obsolete |

These links are repository-relative for use in a copied project. If copying this guide alone, also copy referenced code/evidence or change links to an accessible source checkout. Source code and measurements matter more than preserving this workspace's directory names.

## 16. Keep this guide current

This document is the maintained cross-project handoff. Whenever we learn something material about transport, calibration, drift, coordinates, projection, source-frame integration, lifecycle, rendering performance, prediction, display modes or hardware behavior:

1. Update the relevant section in the same work session, not only a chat reply or chronological note.
2. Record date, device/OS/context, what changed, evidence, and limitations. Label simulation, stationary measurement, wearer report, static firmware inference and unverified hypothesis separately.
3. Link focused documents/test artifacts for detail. Preserve failures that explain a fix; replace stale current recommendations instead of leaving conflicting defaults.
4. Keep source names/defaults/commands aligned with code. Regenerate and check `rustdesk-xr-probe/portable/Xreal3Dof.java` when its canonical tracking/calibration/projection sources or facade change. Do not publish unit-private factory data, serials or changing ADB credentials/endpoints.
5. Update the short change record below. Keep this document usable as a starting guide rather than turning it into an unfiltered chat transcript.

The workspace [AGENTS.md](../AGENTS.md) makes this a standing instruction for future work here. For another project, copy this guide and add the following instruction to that project's `AGENTS.md` (adjust the path):

```text
Maintain docs/ANDROID_XREAL_3DOF_PORTING_GUIDE.md as the living XR handoff.
Whenever XR implementation or new measurements change our understanding, update
its relevant sections, evidence links, limitations and dated change record in the
same task. Distinguish measured results from hypotheses and preserve user feedback
as subjective evidence. Do not leave new findings only in chat.
```

This is an ongoing repository workflow, not an automatic background monitor. Another project needs the guide/instruction carried into its own context.

### Change record

- **2026-11-06:** Consolidated the Android/Mac knowledge and current source map. Included parser/clock contracts, calibration independence, gravity averaging, factory identity/thermal deltas, 4K-source/native-output geometry, EGL scheduling, prediction-off default, HUD/performance lessons and validation commands.
- **2026-11-06:** Captured stale-bias recovery, warm-up convergence correction, direct 3DoF button activation, the final 0.0281°/three-minute active-renderer result, and the user's subsequent positive subjective feedback. Added standing maintenance instructions.

- **2026-11-06:** Added the single-file `Xreal3Dof.java` distribution, public lifecycle/snapshot/geometry/Canvas-render API, arbitrary source dimensions, embedded notices, copy instructions and reproducible export checks. Validated standalone renamed-package compilation, 71 extracted-code/API tests and API-26 dex; no new hardware run claimed.

- **2026-11-06:** Integrated the portable tracker into the RustDesk fork's live BGRA texture path: default native-sized 3DoF, late pose reads, projected cursor/calibration HUD, explicit calibration controls and viewer-owned transport lifecycle. Preserved the flat native renderer. Documented app-private profile separation and the lack of new hardware/optical measurements; validation details live in the fork integration record.
- **2026-11-06:** Declared the portable facade's existing API-26 requirement with `@TargetApi(26)` so lower-minSdk hosts can gate XR without false nested-API lint findings. Regenerated both copies; no estimator or rendering behavior changed.
- **2026-11-06:** Recorded RustDesk emulator validation: 18 geometry/HUD cases / 73,204 sampled pixels passed; retained strict wrap/checker filtering failures, including the same checker result in the unchanged native flat shader. No physical-glasses validation or optical improvement claimed.

- **2026-11-06:** Recorded the user's 30–60 s RustDesk worn-start report and Mac comparison. Restored a heading-qualified short gravity-fit path after eight seconds while retaining long-sway/stationary protections; synthetic settled-motion confirmation is 16 s total / 6 s after settling. Regenerated the portable module. Documented centered cursor, drag removal, touch-glass motion and user-confirmed staged dictation gestures; no phone deployment/live validation in this follow-up.

- **2026-11-06:** Deployed the input/calibration follow-up to Pixel 9 via wireless ADB with app data preserved. Installed APK SHA-256 matches the tested artifact; MainActivity cold launch succeeded. This verifies installation/startup only, not on-head calibration or microphone/remote-input behavior.

- **2026-11-06:** Made the existing persisted phone trackpad-sensitivity slider prominent (0.5–8×, control lock preserved). Accept/discard now immediately hides the dictation preview; pending final recognition still supplies the accepted text without redisplaying it. This changes XR phone controls/preview state only, not tracking or projection.
