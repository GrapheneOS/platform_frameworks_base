package com.android.internal.os;

/**
 * @hide
 */
public enum ZygoteType {
    Primary("zygote", "usap_pool_primary"),
    Secondary("zygote_secondary", "usap_pool_secondary");

    private final String socketName;
    private final String usapPoolSocketName;

    ZygoteType(String socketName, String usapPoolSocketName) {
        this.socketName = socketName;
        this.usapPoolSocketName = usapPoolSocketName;
    }

    public String getSocketName() {
        return socketName;
    }

    public String getUsapPoolSocketName() {
        return usapPoolSocketName;
    }

    public static ZygoteType fromSocketName(String socketName) {
        for (var t : ZygoteType.values()) {
            if (socketName.equals(t.socketName)) {
                return t;
            }
        }
        throw new IllegalArgumentException(socketName);
    }
}
