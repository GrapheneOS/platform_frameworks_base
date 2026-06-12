package android.os;

/** @hide */
public enum ZygoteSelectionMode {
    Regular,
    // Use the compat zygote if the command is addressed to 64-bit zygote. Compat zygote uses
    // scudo instead of hardened_malloc
    PreferCompatZygote,
}
