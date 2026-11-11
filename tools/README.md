# LocalVision offline bench

The JVM test decodes the 12 PNG files under `fixtures/blhx/`, reads their
expected page IDs from `fixtures/blhx/manifest.md`, and runs `PageDetector`
without an Android device or network access.

Run it from the repository root (choose either Android product flavor):

```bash
./gradlew :app:testSideloadDebugUnitTest \
  --tests com.axiomlabs.hermesrelay.localvision.LocalVisionBenchTest
```

The console contains only the summary JSON line and any mismatched frame JSON
lines. The complete JSON-lines report (all frames followed by the summary) is
written to `build/localvision-bench.json` relative to the repository root.

The test fails unless all 12 manifest entries match and also enforces the API
contract's median `< 60 ms` and P99 `< 300 ms` budgets.
