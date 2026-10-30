package com.android.server.permission.access.permission

/** Transforms the actual INTERNET runtime grant; permission checking remains unchanged. */
object UserNetworkPermissionPolicy {
    private const val BACKUP = PermissionFlags.NETWORK_BLOCKED or
        PermissionFlags.NETWORK_PREVIOUSLY_GRANTED
    private const val UNSUPPORTED_GRANTS = PermissionFlags.INSTALL_GRANTED or
        PermissionFlags.PROTECTION_GRANTED or PermissionFlags.LEGACY_GRANTED or
        PermissionFlags.IMPLICIT_GRANTED

    fun update(
        oldFlags: Int,
        requestedFlags: Int,
        blocked: Boolean,
        initializeInstallGrant: Boolean = false,
    ): Int {
        if (blocked) {
            check(requestedFlags and UNSUPPORTED_GRANTS == 0) {
                "INTERNET must use GrapheneOS runtime grant semantics"
            }
            val saved = if (!initializeInstallGrant &&
                oldFlags and PermissionFlags.NETWORK_BLOCKED != 0) {
                oldFlags and PermissionFlags.NETWORK_PREVIOUSLY_GRANTED
            } else if (requestedFlags and PermissionFlags.RUNTIME_GRANTED != 0) {
                PermissionFlags.NETWORK_PREVIOUSLY_GRANTED
            } else {
                0
            }
            return (requestedFlags and (BACKUP or PermissionFlags.RUNTIME_GRANTED).inv()) or
                PermissionFlags.NETWORK_BLOCKED or saved
        }
        if (oldFlags and PermissionFlags.NETWORK_BLOCKED == 0) return requestedFlags
        val restored = if (oldFlags and PermissionFlags.NETWORK_PREVIOUSLY_GRANTED != 0) {
            PermissionFlags.RUNTIME_GRANTED
        } else {
            0
        }
        return (requestedFlags and (BACKUP or PermissionFlags.RUNTIME_GRANTED).inv()) or restored
    }
}
