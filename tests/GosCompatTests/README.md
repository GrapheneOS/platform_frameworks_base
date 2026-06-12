# GosCompatTests

These tests are generally shapped as non-privileged apps in order to regression test compatability
with patterns or code used in actual apps.

`GosCompatCheckApp` is a standalone helper app with a manual UI. See subdirectories for descriptions
of each test.

You can run tests from the checkout root via this directory's `TEST_MAPPING`:

```sh
atest --test-mapping frameworks/base/tests/GosCompatTests:gos_postsubmit
```

Generally, the device should be unlocked and on user 0 while the tests are running. Most tests can
be run on user builds both via `atest` and using the standalone UI in `GosCompatCheckApp`. Host
modules that mutate persistent device or package state can require adb root.

Alternatively, view the `TEST_MAPPING` file or Android.bp files and run test modules directly with
`atest`.
