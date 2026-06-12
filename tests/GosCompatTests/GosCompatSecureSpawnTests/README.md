# GosCompatSecureSpawnTests

This module verifies the app process startup path selected by secure app spawning and by app
compatibility flags that require exec spawning.

The package under test is `GosCompatSecureSpawnApp`, not `GosCompatCheckApp`. Exec spawning is
disabled for packages with `ApplicationInfo.FLAG_DEBUGGABLE`, and `GosCompatCheckApp` is intentionally
debuggable because the existing GosCompat modules use `run-as app.grapheneos.goscompat.checks` to read
result files from its app data directory. Making that shared helper non-debuggable would require
reworking those result collection paths or splitting the existing modules first, which is more churn
than this regression test needs.

When `adb root` is available, then tests will set the secure app spawning to the needed state for
those tests and reboot. After those tests finish, the test will reset to the original secure app 
spawning state and do another reboot. If `adb root` is not available, then the secure app spawning 
prop cannot be set by shell, so the tests will fail the assumption. If secure app spawning state is
already set to the state needed for the test, no prop changes + reboot is needed and tests will just
run.

Run the module directly with:

```sh
atest GosCompatSecureSpawnTests
```
