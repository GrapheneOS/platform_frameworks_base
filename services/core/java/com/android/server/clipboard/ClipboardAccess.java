package com.android.server.clipboard;

import static android.content.Context.DEVICE_ID_INVALID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.UidObserver;
import android.content.ClipDescription;
import android.content.Context;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.settings.app.AswAllowClipboardRead;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.clipboard.ClipboardService.Clipboard;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;

/**
 * Applies the per-app clipboard read setting and tracks trusted paste authorization grants.
 *
 * <p>Each grant is bound to a UID, resolved clipboard device, and primary clip generation. A grant
 * initially allows time for asynchronous paste dispatch. Its first non-null primary clip read
 * starts a fixed read window which later reads do not extend. A new grant replaces the previous
 * grant for the UID. Grants are invalidated by expiry, a clip change, or UID exit.</p>
 *
 * <p>Grant records share {@link ClipboardService}'s lock so checking a grant and selecting the
 * corresponding clip are atomic. Trusted routes validate the exact current target before creating
 * a grant, while the resulting authorization is deliberately UID scoped. Every read still passes
 * the clipboard API's package identity, focus, AppOps, and device lock checks.</p>
 */
final class ClipboardAccess {
    private static final String TAG = "ClipboardAccess";

    // Callback return does not mean the toolkit has read the clipboard. Allow one normal
    // unmultiplied input dispatch timeout for asynchronous delivery while bounding abandoned work.
    private static final long PASTE_GRANT_DISPATCH_TIMEOUT_MILLIS = 5000L;

    // Clipboard clients such as Chromium-based browsers may read several representations. The
    // first payload read starts this fixed window; later reads must not renew it.
    private static final long PASTE_GRANT_READ_WINDOW_MILLIS = 1000L;

    private final Context mContext;
    private final ClipboardService mService;
    private final PackageManagerInternal mPmi;

    // Lock must be the one used by ClipboardService
    private final Object mLock;

    @GuardedBy("mLock")
    // One record per UID. Matching reads remove expired entries, while replacement and UID exit
    // bound retention without per-grant delayed cleanup tasks.
    private final SparseArray<PasteGrantRecord> mPasteGrantsByUid = new SparseArray<>();

    ClipboardAccess(@NonNull Context context, @NonNull ClipboardService service,
            @NonNull Object lock) {
        mContext = context;
        mService = service;
        mLock = lock;
        mPmi = LocalServices.getService(PackageManagerInternal.class);
        registerUidObserver();
    }

    void createPasteGrantForDevice(int intendingUid, int requestedDeviceId) {
        final int intendingUserId = UserHandle.getUserId(intendingUid);
        final int intendingDeviceId = mService.getIntendingDeviceId(
                requestedDeviceId, intendingUid);
        if (intendingDeviceId == DEVICE_ID_INVALID) {
            Slog.i(TAG, "createPasteGrantForDevice: invalid deviceId for uid:" + intendingUid
                    + " deviceId:" + requestedDeviceId);
            return;
        }

        synchronized (mLock) {
            final Clipboard clipboard = mService.getClipboardLocked(intendingUserId,
                    intendingDeviceId);
            if (clipboard == null) {
                return;
            }

            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final PasteGrantRecord record = new PasteGrantRecord(intendingDeviceId,
                    clipboard.primaryClipGeneration,
                    elapsedRealtime + PASTE_GRANT_DISPATCH_TIMEOUT_MILLIS);
            mPasteGrantsByUid.put(intendingUid, record);
        }
    }

    private void removePasteGrantForUid(int intendingUid) {
        synchronized (mLock) {
            mPasteGrantsByUid.remove(intendingUid);
        }
    }

    /**
     * Source of authorization for a payload read. The service distinguishes {@link #PASTE_GRANT}
     * so only a non-null payload returned through a temporary grant starts its fixed read window.
     */
    enum PayloadReadAccess {
        /** No clipboard ownership, persistent setting, or temporary paste grant allows the read. */
        DENIED,

