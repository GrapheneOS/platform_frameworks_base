package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class LockAction {
    public static final int ACTION_REBOOT = 1;
    public static final int ACTION_SHUTDOWN = 2;

    public static final BoolSetting ENABLED_SETTING = new BoolSetting(
            Setting.Scope.GLOBAL, Settings.Global.LOCK_ACTION_ENABLED, false);

    public static final IntSetting ACTION_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.LOCK_ACTION_TYPE, ACTION_REBOOT,
            ACTION_REBOOT, ACTION_SHUTDOWN);
}