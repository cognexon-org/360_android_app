# PropertyTour360 Android v2.0 — Final integrated source release

## Mode A
- Stable fixed-reference quaternion target grid and 26-point sphere coverage.
- Game-rotation-vector preference, display rotation correction, smoothing, motion gate, dwell-based auto capture, manual fallback and capture manifest.
- CameraX high-quality JPEG capture, panorama import, room graph, upload/stitch, validation and publish flow.

## Mode B
- ARCore optional capability path with horizontal/vertical planes, camera poses, intrinsics, Raw Depth and confidence evidence.
- Evidence package now records duration, pose count, plane snapshots/types and depth-frame count.
- Automatic scan-quality score and GOOD_DRAFT / USABLE_WITH_CORRECTION / RESCAN_RECOMMENDED status.
- Implausible measurements are rejected before server submission.
- Weak AR scans are blocked from publishing; measured manual fallback remains available.
- Canonical model carries measured-draft, designer-confirmation and structural-verification status.
- Unit tests cover geometry and scan-quality rules.

## Release boundary
This is a complete integrated source implementation for the supplied v1 backend contract. It still requires Android Studio dependency sync, physical-device QA across supported phones, backend deployment, signing, privacy policy and pilot validation before Play Store production release.
