package android.app;

import android.Manifest;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ServiceInfo.ForegroundServiceType;
import android.util.Log;

import com.android.internal.pm.pkg.component.ParsedServiceImpl;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;

/** @hide */
public class GmsCompatServiceHooks {
    static final String TAG = "GmsCompatServiceHooks";

    private static final int PROTECTED_TYPES = FOREGROUND_SERVICE_TYPE_CAMERA
            | FOREGROUND_SERVICE_TYPE_LOCATION | FOREGROUND_SERVICE_TYPE_MICROPHONE;

    public static void amendParsedService(ParsedServiceImpl s) {
        final int origFgsType = s.getForegroundServiceType();
        int fgsType = origFgsType;
        if ((fgsType & FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) != 0) {
            // FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED requires special privileges
            fgsType &= ~FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
            fgsType |= FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        if ((fgsType & PROTECTED_TYPES) != 0) {
            // fallback type in case the required permissions are missing
            fgsType |= FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        if (fgsType != origFgsType) {
            s.setForegroundServiceType(fgsType);
        }
    }

    static int getFgsTypeForRetry(final int origFgsType) {
        if (origFgsType == FOREGROUND_SERVICE_TYPE_MANIFEST) {
            return 0;
        }

        if ((origFgsType & PROTECTED_TYPES) == 0) {
            return 0;
        }
        // The policy for protected fgsTypes is too complex to check in translateFgsType() (and the
        // check would be racy anyway), retry instead.
        int fgsType = origFgsType & ~PROTECTED_TYPES;
        if (fgsType == 0) {
            fgsType |= FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        Log.i(TAG, "retrying startForeground with fgsType adjusted from "
                + Integer.toHexString(origFgsType) + " to " + Integer.toHexString(fgsType));
        return fgsType;
    }

    static int translateFgsType(Service service, @ForegroundServiceType final int origFgsType) {
        int fgsType = origFgsType;

        if (fgsType == FOREGROUND_SERVICE_TYPE_MANIFEST) {
            PackageManager pm = service.getPackageManager();
            var serviceComponent = new ComponentName(service, service.getClassName());
            ServiceInfo serviceInfo;
            try {
                serviceInfo = pm.getServiceInfo(serviceComponent, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "unable to resolve FOREGROUND_SERVICE_TYPE_MANIFEST", e);
                return origFgsType;
            }
            fgsType = serviceInfo.getForegroundServiceType();
            if (fgsType == 0) {
                return origFgsType;
            }
        }

        if ((fgsType & FOREGROUND_SERVICE_TYPE_CAMERA) != 0) {
            if (!GmsCompat.hasPermission(Manifest.permission.CAMERA)) {
                fgsType &= ~FOREGROUND_SERVICE_TYPE_CAMERA;
            }
        }

        if ((fgsType & FOREGROUND_SERVICE_TYPE_LOCATION) != 0) {
            if (!GmsCompat.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                fgsType &= ~FOREGROUND_SERVICE_TYPE_LOCATION;
            }
        }

        if ((fgsType & FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) {
            if (!GmsCompat.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                fgsType &= ~FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
        }

        if ((fgsType & FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) != 0) {
            fgsType &= ~FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
            fgsType |= FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }

        if (fgsType == 0 && origFgsType != 0) {
            fgsType = FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }

        if (origFgsType != fgsType) {
            Log.i(TAG, "adjusted fgsType from " + Integer.toHexString(origFgsType) + " to "
                    + Integer.toHexString(fgsType) + " for " + service.getClassName());
        }

        return fgsType;
    }
}
