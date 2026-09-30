# Cold file reads with low free swap

This native gtest measures cold and warm mmap scans of fresh files before filling
swap, while holding anonymous allocations, and after releasing them. It requests
pageout of its own allocations to reach low free swap. Cold samples require an
uncached file and read accounting covering its full size.
Pixel kernel policy values are logged when `su` access is available; they are optional.

Run on a test device with swap enabled:

```sh
atest ColdFileReadBenchmark
```

The free swap cutoff defaults to 20 MiB. Override it with a host test option:

```sh
atest ColdFileReadBenchmark -- \
    --module-arg 'ColdFileReadBenchmark:set-option:swap-cutoff-mib:32'
```

The same syntax accepts `file-mib` (default 128), `rounds` (3),
`max-allocation-mib` (0 selects an automatic budget), and `timeout-seconds` (300).
The test creates memory pressure and fails if it cannot obtain valid scans below
the cutoff within these limits. Passing means the workload was valid; timings
are reported without a speed requirement.

Summary metrics group cold reads by observed free swap, with a warm read control.
They appear in the atest terminal output and logcat under
`ColdFileReadBenchmark`. Detailed scan records also appear in logcat and are
saved with the test results.

Each scan records thread CPU time and voluntary/involuntary context switches,
with medians in the summary. Scheduling policy, priority, CPU affinity, and
cgroup membership are logged before each scan when available. These diagnostics
help distinguish CPU work from waits, but do not identify I/O waits.

For comparisons, match file size, cutoff, allocation settings, screen and
charging state, and background workload. Let the device cool between runs and
record the kernel and policy being tested. A visible app and an adb shell
process can have different scheduling context.
