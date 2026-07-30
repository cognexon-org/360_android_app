# PropertyTour360 Android v2.1 — Mode A capture update

- Added per-room choice between **Quick Room View** and **Full Room Sphere**.
- Quick Room View uses one central ring with 8–10 normal 1x camera positions.
- Full Room Sphere uses two staggered rings at approximately 30° up/down plus ceiling and floor, normally 18–22 images.
- Replaced the old 26-target default capture pattern.
- Added Camera2 field-of-view estimation to choose 8, 9 or 10 positions without user calibration or ultrawide dependency.
- Uploads measured yaw, pitch, roll and capture metadata with every photo.
- Uploads the capture manifest as immutable source evidence.
- Uses the corrected back-camera orientation tracker supplied with this release.
