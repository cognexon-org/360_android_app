# Release validation — Android v3.1.0

## Passed

- Mode A preservation: **6/6**
- Mode A + Mode B source-contract checks: **51/51**
- Canonical model standalone Kotlin compilation: **passed**
- L-shaped canonical model runtime smoke test: **passed**
- Kotlin grammar scan: **no syntax errors detected**
- Secret/cache check: no `.env` or `local.properties`

## Mode A verification

The current panorama camera files are byte-identical to the user-supplied
working app. The Mode A repository stitch function, ViewModel workflow and
Compose workspace are text-identical.

## Build limitation

A complete Android/Gradle build was not possible in this environment because:

1. the supplied repository does not include `gradle-wrapper.jar`; and
2. this environment cannot download the wrapper JAR or Android dependencies.

This release therefore does **not** claim a signed APK or full device build.
Before field deployment, run:

```bash
./gradlew clean test assembleDebug
```

Then test both capture modes on:

- one ordinary Android camera phone
- one ARCore phone without useful Depth
- one ARCore Depth-capable phone

## Backend integration

Mode A keeps the supplied working contract. Mode B targets the new combined
backend defined in `MODE_B_V2_INTEGRATION_CONTRACT.md`, which is the next
repository to be generated.
