# GosCompatSecureSpawnTests

This module verifies the app process startup path selected by secure app spawning and by app
compatibility flags that require exec spawning.

The package under test is the non-debuggable `GosCompatSecureSpawnApp`, not `GosCompatCheckApp`. 
`GosCompatCheckApp` is intentionally debuggable because the existing GosCompat modules use 
`run-as app.grapheneos.goscompat.checks` to read result files from its app data directory. Making
that shared helper non-debuggable would require reworking those result collection paths or splitting
the existing modules first, which is more churn than this regression test needs.

Run the module directly with:

```sh
atest GosCompatSecureSpawnTests
```
