# Mode B v2.1 unified backend integration contract

## Workflow

```text
DESIGN_SCAN capture
→ typed Capture Package v2.1 assets
→ capture validation
→ Mode B geometry job
→ evidence-linked canonical draft
→ Designer Studio correction
→ designer confirmation/site verification
→ private exports
→ approved public presentation
```

## Android API usage

The app retains the working Mode A `/v1` API surface. The new combined backend must support the following Mode B behavior on that unified API:

### Capture and room records

- `POST /v1/captures` with `mode = DESIGN_SCAN`
- `POST /v1/captures/{captureId}/rooms`
- `PATCH /v1/captures/{captureId}/rooms/{roomId}` accepting:
  - `floorPolygon`
  - `ceilingHeightM`
  - `measurements`
  - `openings`
  - `roomPlacement`
  - `roomModel`

### Typed assets

The existing signed-upload endpoints must accept:

- `RGB_KEYFRAME`
- `DEPTH_MAP`
- `DEPTH_CONFIDENCE`
- `AR_POSES`
- `AR_PLANES`
- `CAMERA_INTRINSICS`
- `CAPTURE_MANIFEST`
- `MODEL_EVIDENCE`

### Capture validation

- `POST /v1/captures/{captureId}/submit`
- `GET /v1/jobs/{jobId}`
- `GET /v1/captures/{captureId}`

A valid Mode B capture reaches `READY`; missing evidence or measurements produces a correction/recapture status.

### Design project creation

`POST /v1/design-projects` receives:

```json
{
  "captureId": "...",
  "name": "...",
  "model": { "schemaVersion": "2.1" },
  "generateGeometry": true
}
```

The response must support:

```json
{
  "id": "...",
  "slug": "...",
  "status": "DRAFT_MODEL",
  "name": "...",
  "geometryJobId": "...",
  "geometryStatus": "QUEUED",
  "verificationStatus": "DESIGNER_REVIEW_REQUIRED"
}
```

Android waits for the optional geometry job and then hands the project to Designer Studio. It does not call public Mode B publishing.

## Canonical model

- `schemaVersion: 2.1`
- metres
- right-handed Y-up coordinates
- structure, floors, rooms, transforms, polygons, walls and openings
- measurement provenance
- evidence references and confidence
- verification status attached to derived elements

## Trust rules

- Sensor output is always a draft.
- Editing invalidates previous confirmation.
- Publishing is blocked until designer confirmation or site verification.
- Working assets remain private.
- Structural, fabrication, electrical, plumbing and regulatory decisions require qualified verification.
