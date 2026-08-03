# PropertyTour360 Android Capture v3.1.0

Native Kotlin Android application containing both capture modes.

- **Mode A — Property Tour:** the latest working guided panorama implementation from the supplied `360_android_app-main (1).zip` is retained without feature rollback.
- **Mode B — Design Scan:** synchronized RGB-D evidence capture, field-plan correction, measurement provenance and draft handoff to the unified PropertyTour360 backend and Designer Studio.

## Mode A preservation

This release was rebased on the user's latest working Android repository. The following Mode A source files are byte-for-byte identical to that baseline:

- `CameraFovEstimator.kt`
- `OrientationTracker.kt`
- `PanoramaCaptureScreen.kt`

The complete `uploadRoomPhotosAndStitch()` repository function and the Mode A Compose workspace are also identical to the supplied working baseline. This retains:

- 40% overlap / 10–16 positions per ring
- tighter 3.5° capture alignment
- exposure and white-balance lock after the first frame
- measured yaw, pitch and roll metadata
- the existing panorama manifest asset contract
- pose-aware stitching request and server QA workflow
- imported equirectangular panorama support
- room graph validation and tour publishing

See `MODE_A_PRESERVATION.md` and `CHANGES_MODE_A_STITCHING_v2_2.md`.

## Mode B v3.1 implementation

### Guided AR/RGB-D capture

- ARCore `AUTOMATIC` Depth where supported, with Raw Depth and confidence evidence
- synchronized CPU RGB keyframes, camera pose and intrinsics
- dense and raw depth timestamps with stale-frame filtering
- adaptive translation, rotation and time-based keyframes
- horizontal and vertical plane observations
- centre-reticle AR hit proposals for corners, doors, windows, passages, floors, ceilings, stairs, level changes and measurement endpoints
- device, application, rotation, tracking and evidence metadata
- capture summary, manifest and SHA-256 checksums

### Field-plan workflow

- rectangular, L-shaped and free-polygon room plans
- draggable vertices and polygon validation
- multiple wall-attached doors, windows and open passages
- opening offsets, sizes, sill height and optional swing/direction
- ceiling-height confirmation
- tape, laser, AR-assisted and manual measurement provenance
- measurement tolerance, endpoints, device and evidence references
- floor ID, elevation, origin, rotation and doorway/connection anchor metadata
- quick rectangular fallback for basic devices and operators

### Capture Package v2.1

A scanned and field-confirmed room contains:

```text
manifest.json
checksums.sha256
capture_summary.json
intrinsics.json
poses.jsonl
planes.jsonl
keyframes.jsonl
operator-markups.jsonl
field-plan.json
measurements.json
keyframes/{id}/
  rgb.jpg
  metadata.json
  depth_dense.depth16       # when available
  depth_raw.depth16         # when available
  confidence.confidence8    # when available
```

The app verifies the checksum manifest before upload and registers primary files using typed asset kinds. An immutable ZIP copy is also uploaded as `MODEL_EVIDENCE`.

### Trust and publishing rules

- Android creates a draft design project only.
- Android does not publish a Mode B project publicly.
- Sensor and operator geometry is labelled as a draft requiring Designer Studio review.
- Editing a field plan invalidates the previous evidence-upload state and requires re-upload.
- Structural status remains unknown until appropriately verified.

## Unified backend target

Mode B targets the new combined backend that will be generated after this app. It does not contain compatibility branches for an obsolete Mode B backend. Mode A keeps the working API contract from the supplied repository.

See `MODE_B_V2_INTEGRATION_CONTRACT.md` for the exact expected contract.

## Requirements

- Android Studio
- Android SDK 36
- JDK 17
- Android 7.0/API 24 or later
- A physical ARCore-supported device for sensor-assisted Mode B capture
- Ordinary Android camera phones remain supported for Mode A

## Build

```bash
./gradlew clean test assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The supplied source repository does not include `gradle-wrapper.jar`; restore/regenerate the wrapper in Android Studio or CI before command-line builds.
