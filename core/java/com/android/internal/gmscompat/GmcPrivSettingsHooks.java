package com.android.internal.gmscompat;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.StringParceledListSlice;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.Set;

public class GmcPrivSettingsHooks {
    private static final String TAG = GmcPrivSettingsHooks.class.getSimpleName();

    public static final String DEVICE_CONFIG_NAMESPACE = "config";
    private static ArrayMap<String, ArrayMap<String, ArraySet<ContentObserver>>> registeredContentObservers;

    public static final String ACTION_SETTING_CHANGED = GmcPrivSettingsHooks.class.getName() + ".ACTION_SETTING_CHANGED";
    public static final String EXTRA_SETTING_NAMESPACE = "namespace";
    public static final String EXTRA_SETTING_KEY = "key";

    static void init(Context ctx) {
        registeredContentObservers = new ArrayMap<>();
        ChangeReceiver.register(ctx);
    }

    static class ChangeReceiver extends BroadcastReceiver {

        static void register(Context ctx) {
            var filter = new IntentFilter(ACTION_SETTING_CHANGED);
            String perm = GmsCompatApp.SELF_PERMISSION;

            var instance = new ChangeReceiver();
            ctx.registerReceiver(instance, filter, perm, GmsHooks.getBackgroundHandler(), Context.RECEIVER_EXPORTED);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle args = intent.getExtras();
            Log.d(TAG, "onChange: " + args.toStringDeep());
            String ns = args.getString(EXTRA_SETTING_NAMESPACE);
            String key = args.getString(EXTRA_SETTING_KEY);
            ContentObserver[] observers;
            synchronized (registeredContentObservers) {
                ArrayMap<String, ArraySet<ContentObserver>> nsObservers = registeredContentObservers.get(ns);
                if (nsObservers == null) {
                    return;
                }
                ArraySet<ContentObserver> keyObservers = nsObservers.get(key);
                if (keyObservers == null) {
                    return;
                }
                observers = keyObservers.toArray(new ContentObserver[0]);
            }

            Uri namespaceUri = Uri.parse("content://" + Settings.AUTHORITY + '/' + ns);
            Uri uri = Settings.NameValueTable.getUriFor(namespaceUri, key);
            Uri[] uriArray = { uri };
            int userId = context.getUserId();
            for (ContentObserver o : observers) {
                try {
                    o.getContentObserver().onChangeEtc(false, uriArray, 0, userId);
                } catch (RemoteException e) {
                    // local call
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public static String deviceConfigNamespace(String namespace) {
        // last path component of DeviceConfig.CONTENT_URI
        return DEVICE_CONFIG_NAMESPACE + ':' + namespace;
    }

    public static String getString(String ns, String key) {
        return GmsCompatApp.call(i -> i.privSettingsGetString(ns, key));
    }

    public static boolean putString(String ns, String key, @Nullable String value) {
        return GmsCompatApp.call(i -> i.privSettingsPutString(ns, key, value));
    }

    public static boolean setProperties(DeviceConfig.Properties props) {
        Set<String> keyset = props.getKeyset();
        int numKeys = keyset.size();
        var keys = new ArrayList<String>(numKeys);
        var values = new ArrayList<String>(numKeys);
        String ns = deviceConfigNamespace(props.getNamespace());

        for (String key : keyset) {
            if (key == null) {
                throw new IllegalStateException("null DeviceConfig.Properties key");
            }
            String value = props.getString(key, null);
            if (value == null) {
                // null is the default value
                continue;
            }
            keys.add(key);
            values.add(value);
        }

        return GmsCompatApp.call(i -> i.privSettingsPutStrings(ns,
                    new StringParceledListSlice(keys),
                    new StringParceledListSlice(values)));
    }

    public static boolean maybeRegisterObserver(Uri uri, ContentObserver observer) {
        String s = uri.toString();

        String prefix = "content://settings/";

        if (!s.startsWith(prefix)) {
            return false;
        }

        int nsStart = prefix.length();
        int nsEnd = s.indexOf('/', nsStart);

        if (nsEnd < 0 || nsStart == nsEnd) {
            return false;
        }

        String ns = s.substring(nsStart, nsEnd);
        String key = s.substring(nsEnd + 1);

        switch (ns) {
            // keep in sync with Settings.NameValueCache#maybeGetGmsCompatNamespace
            case "global":
                if (Settings.Global.isKnownKey(key)) {
                    return false;
                }
                break;
            case "secure":
                if (Settings.Secure.isKnownKey(key)) {
                    return false;
                }
                break;
            default:
                return false;
        }

        synchronized (registeredContentObservers) {
            ArrayMap<String, ArraySet<ContentObserver>> nsObservers = registeredContentObservers.get(ns);
            if (nsObservers == null) {
                nsObservers = new ArrayMap<>();
                registeredContentObservers.put(ns, nsObservers);
            }
            ArraySet<ContentObserver> keyObservers = nsObservers.get(key);
            if (keyObservers == null) {
                keyObservers = new ArraySet<>();
                nsObservers.put(key, keyObservers);
            }
            keyObservers.add(observer);
        }

        return true;
    }

    public static void unregisterObserver(ContentObserver observer) {
        synchronized (registeredContentObservers) {
            for (int nsIdx = 0; nsIdx < registeredContentObservers.size(); ++nsIdx) {
                ArrayMap<String, ArraySet<ContentObserver>> nsObservers = registeredContentObservers.valueAt(nsIdx);
                for (int keyIdx = 0; keyIdx < nsObservers.size(); ++keyIdx) {
                    ArraySet<ContentObserver> observers = nsObservers.valueAt(keyIdx);
                    observers.remove(observer);
                }
            }
        }
    }
}