        /** The requesting UID set the current primary clip. */
        CLIP_OWNER,

        /** The persistent per-app clipboard read setting allows the read. */
        PERSISTENT_APP_SETTING,

        /** A temporary grant created for a trusted user paste action allows the read. */
        PASTE_GRANT,
    }

    @GuardedBy("mLock")
    PayloadReadAccess getPayloadReadAccessLocked(boolean readAllowedForPackage, int intendingUid,
            int intendingUserId, int intendingDeviceId) {
        final Clipboard clipboard = mService.getClipboardLocked(intendingUserId,
                intendingDeviceId);
        if (clipboard != null && clipboard.primaryClip != null
                && clipboard.primaryClipUid == intendingUid) {
            return PayloadReadAccess.CLIP_OWNER;
        }
        if (clipboardReadAllowedByPasteGrantLocked(
                intendingUid, intendingUserId, intendingDeviceId)) {
            return PayloadReadAccess.PASTE_GRANT;
        }
        return readAllowedForPackage
                ? PayloadReadAccess.PERSISTENT_APP_SETTING
                : PayloadReadAccess.DENIED;
    }

    @GuardedBy("mLock")
    private boolean clipboardReadAllowedByPasteGrantLocked(int intendingUid, int intendingUserId,
            int intendingDeviceId) {
        final PasteGrantRecord record = mPasteGrantsByUid.get(intendingUid);
        // A UID can access clipboard silos on different virtual devices. Looking at another silo
        // must not consume the grant for the device on which Paste was authorized.
        if (record == null || !record.isForDevice(intendingDeviceId)) {
            return false;
        }

        final Clipboard clipboard = mService.getClipboardLocked(intendingUserId,
                intendingDeviceId);
        if (clipboard == null
                || !record.isValidFor(clipboard, SystemClock.elapsedRealtime())) {
            mPasteGrantsByUid.remove(intendingUid);
            return false;
        }
        return true;
    }

