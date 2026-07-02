package app.grapheneos.hardeningtest;

import android.annotation.Nullable;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;

import static app.grapheneos.hardeningtest.Utils.getTargetSdk;
import static junit.framework.Assert.assertEquals;

public class TestService extends Service {

    public void onCreate() {
        super.onCreate();
        Utils.checkSELinuxContextAndFlags(getExpectedSELinuxContextPrefix(), getTargetSdk(this));
    }

    private INativeTestService bindNativeService() {
        var intent = new Intent().setClassName(getPackageName(),
                "app.grapheneos.hardeningtest.IsolatedNativeTestService");
        var sq = new SynchronousQueue<INativeTestService>();
        getApplicationContext().bindService(intent, BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    sq.put(INativeTestService.Stub.asInterface(service));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                throw new IllegalStateException("onServiceDisconnected for native service");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                throw new IllegalStateException("onBindingDied for native service");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                throw new IllegalStateException("onNullBinding for native service");
            }
        });
        INativeTestService nativeService;
        try {
            nativeService = Objects.requireNonNull(sq.take());
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        int nativeServicePpid;
        try {
            nativeServicePpid = nativeService.getPpid();
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
        int ppid = Os.getppid();
        boolean isExecSpawned = Os.getenv("IS_EXEC_SPAWNED_APP_PROCESS") != null;
        if (isExecSpawned) {
            if (nativeServicePpid != ppid) {
                // both native and Java processes are fork+execed from the primary Java zygote
                throw new IllegalStateException("nativeServicePpid != ppid");
            }
        } else {
            if (nativeServicePpid == ppid) {
                throw new IllegalStateException("nativeServicePpid == ppid");
            }
        }
        return nativeService;
    }

    protected String getExpectedSELinuxContextPrefix() {
        int targetSdk = getTargetSdk(this);
        if (targetSdk != Build.VERSION.SDK_INT) {
            assertEquals("targetSdk", 27, targetSdk);
            return "u:r:untrusted_app_27:s0:c";
        }
        return "u:r:untrusted_app:s0:c";
    }

    class BinderImpl extends ITestService.Stub {
        @Nullable
        public String testDynamicCodeLoading(String typeStr, boolean isAllowed,
                ParcelFileDescriptor appDataFileFd, ParcelFileDescriptor execmodFd,
                boolean callNativeService) {
            INativeTestService nativeService = callNativeService ? bindNativeService() : null;

            boolean isIsolated = callNativeService || Process.isIsolated();
            MultiTests.Type type = MultiTests.Type.valueOf(typeStr);

            var failures = new StringBuilder();

            for (Method m : MultiTests.class.getDeclaredMethods()) {
                var ann = m.getAnnotation(MultiTest.class);
                if (ann == null) {
                    continue;
                }

                if (ann.type() != type) {
                    continue;
                }

                int targetSdk = getApplicationInfo().targetSdkVersion;

                boolean blockedByBasePolicy = targetSdk >=
                    (isIsolated ? ann.alwaysDeniedMinSdkIsolated() : ann.alwaysDeniedMinSdk());

                if (!blockedByBasePolicy && isAllowed && ann.skipAllowedTest()) {
                    continue;
                }

                Log.d("testDcl", "before2 " + m.getName());

                int ret;
                if (callNativeService) {
                    if ((m.getModifiers() & Modifier.NATIVE) == 0) {
                        continue;
                    }
                    ParcelFileDescriptor fd = null;
                    switch (m.getName()) {
                        case "execmod":
                            fd = execmodFd;
                            break;
                        case "exec_app_data_file":
                            fd = appDataFileFd;
                            break;
                    }
                    String nativeName = "Java_app_grapheneos_hardeningtest_MultiTests_" + m.getName().replaceAll("_", "_1");
                    try {
                        ret = nativeService.callFunc(nativeName, fd);
                    } catch (Throwable e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                } else {
                    Object[] args;
                    switch (m.getName()) {
                        case "execmod":
                            args = new Object[] { execmodFd.detachFd() };
                            break;
                        case "exec_app_data_file":
                            args = new Object[] { appDataFileFd.detachFd() };
                            break;
                        default:
                            args = new Object[0];
                    }
                    try {
                        ret = (int) m.invoke(null, args);
                    } catch (Throwable e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                }

                Log.d("testDcl" + (isAllowed? "_allowed" : "_restricted"), "isolated " + isIsolated
                    + ", native " + callNativeService
                    + ", targetSdk " + targetSdk + " " + m.getName() + ": " + Errno.name(ret));

                int expectedRet = isAllowed?
                    isIsolated ?
                        (blockedByBasePolicy? ann.blockedReturnCodeIsolated() : ann.allowedReturnCodeIsolated()) :
                        (blockedByBasePolicy? ann.blockedReturnCode() : ann.allowedReturnCode()) :
                    isIsolated ?
                        ann.blockedReturnCodeIsolated() : ann.blockedReturnCode();

                if (ret != expectedRet) {
                    failures.append('\n');
                    failures.append(m.getName());
                    failures.append(": expected ");
                    failures.append(Errno.name(expectedRet));
                    failures.append(", got ");
                    failures.append(Errno.name(ret));
                    failures.append(", ");
                    if (callNativeService) {
                        failures.append("result is from native isolated service");
                    } else {
                        failures.append(getProcInfo());
                    }
                }
            }

            if (!failures.isEmpty()) {
                return failures.toString();
            }

            return null;
        }

        private String getProcInfo() {
            return "SELinux context: " + SELinux.getContext() + ", pkg: " + getPackageName();
        }

        @Override
        @Nullable
        public String testPtrace(boolean isAllowed, int mainProcessPid, boolean callNativeService) {
            int ret;
            if (callNativeService) {
                try {
                    ret = bindNativeService().callFunc("Java_app_grapheneos_hardeningtest_MultiTests_ptrace", null);
                } catch (Throwable e) {
                    e.printStackTrace();
                    throw new IllegalStateException(e);
                }
            } else {
                ret = MultiTests.ptrace();
            }
            int expectedRet = isAllowed ? 0 : OsConstants.EPERM;

            if (ret != expectedRet) {
                return "expected " + Errno.name(expectedRet) + ", got " + Errno.name(ret) + ", "
                        + (callNativeService ? "from native service" : getProcInfo());
            }

            return null;
        }
    }

    private final IBinder binder = new BinderImpl();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
