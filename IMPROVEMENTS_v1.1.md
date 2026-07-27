# PropertyTour360 Android v1.1 — Mode A completion update

## Implemented

- Replaced the magnetometer-sensitive yaw-only capture controller with a display-aware, smoothed orientation tracker.
- Prefers `TYPE_GAME_ROTATION_VECTOR` to avoid magnetic-heading jumps; falls back to `TYPE_ROTATION_VECTOR`.
- Added circular yaw interpolation, pitch/roll filtering and angular-speed measurement.
- Added a fixed-reference target system that does not recenter after each frame.
- Replaced the 12-image horizontal ring with a 26-point sphere:
  - 8 horizon frames;
  - 8 upper-ring frames;
  - 8 lower-ring frames;
  - ceiling and floor frames.
- Added a visual target reticle showing the direction required to reach the next frame.
- Added alignment thresholds for both yaw and pitch.
- Added motion rejection and a 650 ms stability dwell before automatic capture.
- Added a manual capture fallback and a per-session auto-capture switch.
- Changed CameraX capture to maximum-quality JPEG at quality 95.
- Added a versioned JSON capture manifest with target pose, measured pose, movement speed and timestamp for every frame.
- Added the high-sampling-rate sensors permission.
- Bumped application version to 1.1.0 / versionCode 2.

## Clean-room boundary

No code, resources, native binaries, branding or proprietary assets from the inspected third-party APK are included. The implementation reproduces the general guided-photo-sphere workflow using Android public APIs and original PropertyTour360 code.

## Validation still required before store release

- Build and instrumentation testing in Android Studio with network access for Gradle dependencies.
- Physical-device testing across portrait-display rotations and representative manufacturers.
- Stitching calibration against the backend worker using the 26-frame sequence.
- Low-light, mirror, blank-wall, small-room and parallax test sets.
- Privacy policy, consent, retention and deletion workflow review.
