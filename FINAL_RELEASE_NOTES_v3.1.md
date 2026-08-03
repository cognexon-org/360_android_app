# PropertyTour360 Android v3.1.0 — latest Mode A rebase

This package replaces the earlier v3.1 ZIP.

## Rebase correction

The application is now based directly on the user-supplied latest working Android repository. Mode A's current capture and upload implementation is preserved rather than inherited from an older snapshot.

## Mode A retained

- dense-overlap v2.2 capture tuning
- tighter alignment tolerances
- exposure and white-balance locking
- current panorama manifest asset contract
- current pose-aware stitch payload
- existing import, QA, room graph and publishing flows

## Mode B included

- synchronized RGB-D Capture Package v2.1
- centre-hit structural markups
- scan completion guidance
- rectangle, L-shape and free-polygon field plans
- multiple wall-attached openings
- measurement method, tolerance, endpoints and evidence provenance
- floor, elevation, origin, rotation and connection anchors
- checksum verification before typed upload
- canonical model schema 2.1
- draft-only Designer Studio handoff

## Backend direction

Mode B targets the new combined Mode A + Mode B backend that will follow this release. No legacy Mode B compatibility branch is included.
