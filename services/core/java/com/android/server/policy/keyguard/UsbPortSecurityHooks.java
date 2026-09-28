package com.android.server.policy.keyguard;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.ext.settings.UsbPortSecurity;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.ext.SystemErrorNotification;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public class UsbPortSecurityHooks {
    private static final String TAG = UsbPortSecurityHooks.class.getSimpleName();
    @Nullable
    private static UsbPortSecurityHooks INSTANCE;

    private final Context context;
    private final Handler handler;
    private final UsbManager usbManager;

    private UsbPortSecurityHooks(Context ctx) {
        this.context = ctx;
        // use a dedicated thread to guarantee that the callbacks do not stall
        var ht = new HandlerThread(TAG);
        ht.start();
        this.handler = ht.getThreadHandler();
        this.usbManager = Objects.requireNonNull(ctx.getSystemService(UsbManager.class));
    }

    private static volatile int isSupportedCached;

    public static boolean isSupported() {
        return isSupported(ActivityThread.currentSystemContext());
    }

    public static boolean isSupported(Context ctx) {
        int cache = isSupportedCached;
        if (cache != 0) {
            return cache > 0;
        }

        boolean res = ctx.getResources().getBoolean(R.bool.config_usbPortSecuritySupported);
        isSupportedCached = res ? 1 : -1;
        return res;
    }

    public static void setInitialMode(Context ctx) {
        if (!isSupported(ctx)) {
            return;
        }

        int initialMode = UsbPortSecurity.MODE_SETTING.get();
        Slogf.d(TAG, "initial value of persist.security.usb_mode: %d", initialMode);

        switch (initialMode) {
            case UsbPortSecurity.MODE_CHARGING_ONLY:
            case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED:
                setSecurityStateForAllPortsInner(ctx, PortSecurityState.CHARGING_ONLY_IMMEDIATE);
                break;
            case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU:
            case UsbPortSecurity.MODE_ALL_PORTS_ENABLED:
                setSecurityStateForAllPortsInner(ctx, PortSecurityState.PORTS_ENABLED);
                break;
        }
    }

    public static void init(Context ctx) {
        if (!isSupported(ctx)) {
            return;
        }

        var i = new UsbPortSecurityHooks(ctx);

        synchronized (pendingCallbacks) {
            INSTANCE = i;
            for (Runnable cb : pendingCallbacks) {
                Slog.d(TAG, "init: enqueued a pending callback");
                i.handler.post(cb);
            }
            pendingCallbacks.clear();
        }
        i.registerPortChangeReceiver();
    }

    void registerPortChangeReceiver() {
        var receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Slog.d(TAG, "PortChangeReceiver: " + intent + ", extras " + intent.getExtras().deepCopy());
                UsbPortStatus portStatus = intent.getParcelableExtra(UsbManager.EXTRA_PORT_STATUS,
                        UsbPortStatus.class);
                if (portStatus.isConnected()) {
                    ++usbConnectEventCount;
                    Slog.d(TAG, "usbConnectEventCount: " + usbConnectEventCount);
                } else {
                    if (keyguardDismissedAtLeastOnce && prevKeyguardShowing != null && prevKeyguardShowing.booleanValue()) {
                        int setting = UsbPortSecurity.MODE_SETTING.get();
                        if (setting == UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU || setting == UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED) {
                            if (!isAnyUsbPortConnected()) {
                                Slog.d(TAG, "keyguard is showing and there's no longer any connected USB devices, issuing the CHARGING_ONLY_IMMEDIATE command");
                                // In some cases, the CHARGING_ONLY_IMMEDIATE command provides extra
                                // protections compared to the CHARGING_ONLY command that is issued
                                // when the device becomes locked
                                setSecurityStateForAllPorts(PortSecurityState.CHARGING_ONLY_IMMEDIATE);
                            }
                        }
                    }
                }
            }
        };
        var filter = new IntentFilter(UsbManager.ACTION_USB_PORT_CHANGED);
        context.registerReceiver(receiver, filter, null, handler);
    }

    private ArraySet<String> halEnabledPorts = new ArraySet<>();
    private ArraySet<String> halDisabledPorts = new ArraySet<>();

    // implementation of the standard android.hardware.usb.IUsb.enableUsbDataSignal() API
    public static boolean onHalEnableUsbDataSignal(String portName, boolean enable,
            android.hardware.usb.IUsbOperationInternal callback) {
        if (!isSupported()) {
            return false;
        }
        Slog.d(TAG, "onHalEnableUsbDataSignal: portName: " + portName + ", enable: " + enable);

        if (INSTANCE == null) {
            throw new IllegalStateException("UsbPortSecurityHooks is not initialized");
        }

        INSTANCE.handler.post(() -> {
            INSTANCE.onHalEnableUsbDataSignalInner(portName, enable);
            try {
                callback.onOperationComplete(android.hardware.usb.UsbOperationInternal.USB_OPERATION_SUCCESS);
            } catch (RemoteException e) {
                Slog.w(TAG, "onHalEnableUsbDataSignal", e);
            }
        });
        return true;
    }

    public void onHalEnableUsbDataSignalInner(String portName, boolean enable) {
        Slog.d(TAG, "onHalEnableUsbDataSignalInner: portName: " + portName + ", enable: " + enable);

        if (enable) {
            if (!halDisabledPorts.remove(portName)) {
                Slog.d(TAG, "port not found in halDisabledPorts");
            }
            if (!halEnabledPorts.add(portName)) {
                Slog.d(TAG, "port already in halEnabledPorts");
            }
        } else {
            if (!halEnabledPorts.remove(portName)) {
                Slog.d(TAG, "port not found in halEnabledPorts");
            }
            if (!halDisabledPorts.add(portName)) {
                Slog.d(TAG, "port already in halDisabledPorts");
            }
        }

        Slog.d(TAG, "halDisabledPorts: " + Arrays.toString(halDisabledPorts.toArray()) + ", halEnabledPorts: " + Arrays.toString(halEnabledPorts.toArray()));

        int setting = UsbPortSecurity.MODE_SETTING.get();

        if (!halDisabledPorts.isEmpty()) {
            switch (setting) {
                case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED:
                case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU:
                case UsbPortSecurity.MODE_ALL_PORTS_ENABLED:
                    setSecurityStateForAllPorts(PortSecurityState.CHARGING_ONLY_IMMEDIATE);
                    break;
            }
        } else {
            switch (setting) {
                case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED:
                case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU:
                    if (prevKeyguardShowing != null && !prevKeyguardShowing.booleanValue()) {
                        setSecurityStateForAllPorts(PortSecurityState.PORTS_ENABLED);
                    }
                    break;
                case UsbPortSecurity.MODE_ALL_PORTS_ENABLED:
                    setSecurityStateForAllPorts(PortSecurityState.PORTS_ENABLED);
                    break;
            }
        }
    }

    private static final ArrayList<Runnable> pendingCallbacks = new ArrayList<>();

    public static void onKeyguardShowingStateChanged(Context ctx, boolean showing, int userId) {
        if (!isSupported(ctx)) {
            return;
        }

        UsbPortSecurityHooks instance;
        synchronized (pendingCallbacks) {
            instance = INSTANCE;
            if (instance == null) {
                // UsbService hasn't completed initialization yet, delay the callback until then
                Slog.d(TAG, "onKeyguardShowingStateChanged: adding pending callback: showing: " + showing + " userId " + userId);
                pendingCallbacks.add(() -> onKeyguardShowingStateChanged(ctx, showing, userId));
                return;
            }
        }

        instance.handler.post(() -> instance.onKeyguardShowingStateChangedInner(ctx, showing, userId));
    }

    private boolean keyguardDismissedAtLeastOnce;
    private Boolean prevKeyguardShowing; // intentionally using boxed boolean to have a null value
    private long keyguardShowingChangeCount;

    private int usbConnectEventCountBeforeLocked;
    private int usbConnectEventCount;

    void onKeyguardShowingStateChangedInner(Context ctx, boolean showing, int userId) {
        int setting = UsbPortSecurity.MODE_SETTING.get();

        Slog.d(TAG, "onKeyguardShowingStateChanged, showing " + showing + ", userId " + userId
                + ", modeSetting " + setting);

        Boolean showingB = Boolean.valueOf(showing);
        if (prevKeyguardShowing == showingB) {
            Slog.d(TAG, "onKeyguardShowingStateChangedInner: duplicate callback, ignoring");
            return;
        }
        prevKeyguardShowing = showingB;
        ++keyguardShowingChangeCount;

        if (setting == UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED
              || (keyguardDismissedAtLeastOnce && setting == UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU))
        {
            if (showing) {
                setSecurityStateForAllPorts(PortSecurityState.CHARGING_ONLY);
                usbConnectEventCountBeforeLocked = usbConnectEventCount;
            } else {
                boolean forceReconnect = false;
                if (!keyguardDismissedAtLeastOnce) {
                    for (UsbPort port : usbManager.getPorts()) {
                        UsbPortStatus s = port.getStatus();
                        if (s == null || s.isConnected()) {
                            // at boot-time, "port connected" event might not be delivered if the
                            // event fires before UsbService is initialized, which breaks the
                            // usbConnectEventCountBeforeLocked check below
                            forceReconnect = true;
                            break;
                        }
                    }
                }

                if (!forceReconnect && usbConnectEventCountBeforeLocked == usbConnectEventCount) {
                    setSecurityStateForAllPorts(PortSecurityState.PORTS_ENABLED);
                } else {
                    // Turn USB ports off and on to trigger reconnection of devices that were connected
                    // in charging-only state. Simply enabling the data path is not enough in some
                    // advanced scenarios, e.g. when port alt mode or port role switching are used.
                    Slog.d(TAG, "toggling USB ports");
                    setSecurityStateForAllPorts(PortSecurityState.PORTS_DISABLED);
                    final long curShowingChangeCount = keyguardShowingChangeCount;
                    final long delayMs = 1500;
                    handler.postDelayed(() -> {
                        if (keyguardShowingChangeCount == curShowingChangeCount) {
                            setSecurityStateForAllPorts(PortSecurityState.PORTS_ENABLED);
                        } else {
                            Slog.d(TAG, "showingChangeCount changed, skipping delayed enable");
                        }
                    }, delayMs);
                }
            }
        }

        if (userId == UserHandle.USER_SYSTEM && !showing) {
            keyguardDismissedAtLeastOnce = true;
        }
    }

    private interface PortSecurityState {
        // disable all ports
        String PORTS_DISABLED = "ports_disabled";
        // immediately disables USB data path and disables alt modes on subsequent connections
        String CHARGING_ONLY_IMMEDIATE = "charging-only_immediate";
        // applies after port disconnect if it's currently connected
        String CHARGING_ONLY = "charging-only";
        String PORTS_ENABLED = "ports_enabled";
    }

    private void setSecurityStateForAllPorts(String state) {
        if (!handler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("setSecurityStateForAllPorts() must be called on the handler thread");
        }

        if (PortSecurityState.PORTS_ENABLED.equals(state) && !halDisabledPorts.isEmpty()) {
            Slogf.d(TAG, "setSecurityStateForAllPorts: ignoring enable request since halDisabledPorts is %s", Arrays.toString(halDisabledPorts.toArray()));
            return;
        }

        setSecurityStateForAllPortsInner(context, state);
    }

    private static void setSecurityStateForAllPortsInner(Context ctx, String state) {
        Slog.d(TAG, "setSecurityStateForAllPorts: " + state);

        setDenyNewUsb2(ctx, !state.equals(PortSecurityState.PORTS_ENABLED));

        try {
            SystemProperties.set("sys.port_security_mode", state);
        } catch (RuntimeException e) {
            showErrorNotif(ctx, Log.getStackTraceString(e));
        }
    }

    private static void setDenyNewUsb2(Context ctx, boolean enabled) {
        String prop = "security.deny_new_usb2";
        String val = enabled ? "1" : "0";
        try {
            SystemProperties.set(prop, val);
            Slog.d(TAG, "set " + prop + " to " + val);
        } catch (RuntimeException e) {
            String msg = "unable to set " + prop + " to " + val + ":\n" + Log.getStackTraceString(e);
            showErrorNotif(ctx, msg);
        }
    }

    public static void updateSetting(int newValue) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.SHELL_UID) {
            throw new SecurityException("only system and shell are allowed to call updatePortSecuritySetting()");
        }

        Slogf.d(TAG, "updateSetting: %d", newValue);

        UsbPortSecurityHooks instance = INSTANCE;
        if (instance == null) {
            throw new IllegalStateException("no UsbPortSecurityHooks instance");
        }
        instance.handler.post(() -> instance.updateSettingInner(newValue));
    }

    private void updateSettingInner(int newValue) {
        if (!Boolean.FALSE.equals(prevKeyguardShowing)) {
            // not strictly necessary, but allows to simplify the logic in code that changes port
            // security state below
            Slog.e(TAG, "keyguard has to be dismissed before calling updateSetting()");
            return;
        }

        int prevValue = UsbPortSecurity.MODE_SETTING.get();

        UsbPortSecurity.MODE_SETTING.put(newValue);

        boolean delayStateUpdate = false;

        if (prevValue == UsbPortSecurity.MODE_CHARGING_ONLY && newValue >= UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED) {
            // Turn USB ports off first to trigger reconnection of devices that were connected
            // in charging-only state. Simply enabling the data path is not enough in some
            // advanced scenarios, e.g. when port alt mode or port role switching are used.
            setSecurityStateForAllPorts(PortSecurityState.PORTS_DISABLED);
            delayStateUpdate = true;
        }

        String state = switch (newValue) {
            case UsbPortSecurity.MODE_ALL_PORTS_DISABLED ->
                    PortSecurityState.PORTS_DISABLED;
            case UsbPortSecurity.MODE_CHARGING_ONLY ->
                    PortSecurityState.CHARGING_ONLY_IMMEDIATE;
            case UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED,
                 UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU,
                 UsbPortSecurity.MODE_ALL_PORTS_ENABLED ->
                    PortSecurityState.PORTS_ENABLED;
            default -> throw new IllegalArgumentException(Integer.toString(newValue));
        };

        if (delayStateUpdate) {
            final long curShowingChangeCount = keyguardShowingChangeCount;
            // it's hard to setup a proper callback to avoid this hardcoded delay, would need to
            // modify init and kernel
            final long delayMs = 1500;
            handler.postDelayed(() -> {
                if (keyguardShowingChangeCount == curShowingChangeCount) {
                    setSecurityStateForAllPorts(state);
                } else {
                    Slog.d(TAG, "updateSetting: showingChangeCount changed, skipping delayed state change");
                }
            }, delayMs);
        } else {
            setSecurityStateForAllPorts(state);
        }
    }

    private static void showErrorNotif(Context context, String msg) {
        String type = "error in USB-C port security feature";
        String title = context.getString(R.string.usb_port_security_error_title);
        new SystemErrorNotification(type, title, msg).show(context);
    }

    private boolean isAnyUsbPortConnected() {
        for (UsbPort port : usbManager.getPorts()) {
            UsbPortStatus s = port.getStatus();
            if (s != null && s.isConnected()) {
                return true;
            }
        }
        return false;
    }
}
