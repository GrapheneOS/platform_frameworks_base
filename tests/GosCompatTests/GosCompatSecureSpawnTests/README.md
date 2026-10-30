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

The six tests in `SecureSpawnProcessStatusHostTest` each run both secure spawning modes: regular app,
managed isolated service, native isolated service, managed app zygote child, native app zygote
child, and WebView renderer. Each launch reads the child's `/proc/<pid>/status` once through
UiAutomation as shell and reuses it for explicit assertions and the broad comparison;
`/proc/self/status` in a shell command would describe the shell utility instead of the target.

Add assertions in `SecureSpawnDeviceTest#checkProcessStatus` with
`status.check("FieldName", value -> ...)`. The reader trims field values and preserves empty
fields such as `Groups:`. `FailureCollector` preserves every failed field assertion, including
missing fields, parsing failures and any later uncaught exception. Device instrumentation returns
separate summary and diagnostic results to the host. The host publishes failures from both modes
and the broad diff in the `secure_spawn_failures` test metric and attached text report. Original
exception traces are saved separately in `secure_spawn_failure_diagnostics`. Since atest only
prints test metrics for passing tests, the JUnit failure also contains the compact summary, without
exception traces. Reporting runs after teardown and before metrics are forwarded. A failure in
one mode does not prevent attempting the other. When a fresh snapshot is unavailable, the capture
failure is reported and the diff is skipped; previous instrumentation results are never reused.
Use `status.expect`, `status.expectIntegers` or `status.expectBits` for field comparisons: the
report shows only the compared values, labeled `expected` and `actual`. Signal values
are masked to the named bits. Entire proc status snapshots are not included. Parent inspection
failures are collected too. Service startup and renderer discovery remain prerequisites: there
is no process to inspect if those fail.

The signal assertions inspect the main thread's mask and the process's signal dispositions.
They check inherited signal state directly, rather than testing delivery by terminating the
service. They do not assert that every thread retains the main thread's mask.

The six `SecureSpawnProcessStatusHostTest` methods each capture the
same process type with exec spawning forced off and then on. They compare all status fields
except identities and ancestry, transient state, memory and thread measurements, pending signals,
context-switch counters, and scheduler-assigned CPU/NUMA masks. Unknown fields are compared by
default. The shared `StatusComparison` class lists the exclusions.

The enabled and disabled host classes inherit their separate control cases from
`SecureSpawnModeHostTestBase`. The common fixture base has no test methods, so those cases do not
run a third time in the process status class. Package-state setup is shared and applies the
selected spawning mode in one command.

Isolated UID/GID tuples are normalized against the process's real UID only when it belongs to the
isolated app ID range. The user ID and differences among real, effective, saved and filesystem
identities remain visible. Supplementary groups are sorted, not normalized away. Signal masks,
capability sets, seccomp mode and filter count, NoNewPrivs, umask and other stable fields are
compared in full.
Only differing fields appear in the comparison report, labeled `exec spawning disabled` and
`exec spawning enabled`. Expected-value checks have separate headings for each mode. The device publishes its snapshot before policy assertions, parent inspection and syscall
probes, so a later failure does not discard it or prevent the broad comparison. Snapshot
normalization failures are collected without stopping explicit assertions. Expected values and
syscall behavior are still checked independently: matching incorrect states in both modes fail
those checks even when the broad comparison produces no diff.