    /**
     * Starts the fixed read window after a non-null payload read accepted earlier under
     * {@code mLock}. Synchronous work may cross the pending deadline, so the record and clip
     * identity are rechecked, but the deadline is not. Metadata-only reads do not start the window.
     */
    @GuardedBy("mLock")
    void activatePasteGrantOnPrimaryClipReadLocked(int intendingUid, @NonNull Clipboard clipboard) {
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final PasteGrantRecord record = mPasteGrantsByUid.get(intendingUid);
        if (record != null && record.isForClipboard(clipboard)) {
            record.startReadWindow(elapsedRealtime);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    ClipDescription getPrimaryClipDescriptionLocked(boolean readAllowedForPackage,
            int intendingUid, int intendingUserId, int intendingDeviceId) {
        final PayloadReadAccess access = getPayloadReadAccessLocked(readAllowedForPackage,
                intendingUid, intendingUserId, intendingDeviceId);
        final Clipboard clipboard = mService.getClipboardLocked(intendingUserId,
                intendingDeviceId);
        final ClipDescription description = clipboard != null && clipboard.primaryClip != null
                ? clipboard.primaryClip.getDescription() : null;
        if (description == null || access != PayloadReadAccess.DENIED) {
            return description;
        }

        final String[] mimeTypes = new String[description.getMimeTypeCount()];
        for (int i = 0; i < mimeTypes.length; i++) {
            mimeTypes[i] = description.getMimeType(i);
        }
        final ClipDescription descriptionWithoutLabel = new ClipDescription(null, mimeTypes);
        descriptionWithoutLabel.setTimestamp(description.getTimestamp());
        // TextView.canPasteAsPlainText() uses this bit to offer Paste as plain text.
        descriptionWithoutLabel.setIsStyledText(description.isStyledText());
        return descriptionWithoutLabel;
    }

    @GuardedBy("mLock")
    boolean hasClipboardTextLocked(boolean readAllowedForPackage, int intendingUid,
            int intendingUserId, int intendingDeviceId) {
        final PayloadReadAccess access = getPayloadReadAccessLocked(readAllowedForPackage,
                intendingUid, intendingUserId, intendingDeviceId);
        final Clipboard clipboard = mService.getClipboardLocked(intendingUserId,
                intendingDeviceId);
        if (clipboard == null || clipboard.primaryClip == null) {
            return false;
        }
        if (access == PayloadReadAccess.DENIED) {
            // Some apps use this query to decide whether the system toolbar should offer Paste.
            // Preserve clip presence without inspecting or exposing its content.
            return true;
        }
        final CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
        return text != null && text.length() > 0;
    }

    boolean clipboardReadAllowedForPackage(String packageName, int intendingUid,
            int intendingUserId, boolean isDefaultIme) {
        return getClipboardReadPolicyForPackage(packageName, intendingUid, intendingUserId,
                isDefaultIme).readAllowed;
    }

    ClipboardReadPolicy getClipboardReadPolicyForPackage(String packageName, int intendingUid,
            int intendingUserId, boolean isDefaultIme) {
        final PackageStateInternal packageState = mPmi.getPackageStateInternal(packageName);
        if (packageState == null
                || packageState.getAppId() != UserHandle.getAppId(intendingUid)) {
            return ClipboardReadPolicy.DENIED;
        }
        final PackageUserStateInternal userState =
                packageState.getUserStateOrDefault(intendingUserId);
        if (!userState.isInstalled() || userState.isHidden()) {
            return ClipboardReadPolicy.DENIED;
        }
        final GosPackageState gosPackageState = userState.getGosPackageState();
        final AswAllowClipboardRead appSwitch = AswAllowClipboardRead.I;
        final boolean readAllowed = appSwitch.get(mContext, intendingUserId,
                packageState.isSystem(), isDefaultIme, gosPackageState);
        return new ClipboardReadPolicy(readAllowed,
                !readAllowed && appSwitch.isNotificationEnabled(gosPackageState));
    }

    static final class ClipboardReadPolicy {
        static final ClipboardReadPolicy DENIED = new ClipboardReadPolicy(false, false);

        final boolean readAllowed;
        final boolean showAccessDeniedNotification;

        ClipboardReadPolicy(boolean readAllowed, boolean showAccessDeniedNotification) {
            this.readAllowed = readAllowed;
            this.showAccessDeniedNotification = showAccessDeniedNotification;
        }
    }

    private void registerUidObserver() {
        try {
            ActivityManager.getService().registerUidObserver(new UidObserver() {
                @Override
                public void onUidGone(int uid, boolean disabled) {
                    removePasteGrantForUid(uid);
                }
            }, ActivityManager.UID_OBSERVER_GONE, ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }
    }

    private static final class PasteGrantRecord {
        private final int mDeviceId;
        private final int mClipGeneration;

        private long mExpiryElapsedRealtime;

        private boolean mReadWindowStarted;

        private PasteGrantRecord(int deviceId, int clipGeneration, long expiryElapsedRealtime) {
            mDeviceId = deviceId;
            mClipGeneration = clipGeneration;
            mExpiryElapsedRealtime = expiryElapsedRealtime;
        }

        private boolean isValidFor(@NonNull Clipboard clipboard, long elapsedRealtime) {
            return isForClipboard(clipboard)
                    && mExpiryElapsedRealtime >= elapsedRealtime;
        }

        private boolean isForClipboard(@NonNull Clipboard clipboard) {
            return isForDevice(clipboard.deviceId)
                    && mClipGeneration == clipboard.primaryClipGeneration;
        }

        private boolean isForDevice(int deviceId) {
            return mDeviceId == deviceId;
        }

        private void startReadWindow(long elapsedRealtime) {
            if (!mReadWindowStarted) {
                mReadWindowStarted = true;
                mExpiryElapsedRealtime = elapsedRealtime + PASTE_GRANT_READ_WINDOW_MILLIS;
            }
        }
    }
}
