/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import static com.android.server.am.Flags.FLAG_GET_PACKAGE_NAMES_FOR_PID_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Context.BindServiceFlags;
import android.content.Context.BindServiceFlagsBits;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Interface for in-process calls into
 * {@link android.content.Context#ACTIVITY_SERVICE ActivityManager system service}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ActivityManagerLocal {
    /**
     * Checks whether an app will be able to start a foreground service or not.
     *
     * @param pid The process id belonging to the app to be checked.
     * @param uid The UID of the app to be checked.
     * @param packageName The package name of the app to be checked.
     * @return whether the app will be able to start a foreground service or not.
     */
    boolean canStartForegroundService(int pid, int uid, @NonNull String packageName);

    /**
     * Returns {@code true} if a foreground service started by an uid is allowed to have
     * while-in-use permissions.
     *
     * @param pid The process id belonging to the app to be checked.
     * @param uid The UID of the app to be checked.
     * @param packageName The package name of the app to be checked.
     * @return whether the foreground service is allowed to have while-in-use permissions.
     * @hide
     */
    boolean canAllowWhileInUsePermissionInFgs(int pid, int uid, @NonNull String packageName);

    /**
     * Temporarily allow foreground service started by an uid to have while-in-use permission
     * for durationMs.
     *
     * @param uid The UID of the app that starts the foreground service.
     * @param durationMs elapsedRealTime duration in milliseconds.
     * @hide
     */
    void tempAllowWhileInUsePermissionInFgs(int uid, long durationMs);

    /**
     * Requests that an SDK sandbox service be started. If this service is not already running,
     * it will be instantiated and started (creating a process for it if needed). You can through
     * the arguments here have the system bring up multiple concurrent processes hosting their own
     * instance of that service. Each instance is identified by the {@code processName} provided
     * here.
     *
     * @param service Identifies the sdk sandbox process service to connect to. The Intent must
     *                specify an explicit component name. This value cannot be null.
     * @param clientAppUid Uid of the app for which the sdk sandbox process needs to be spawned.
     * @param clientAppPackage Package of the app for which the sdk sandbox process needs to
     *        be spawned. This package must belong to the clientAppUid.
     * @param processName Unique identifier for the service instance. Each unique name here will
     *        result in a different service instance being created. Identifiers must only contain
     *        ASCII letters, digits, underscores, and periods.
     *
     * @throws RemoteException If the service could not be started.
     * @return If the service is being started or is already running, the {@link ComponentName} of
     * the actual service that was started is returned; else if the service does not exist null is
     * returned.
     */
    @Nullable
    @SuppressLint("RethrowRemoteException")
    ComponentName startSdkSandboxService(@NonNull Intent service, int clientAppUid,
            @NonNull String clientAppPackage, @NonNull String processName)
            throws RemoteException;

    // TODO(b/269592470): What if the sandbox is stopped while there is an active binding to it?
    /**
     * Requests that an SDK sandbox service with a given {@code processName} be stopped.
     *
     * @param service Identifies the sdk sandbox process service to connect to. The Intent must
     *        specify an explicit component name. This value cannot be null.
     * @param clientAppUid Uid of the app for which the sdk sandbox process needs to be stopped.
     * @param clientAppPackage Package of the app for which the sdk sandbox process needs to
     *        be stopped. This package must belong to the clientAppUid.
     * @param processName Unique identifier for the service instance. Each unique name here will
     *        result in a different service instance being created. Identifiers must only contain
     *        ASCII letters, digits, underscores, and periods.
     *
     * @return If there is a service matching the given Intent that is already running, then it is
     *         stopped and true is returned; else false is returned.
     */
    boolean stopSdkSandboxService(@NonNull Intent service, int clientAppUid,
            @NonNull String clientAppPackage, @NonNull String processName);

    /**
     * Binds to an SDK sandbox service for a given client application.
     *
     * @param service Identifies the sdk sandbox process service to connect to. The Intent must
     *        specify an explicit component name. This value cannot be null.
     * @param conn Receives information as the service is started and stopped.
     *        This must be a valid ServiceConnection object; it must not be null.
     * @param clientAppUid Uid of the app for which the sdk sandbox process needs to be spawned.
     * @param clientAppProcessToken process token used to uniquely identify the client app
     *        process binding to the SDK sandbox. This is obtained using
     *        {@link Context#getProcessToken()}.
     * @param clientAppPackage Package of the app for which the sdk sandbox process needs to
     *        be spawned. This package must belong to the clientAppUid.
     * @param processName Unique identifier for the service instance. Each unique name here will
     *        result in a different service instance being created. Identifiers must only contain
     *        ASCII letters, digits, underscores, and periods.
     * @param flags Operation options provided by Context class for the binding.
     * @return {@code true} if the system is in the process of bringing up a
     *         service that your client has permission to bind to; {@code false}
     *         if the system couldn't find the service or if your client doesn't
     *         have permission to bind to it.
     * @throws RemoteException If the service could not be bound to.
     * @see Context#bindService(Intent, ServiceConnection, int)
     */
    @SuppressLint("RethrowRemoteException")
    boolean bindSdkSandboxService(@NonNull Intent service, @NonNull ServiceConnection conn,
            int clientAppUid, @NonNull IBinder clientAppProcessToken,
            @NonNull String clientAppPackage, @NonNull String processName,
            @BindServiceFlagsBits int flags)
            throws RemoteException;

    /**
     * See {@link #bindSdkSandboxService(Intent, ServiceConnection, int, IBinder, String, String,
     *       int)}
     */
    @SuppressLint("RethrowRemoteException")
    boolean bindSdkSandboxService(@NonNull Intent service, @NonNull ServiceConnection conn,
            int clientAppUid, @NonNull IBinder clientAppProcessToken,
            @NonNull String clientAppPackage, @NonNull String processName,
            @NonNull BindServiceFlags flags)
            throws RemoteException;

    /**
     * @deprecated Please use
     * {@link #bindSdkSandboxService(Intent, ServiceConnection, int, IBinder, String, String,
     *       BindServiceFlags)}
     *
     * This API can't be deleted yet because it can be used by early AdService module versions.
     */
    @SuppressLint("RethrowRemoteException")
    boolean bindSdkSandboxService(@NonNull Intent service, @NonNull ServiceConnection conn,
            int clientAppUid, @NonNull String clientAppPackage, @NonNull String processName,
            @BindServiceFlagsBits int flags)
            throws RemoteException;

    /**
     * Kill an app process associated with an SDK sandbox.
     *
     * @param clientAppProcessToken process token used to uniquely identify the client app
     *        process associated with an SDK sandbox. This is obtained using
     *        {@link Context#getProcessToken()}.
     */
    void killSdkSandboxClientAppProcess(@NonNull IBinder clientAppProcessToken);

    /**
     * Get all the package names associated with a process id.
     *
     * @param pid The process id of the process to be checked.
     * @param uid The UID of the app to be checked.
     * @return The package names associated with the provided PID and UID, or an empty array if the
     *         process is not found or doesn't match the UID provided.
     */
    @FlaggedApi(FLAG_GET_PACKAGE_NAMES_FOR_PID_API)
    @NonNull
    String[] getPackageNamesForPid(int pid, int uid);

    /**
     * Start a foreground service delegate on behalf of the actual app. By this the client app's
     * process state can be promoted to a higher process state, equivalent to that of a foreground
     * service, which may be higher than the app's actual process state if the app is in the
     * background. This can help keep the app in memory and potentially give it extra run-time. The
     * app does not need to define an actual service component nor add it to their manifest file.
     *
     * @param params foreground service delegate params.
     * @param connection a service connection served as callback to the caller. If {@code null}, no
     *                   callbacks will be made to the caller.
     * @return true if delegate is started successfully, false otherwise.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @FlaggedApi(Flags.FLAG_FGS_DELEGATE_SYSTEM_API)
    boolean startForegroundServiceDelegate(
            @NonNull ForegroundServiceDelegationParams params,
            @Nullable ServiceConnection connection);

    /**
     * Stop a foreground service delegate that was started with
     * {@link #startForegroundServiceDelegate}.
     *
     * @param params the foreground service delegate params.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @FlaggedApi(Flags.FLAG_FGS_DELEGATE_SYSTEM_API)
    void stopForegroundServiceDelegate(
            @NonNull ForegroundServiceDelegationParams params);

    void showAppStrictLeakBlockingNotification(int uid, int pid);
}
