# Mode A preservation statement

## Baseline

Mode A is retained from the user-supplied working archive:

```text
360_android_app-main (1).zip
```

## Source preservation checks

The release validation compares the rebased v3.1 source against that baseline.

| Component | Result |
|---|---|
| `CameraFovEstimator.kt` | Byte-identical |
| `OrientationTracker.kt` | Byte-identical |
| `PanoramaCaptureScreen.kt` | Byte-identical |
| `BackendRepository.uploadRoomPhotosAndStitch()` | Text-identical |
| Mode A ViewModel workflow | Text-identical |
| Mode A Compose workspace and capture dialog | Text-identical |

The only shared changes outside those blocks are the application version and additive Mode B data fields/imports.

## Preserved behavior

- Latest dense-overlap capture tuning
- Quick Room View and Full Room Sphere
- tighter alignment gating
- AE/AWB lock behavior
- current target-grid generation
- current capture metadata and manifest
- current panorama upload asset kind
- current stitch API payload
- imported panorama path
- room connection validation
- tour creation, hotspots and publication

No Mode A fallback or alternate implementation was substituted.
