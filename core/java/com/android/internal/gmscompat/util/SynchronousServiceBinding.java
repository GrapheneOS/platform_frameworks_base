package com.android.internal.gmscompat.util;

import android.annotation.ElapsedRealtimeLong;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lazily binds to a service and makes blocking calls to it.
 * <p>
 * Binding happens on first use and after the binding was dropped. It blocks the caller,
 * and other callers of the same instance, for up to three attempts of 10 s each. If all of
 * them fail, the process is killed.
 * <p>
 * Only a running call keeps the binding alive, unless the binding is marked as
 * {@link #setPermanent() permanent}. After one to two
 * {@link #setIdlenessCheckInterval idleness check intervals} without calls, the service is unbound,
 * and anything obtained from the service, such as sub-interfaces or registered callbacks, may stop
 * working.
 */
public abstract class SynchronousServiceBinding<T> implements IBinder.DeathRecipient {
    private static final int MAX_CONNECTION_ATTEMPTS = 3;

    private final String logTag;
    private static Handler serviceConnectionHandler;

    private final Context context;
    private final Intent serviceIntent;
    private boolean isPermanent;
    private final ReentrantLock serviceLock = new ReentrantLock();
    @GuardedBy("serviceLock")
    private ServiceConnectionImpl cachedConnection;
    private volatile int idlenessCheckIntervalMs = 30_000;
    @GuardedBy("serviceLock")
    private boolean isIdleConnectionReleaseScheduled;

    /**
     * Context must live as long as the process, i.e. it should be an application context or
     * the root ContextImpl.
     */
    public SynchronousServiceBinding(Context context, Intent serviceIntent, String logTag) {
        this.context = context;
        this.serviceIntent = serviceIntent;
        this.logTag = logTag;
    }

    /**
     * Never release the binding after it's established and kill our process if the service connection
     * fails.
     */
    protected final void setPermanent() {
        isPermanent = true;
    }

    static class RefCountedService<T> {
        final IBinder binder;
        final T service;
        private final AtomicInteger refCount = new AtomicInteger();
        @ElapsedRealtimeLong private volatile long lastUseTimestamp;

        RefCountedService(IBinder binder, T service) {
            this.binder = binder;
            this.service = service;
        }

        void acquire() {
            if (refCount.getAndIncrement() < 0) {
                throw new IllegalStateException("invalid service refcount");
            }
        }

        void release() {
            lastUseTimestamp = SystemClock.elapsedRealtime();
            refCount.getAndDecrement();
        }

        boolean isIdle(int idleTimeoutMs) {
            return refCount.get() == 0 && SystemClock.elapsedRealtime() - lastUseTimestamp > idleTimeoutMs;
        }
    }

    public static Intent componentIntent(ComponentName cn) {
        var i = new Intent();
        i.setComponent(cn);
        return i;
    }

    public void setIdlenessCheckInterval(int intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException();
        }
        idlenessCheckIntervalMs = intervalMs;
    }

    public interface BinderCall<T, R> {
        R call(T service) throws RemoteException;
    }

    public interface VoidBinderCall<T> {
        void call(T service) throws RemoteException;
    }

    /**
     * This method has to be idempotent and shouldn't perform any IPC.
     */
    protected abstract T wrapBinder(IBinder binder);

    public <R> R call(BinderCall<T, R> callFn) throws RemoteException {
        RefCountedService<T> rcService = acquireService();
        try {
            return callFn.call(rcService.service);
        } finally {
            rcService.release();
        }
    }

    public <R> R callOrExit(BinderCall<T, R> callFn) {
        try {
            return call(callFn);
        } catch (RemoteException e) {
            throw exit(e);
        }
    }

    public void voidCallOrExit(VoidBinderCall<T> callFn) {
        try {
            voidCall(callFn);
        } catch (RemoteException e) {
            throw exit(e);
        }
    }

    public void voidCall(VoidBinderCall<T> callFn) throws RemoteException {
        RefCountedService<T> rcService = acquireService();
        try {
            callFn.call(rcService.service);
        } finally {
            rcService.release();
        }
    }

    private RefCountedService<T> acquireService() {
        serviceLock.lock();
        try {
            if (cachedConnection != null) {
                if (!cachedConnection.isUnbound) {
                    RefCountedService<T> service = cachedConnection.service;
                    if (service != null && (isPermanent || service.binder.isBinderAlive())) {
                        service.acquire();
                        return service;
                    }
                    cachedConnection.unbind();
                }
                cachedConnection = null;
            }

            synchronized (SynchronousServiceBinding.class) {
                if (serviceConnectionHandler == null) {
                    // using a separate callback thread is important to avoid deadlocks, e.g. when
                    // called on the main thread
                    var ht = new HandlerThread("ServiceConnection-callbacks");
                    ht.start();
                    serviceConnectionHandler = ht.getThreadHandler();
                } else {
                    if (serviceConnectionHandler.getLooper().isCurrentThread()) {
                        throw new IllegalStateException("acquireService called on serviceConnectionThread");
                    }
                }
            }

            for (int i = 0; i < MAX_CONNECTION_ATTEMPTS; ++i) {
                var connection = new ServiceConnectionImpl();

                @Context.BindServiceFlagsBits int bindFlags = Context.BIND_AUTO_CREATE;
                if (isPermanent) {
                    bindFlags |= Context.BIND_ABOVE_CLIENT;
                }
                boolean bound;
                try {
                    bound = context.bindServiceAsUser(serviceIntent, connection, bindFlags,
                            serviceConnectionHandler, context.getUser());
                } catch (SecurityException e) {
                    Log.e(logTag, "", e);
                    context.unbindService(connection);
                    throw e;
                }
                if (!bound) {
                    Log.e(logTag, "bindService() failed");
                    context.unbindService(connection);
                    continue;
                }

                boolean interrupted = false;
                boolean retry = false;
                long start = SystemClock.uptimeMillis(); // latch.await() doesn't count time in deep sleep
                for (;;) {
                    try {
                        long timeoutMs = 10_000L - (SystemClock.uptimeMillis() - start);
                        if (timeoutMs < 0 || !connection.initialServiceValueLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                            Log.e(logTag, "connection.initialServiceValueLatch timed out");
                            connection.unbind();
                            retry = true;
                            break;
                        }
                        break;
                    } catch (InterruptedException e) {
                        Log.w(logTag, "interrupted while awaiting initialServiceValueLatch", e);
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (retry) {
                    continue;
                }

                RefCountedService<T> service = connection.service;
                if (service == null) {
                    Log.i(logTag, "connection.service is null");
                    continue;
                }

                if (isPermanent) {
                    try {
                        service.binder.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        throw exit("service has already died");
                    }
                }

                cachedConnection = connection;
                service.acquire();
                scheduleIdleConnectionRelease();
                return service;
            }
        } finally {
            serviceLock.unlock();
        }
        throw exit("acquireService() failed");
    }

    @Override
    public void binderDied() {
        throw exit("service has died");
    }

    @GuardedBy("serviceLock")
    private void scheduleIdleConnectionRelease() {
        if (!isPermanent && !isIdleConnectionReleaseScheduled) {
            serviceConnectionHandler.postDelayed(this::releaseConnectionIfIdle, idlenessCheckIntervalMs);
            isIdleConnectionReleaseScheduled = true;
        }
    }

    private void releaseConnectionIfIdle() {
        if (!serviceLock.tryLock()) {
            // avoid deadlock with acquireService()
            serviceConnectionHandler.postDelayed(this::releaseConnectionIfIdle, idlenessCheckIntervalMs);
            return;
        }
        try {
            isIdleConnectionReleaseScheduled = false;
            ServiceConnectionImpl conn = cachedConnection;
            if (conn == null) {
                return;
            }
            RefCountedService<T> service = conn.service;
            if (service == null) {
                cachedConnection = null;
                return;
            }

            if (service.isIdle(idlenessCheckIntervalMs)) {
                Log.d(logTag, "connection is idle, unbinding");
                conn.unbind();
                cachedConnection = null;
            } else {
                scheduleIdleConnectionRelease();
            }
        } finally {
            serviceLock.unlock();
        }
    }

    private RuntimeException exit(String msg) {
        return exit(new RuntimeException(msg));
    }

    private RuntimeException exit(Exception e) {
        Log.e(logTag, "", e);
        Process.killProcess(Process.myPid());
        for (;;) {
            SystemClock.sleep(60_000);
        }
    }

    public class ServiceConnectionImpl implements ServiceConnection {
        final CountDownLatch initialServiceValueLatch = new CountDownLatch(1);

        volatile RefCountedService<T> service;
        volatile boolean isUnbound;

        private static final AtomicLong idSource = new AtomicLong();
        final String connLogTag = logTag + ':' + idSource.getAndIncrement();

        ServiceConnectionImpl() {
            Log.d(connLogTag, "created ServiceConnectionImpl");
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(connLogTag, "onServiceConnected");

            synchronized (this) {
                if (isUnbound) {
                    return;
                }
                service = new RefCountedService<>(binder, wrapBinder(binder));
            }
            initialServiceValueLatch.countDown();
        }

        void unbind() {
            synchronized (this) {
                if (isUnbound) {
                    return;
                }
                Log.i(connLogTag, "unbinding ServiceConnectionImpl");
                service = null;
                // no-op if count-down already happened
                initialServiceValueLatch.countDown();
                isUnbound = true;
                context.unbindService(this);
            }
        }

        private void exitIfPermanent() {
            if (isPermanent && initialServiceValueLatch.getCount() == 0) {
                throw exit(logTag + " connection failed");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(connLogTag, "onServiceDisconnected");
            exitIfPermanent();
            unbind();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.i(connLogTag, "onBindingDied");
            exitIfPermanent();
            unbind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.i(connLogTag, "onNullBinding");
            exitIfPermanent();
            unbind();
        }
    }
}
