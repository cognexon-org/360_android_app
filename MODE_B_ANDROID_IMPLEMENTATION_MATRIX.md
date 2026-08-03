# PropertyTour360 Android 3.1 — Mode B implementation matrix

This release implements the Android-owned Mode B workflow. Depth fusion, TSDF reconstruction, topology inference and professional design editing remain responsibilities of the combined backend and Designer Studio.

## Capture Package v2.1

- Synchronized CPU RGB keyframes, camera poses and intrinsics
- ARCore Automatic Depth, Raw Depth and confidence where supported
- New-depth timestamp filtering
- Adaptive translation, rotation and time-based keyframes
- Device, Android, application, display-rotation and tracking metadata
- Floor, ceiling and wall plane observations
- Centre-hit operator markups with world-space proposals
- Capture summary, keyframe index and SHA-256 manifest
- Field plan and measurements attached after operator confirmation
- Typed per-file uploads and immutable ZIP evidence

## Scan guidance

- Tracking failure guidance
- Live RGB/RGB-D keyframe count
- Wall observation count
- floor/ceiling completion prompts
- corner/opening markup counts
- centre-reticle surface-hit guidance
- explicit feature buttons for corners, openings, floor, ceiling, stairs, level changes and measurement endpoints

## Field capture and correction

- Rectangle, L-shape and free-polygon plans
- Draggable vertices and 3–32 vertex validation
- Minimum-area and self-intersection checks
- Multiple wall-attached openings and fit validation
- Ceiling height and per-wall measurement provenance
- Tape, laser, AR-assisted and manual method labels
- Tolerance, endpoints, device and evidence references
- Floor/elevation/origin/rotation and connection anchors
- Quick rectangular fallback

## Canonical mobile draft

- Structure, floors, rooms, transforms, polygons, walls and openings
- Evidence references, confidence, scale and verification status
- Measurement provenance and timestamps
- No fabricated default opening placement
- No automatic public publishing

## Capability tiers

- **Android Depth:** RGB + dense/raw depth + confidence + poses + planes + field plan
- **AR-assisted:** RGB + poses + planes + field plan
- **Manual:** field-plan editor + measurements + optional reference panorama

## Deliberate application boundaries

- The phone preserves and validates evidence; it does not run production TSDF reconstruction.
- The backend generates explainable sensor proposals and geometry QA.
- Designer Studio performs final shell correction, design, export and approval.
- The app never labels sensor output as construction-ready.
