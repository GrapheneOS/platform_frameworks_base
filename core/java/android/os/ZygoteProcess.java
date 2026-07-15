/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import static android.os.Process.ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;
import static android.os.Process.ZYGOTE_POLICY_FLAG_SYSTEM_PROCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Zygote;
import com.android.internal.os.ZygoteConfig;
import com.android.internal.os.ZygoteExtraArgs;
import com.android.internal.os.ZygoteType;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*package*/ class ZygoteStartFailedEx extends Exception {
    @UnsupportedAppUsage
    ZygoteStartFailedEx(String s) {
        super(s);
    }

    @UnsupportedAppUsage
    ZygoteStartFailedEx(Throwable cause) {
        super(cause);
    }

    ZygoteStartFailedEx(String s, Throwable cause) {
        super(s, cause);
    }
}

/**
 * Maintains communication state with the zygote processes. This class is responsible
 * for the sockets opened to the zygotes and for starting processes on behalf of the
 * {@link android.os.Process} class.
 *
 * @hide
 */
public class ZygoteProcess implements IZygoteProcess {
    private static final int NUM_ZYGOTE_TYPES = ZygoteType.values().length;

    private static final int ZYGOTE_CONNECT_TIMEOUT_MS = 60000;

    // How long we wait for an AppZygote to complete pre-loading; this is a pretty conservative
    // value that we can probably decrease over time, but we want to be careful here.
    public static volatile int sAppZygotePreloadTimeoutMs = 15000;
    /**
     * Use a relatively short delay, because for app zygote, this is in the critical path of
     * service launch.
     */
    private static final int ZYGOTE_CONNECT_RETRY_DELAY_MS = 5;

    private static final String LOG_TAG = "ZygoteProcess";

    /**
     * The name of the sockets used to communicate with the zygotes.
     */
    private final LocalSocketAddress[] mZygoteSocketAddresses = new LocalSocketAddress[NUM_ZYGOTE_TYPES];

    /**
     * The names of the sockets used to communicate with the zygote's USAP pool.
     */
    private final LocalSocketAddress[] mUsapPoolSocketAddresses = new LocalSocketAddress[NUM_ZYGOTE_TYPES];

    public ZygoteProcess() {
        for (var type : ZygoteType.values()) {
            int idx = type.ordinal();
            mZygoteSocketAddresses[idx] = new LocalSocketAddress(type.getSocketName(),
                                       LocalSocketAddress.Namespace.RESERVED);
            mUsapPoolSocketAddresses[idx] = new LocalSocketAddress(type.getUsapPoolSocketName(),
                                       LocalSocketAddress.Namespace.RESERVED);
        }

        // This constructor is used to create the primary and secondary Zygotes, which can support
        // Unspecialized App Process Pools.
        mUsapPoolSupported = true;
    }

    static ZygoteProcess createChildZygoteProcess(LocalSocketAddress socketAddress) {
        var res = new ZygoteProcess(socketAddress);
        res.mIsChildZygoteProcess = true;
        return res;
    }

    private boolean mIsChildZygoteProcess;

    private ZygoteProcess(LocalSocketAddress primarySocketAddress) {
        mZygoteSocketAddresses[ZygoteType.Primary.ordinal()] = primarySocketAddress;
        // This constructor is used to create the primary Zygotes which CAN NOT
        // support Unspecialized App Process Pools.
        mUsapPoolSupported = false;
    }

    @Override
    public LocalSocketAddress getPrimarySocketAddress() {
        return mZygoteSocketAddresses[ZygoteType.Primary.ordinal()];
    }

    /**
     * State for communicating with the zygote process.
     */
    private static class ZygoteState implements AutoCloseable {
        final LocalSocketAddress mZygoteSocketAddress;
        final LocalSocketAddress mUsapSocketAddress;

        private final LocalSocket mZygoteSessionSocket;

        final DataInputStream mZygoteInputStream;
        final BufferedWriter mZygoteOutputWriter;

        private final List<String> mAbiList;

        boolean lazyPreloadCompleted;

        private boolean mClosed;

        private ZygoteState(LocalSocketAddress zygoteSocketAddress,
                            LocalSocketAddress usapSocketAddress,
                            LocalSocket zygoteSessionSocket,
                            DataInputStream zygoteInputStream,
                            BufferedWriter zygoteOutputWriter,
                            List<String> abiList) {
            this.mZygoteSocketAddress = zygoteSocketAddress;
            this.mUsapSocketAddress = usapSocketAddress;
            this.mZygoteSessionSocket = zygoteSessionSocket;
            this.mZygoteInputStream = zygoteInputStream;
            this.mZygoteOutputWriter = zygoteOutputWriter;
            this.mAbiList = abiList;
        }

        /**
         * Create a new ZygoteState object by connecting to the given Zygote socket and saving the
         * given USAP socket address.
         *
         * @param zygoteSocketAddress  Zygote socket to connect to
         * @param usapSocketAddress  USAP socket address to save for later
         * @return  A new ZygoteState object containing a session socket for the given Zygote socket
         * address
         * @throws IOException
         */
        static ZygoteState connect(@NonNull LocalSocketAddress zygoteSocketAddress,
                @Nullable LocalSocketAddress usapSocketAddress)
                throws IOException {

            DataInputStream zygoteInputStream;
            BufferedWriter zygoteOutputWriter;
            final LocalSocket zygoteSessionSocket = new LocalSocket();

            if (zygoteSocketAddress == null) {
                throw new IllegalArgumentException("zygoteSocketAddress can't be null");
            }

            try {
                zygoteSessionSocket.connect(zygoteSocketAddress);
                zygoteInputStream = new DataInputStream(zygoteSessionSocket.getInputStream());
                zygoteOutputWriter =
                        new BufferedWriter(
                                new OutputStreamWriter(zygoteSessionSocket.getOutputStream()),
                                Zygote.SOCKET_BUFFER_SIZE);
            } catch (IOException ex) {
                try {
                    zygoteSessionSocket.close();
                } catch (IOException ignore) { }

                throw ex;
            }

            return new ZygoteState(zygoteSocketAddress, usapSocketAddress,
                                   zygoteSessionSocket, zygoteInputStream, zygoteOutputWriter,
                                   getAbiList(zygoteOutputWriter, zygoteInputStream));
        }

        LocalSocket getUsapSessionSocket() throws IOException {
            final LocalSocket usapSessionSocket = new LocalSocket();
            usapSessionSocket.connect(this.mUsapSocketAddress);

            return usapSessionSocket;
        }

        boolean matches(String abi) {
            return mAbiList.contains(abi);
        }

        public void close() {
            try {
                mZygoteSessionSocket.close();
            } catch (IOException ex) {
                Log.e(LOG_TAG,"I/O exception on routine close", ex);
            }

            mClosed = true;
        }

        boolean isClosed() {
            return mClosed;
        }
    }

    /**
     * Lock object to protect access to the two ZygoteStates below. This lock must be
     * acquired while communicating over the ZygoteState's socket, to prevent
     * interleaved access.
     */
    private final Object mLock = new Object();

    /**
     * List of exemptions to the API deny list. These are prefix matches on the runtime format
     * symbol signature. Any matching symbol is treated by the runtime as being on the light grey
     * list.
     */
    private List<String> mApiDenylistExemptions = Collections.emptyList();

    /**
     * Proportion of hidden API accesses that should be logged to the event log; 0 - 0x10000.
     */
    private int mHiddenApiAccessLogSampleRate;

    /**
     * Proportion of hidden API accesses that should be logged to statslog; 0 - 0x10000.
     */
    private int mHiddenApiAccessStatslogSampleRate;

    /**
     * Zygote connection states.
     */
    private ZygoteState[] mZygoteStates = new ZygoteState[NUM_ZYGOTE_TYPES];

    /**
     * If this Zygote supports the creation and maintenance of a USAP pool.
     *
     * Currently only the primary and secondary Zygotes support USAP pools. Any
     * child Zygotes will be unable to create or use a USAP pool.
     */
    private final boolean mUsapPoolSupported;

    /**
     * If the USAP pool should be created and used to start applications.
     *
     * Setting this value to false will disable the creation, maintenance, and use of the USAP
     * pool.  When the USAP pool is disabled the application lifecycle will be identical to
     * previous versions of Android.
     */
    private boolean mUsapPoolEnabled = false;

    /**
     * {@inheritDoc}
     *
     * <p>If processes are enabled, a new process is created and the static main() function of a
     * <var>processClass</var> is executed there. The process will continue running after this
     * function returns.</p>
     *
     * </p>If processes are not enabled, a new thread in the caller's process is created and main()
     * of <var>processClass</var> called there.</p>
     */
    @Override
    public final Process.ProcessStartResult start(@NonNull final ZygoteExtraArgs zygoteExtArgs,
                                                  @NonNull final String processClass,
                                                  final String niceName,
                                                  int uid, int gid, @Nullable int[] gids,
                                                  int runtimeFlags, int mountExternal,
                                                  int targetSdkVersion,
                                                  @Nullable String seInfo,
                                                  @NonNull String abi,
                                                  @Nullable String instructionSet,
                                                  @Nullable String appDataDir,
                                                  @Nullable String invokeWith,
                                                  @Nullable String packageName,
                                                  int zygotePolicyFlags,
                                                  boolean isTopApp,
                                                  @Nullable long[] disabledCompatChanges,
                                                  @Nullable long[] enabledCompatChanges,
                                                  boolean useDeliQueue,
                                                  @Nullable Map<String, Pair<String, Long>>
                                                          pkgDataInfoMap,
                                                  @Nullable Map<String, Pair<String, Long>>
                                                          allowlistedDataInfoList,
                                                  boolean bindMountAppsData,
                                                  boolean bindMountAppStorageDirs,
                                                  boolean bindOverrideSysprops,
                                                  long startSeq,
                                                  @Nullable String[] zygoteArgs) {
        // TODO (chriswailes): Is there a better place to check this value?
        if (fetchUsapPoolEnabledPropWithMinInterval()) {
            informZygotesOfUsapPoolStatus();
        }

        try {
            return startViaZygote(zygoteExtArgs, processClass, niceName, uid, gid, gids,
                    runtimeFlags, mountExternal, targetSdkVersion, seInfo,
                    abi, instructionSet, appDataDir, invokeWith, /*startChildZygote=*/ false,
                    packageName, zygotePolicyFlags, isTopApp, disabledCompatChanges,
                    enabledCompatChanges, useDeliQueue, pkgDataInfoMap, allowlistedDataInfoList,
                    bindMountAppsData, bindMountAppStorageDirs, bindOverrideSysprops,
                    startSeq, zygoteArgs);
        } catch (ZygoteStartFailedEx ex) {
            Log.e(LOG_TAG,
                    "Starting VM process through Zygote failed");
            throw new RuntimeException(
                    "Starting VM process through Zygote failed", ex);
        }
    }

    /** retry interval for opening a zygote socket */
    static final int ZYGOTE_RETRY_MILLIS = 500;

    /**
     * Queries the zygote for the list of ABIS it supports.
     */
    @GuardedBy("mLock")
    private static List<String> getAbiList(BufferedWriter writer, DataInputStream inputStream)
            throws IOException {
        // Each query starts with the argument count (1 in this case)
        writer.write("1");
        // ... followed by a new-line.
        writer.newLine();
        // ... followed by our only argument.
        writer.write("--query-abi-list");
        writer.newLine();
        writer.flush();

        // The response is a length prefixed stream of ASCII bytes.
        int numBytes = inputStream.readInt();
        byte[] bytes = new byte[numBytes];
        inputStream.readFully(bytes);

        final String rawList = new String(bytes, StandardCharsets.US_ASCII);

        return Arrays.asList(rawList.split(","));
    }

    /**
     * Sends an argument list to the zygote process, which starts a new child
     * and returns the child's pid. Please note: the present implementation
     * replaces newlines in the argument list with spaces.
     *
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    @GuardedBy("mLock")
    private Process.ProcessStartResult zygoteSendArgsAndGetResult(
            ZygoteState zygoteState, int zygotePolicyFlags, @NonNull ArrayList<String> args)
            throws ZygoteStartFailedEx {
        // Throw early if any of the arguments are malformed. This means we can
        // avoid writing a partial response to the zygote.
        for (String arg : args) {
            // Making two indexOf calls here is faster than running a manually fused loop due
            // to the fact that indexOf is an optimized intrinsic.
            if (arg.indexOf('\n') >= 0) {
                throw new ZygoteStartFailedEx("Embedded newlines not allowed");
            } else if (arg.indexOf('\r') >= 0) {
                throw new ZygoteStartFailedEx("Embedded carriage returns not allowed");
            } else if (arg.indexOf('\u0000') >= 0) {
                throw new ZygoteStartFailedEx("Embedded nulls not allowed");
            }
        }

        /*
         * See com.android.internal.os.ZygoteArguments.parseArgs()
         * Presently the wire format to the zygote process is:
         * a) a count of arguments (argc, in essence)
         * b) a number of newline-separated argument strings equal to count
         *
         * After the zygote process reads these it will write the pid of
         * the child or -1 on failure, followed by boolean to
         * indicate whether a wrapper process was used.
         */
        String msgStr = args.size() + "\n" + String.join("\n", args) + "\n";

        if (shouldAttemptUsapLaunch(zygotePolicyFlags, args)) {
            try {
                return attemptUsapSendArgsAndGetResult(zygoteState, msgStr);
            } catch (IOException ex) {
                // If there was an IOException using the USAP pool we will log the error and
                // attempt to start the process through the Zygote.
                Log.e(LOG_TAG, "IO Exception while communicating with USAP pool - "
                        + ex.getMessage());
            }
        }

        return attemptZygoteSendArgsAndGetResult(zygoteState, msgStr);
    }

    public Process.ProcessStartResult execSpawnNativeAppProcess(ZygoteExtraArgs args) {
        var argList = new ArrayList<String>();
        args.toZygoteArgList(argList);
        String msgStr = argList.size() + "\n" + String.join("\n", argList) + '\n';

        try {
            synchronized (mLock) {
                ZygoteState zygoteState;
                try {
                    zygoteState = attemptConnectionToPrimaryZygote();
                } catch (IOException e) {
                    throw new ZygoteStartFailedEx("Error connecting to zygote", e);
                }
                return attemptZygoteSendArgsAndGetResult(zygoteState, msgStr);
            }
        } catch (ZygoteStartFailedEx e) {
            throw new RuntimeException("Exec spawning native app process failed", e);
        }
    }

    private Process.ProcessStartResult attemptZygoteSendArgsAndGetResult(
            ZygoteState zygoteState, String msgStr) throws ZygoteStartFailedEx {
        try {
            final BufferedWriter zygoteWriter = zygoteState.mZygoteOutputWriter;
            final DataInputStream zygoteInputStream = zygoteState.mZygoteInputStream;

            zygoteWriter.write(msgStr);
            zygoteWriter.flush();

            // Always read the entire result from the input stream to avoid leaving
            // bytes in the stream for future process starts to accidentally stumble
            // upon.
            Process.ProcessStartResult result = new Process.ProcessStartResult();
            result.pid = zygoteInputStream.readInt();
            result.usingWrapper = zygoteInputStream.readBoolean();

            if (result.pid < 0) {
                throw new ZygoteStartFailedEx("fork() failed");
            }

            return result;
        } catch (IOException ex) {
            zygoteState.close();
            Log.e(LOG_TAG, "IO Exception while communicating with Zygote - "
                    + ex.toString());
            throw new ZygoteStartFailedEx(ex);
        }
    }

    private Process.ProcessStartResult attemptUsapSendArgsAndGetResult(
            ZygoteState zygoteState, String msgStr)
            throws ZygoteStartFailedEx, IOException {
        try (LocalSocket usapSessionSocket = zygoteState.getUsapSessionSocket()) {
            final BufferedWriter usapWriter =
                    new BufferedWriter(
                            new OutputStreamWriter(usapSessionSocket.getOutputStream()),
                            Zygote.SOCKET_BUFFER_SIZE);
            final DataInputStream usapReader =
                    new DataInputStream(usapSessionSocket.getInputStream());

            usapWriter.write(msgStr);
            usapWriter.flush();

            Process.ProcessStartResult result = new Process.ProcessStartResult();
            result.pid = usapReader.readInt();
            // USAPs can't be used to spawn processes that need wrappers.
            result.usingWrapper = false;

            if (result.pid >= 0) {
                return result;
            } else {
                throw new ZygoteStartFailedEx("USAP specialization failed");
            }
        }
    }

    /**
     * Test various member properties and parameters to determine if a launch event should be
     * handled using an Unspecialized App Process Pool or not.
     *
     * @param zygotePolicyFlags Policy flags indicating special behavioral observations about the
     *                          Zygote command
     * @param args Arguments that will be passed to the Zygote
     * @return If the command should be sent to a USAP Pool member or an actual Zygote
     */
    private boolean shouldAttemptUsapLaunch(int zygotePolicyFlags, ArrayList<String> args) {
        return mUsapPoolSupported
                && mUsapPoolEnabled
                && policySpecifiesUsapPoolLaunch(zygotePolicyFlags)
                && commandSupportedByUsap(args);
    }

    /**
     * Tests a Zygote policy flag set for various properties that determine if it is eligible for
     * being handled by an Unspecialized App Process Pool.
     *
     * @param zygotePolicyFlags Policy flags indicating special behavioral observations about the
     *                          Zygote command
     * @return If the policy allows for use of a USAP pool
     */
    private static boolean policySpecifiesUsapPoolLaunch(int zygotePolicyFlags) {
        /*
         * Zygote USAP Pool Policy: Launch the new process from the USAP Pool iff the launch event
         * is latency sensitive but *NOT* a system process.  All system processes are equally
         * important so we don't want to prioritize one over another.
         */
        return (zygotePolicyFlags
                & (ZYGOTE_POLICY_FLAG_SYSTEM_PROCESS | ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE))
                == ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;
    }

    /**
     * Flags that may not be passed to a USAP.  These may appear as prefixes to individual Zygote
     * arguments.
     */
    private static final String[] INVALID_USAP_FLAGS = {
        ZygoteExtraArgs.ARG_COMPLEX_COMMAND_MARKER, // USAPs are pointless when exec spawning is used
        "--query-abi-list",
        "--get-pid",
        "--preload-default",
        "--preload-package",
        "--preload-app",
        "--start-child-zygote",
        "--set-api-denylist-exemptions",
        "--hidden-api-log-sampling-rate",
        "--hidden-api-statslog-sampling-rate",
        "--invoke-with"
    };

    /**
     * Tests a command list to see if it is valid to send to a USAP.
     *
     * @param args  Zygote/USAP command arguments
     * @return  True if the command can be passed to a USAP; false otherwise
     */
    private static boolean commandSupportedByUsap(ArrayList<String> args) {
        for (String flag : args) {
            for (String badFlag : INVALID_USAP_FLAGS) {
                if (flag.startsWith(badFlag)) {
                    return false;
                }
            }
            if (flag.startsWith("--nice-name=")) {
                // Check if the wrap property is set, usap would ignore it.
                if (Zygote.getWrapProperty(flag.substring(12)) != null) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Starts a new process via the zygote mechanism.
     *
     * @param processClass Class name whose static main() to run
     * @param niceName 'nice' process name to appear in ps
     * @param uid a POSIX uid that the new process should setuid() to
     * @param gid a POSIX gid that the new process shuold setgid() to
     * @param gids null-ok; a list of supplementary group IDs that the
     * new process should setgroup() to.
     * @param runtimeFlags Additional flags for the runtime.
     * @param targetSdkVersion The target SDK version for the app.
     * @param seInfo null-ok SELinux information for the new process.
     * @param abi the ABI the process should use.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param startChildZygote Start a sub-zygote. This creates a new zygote process
     * that has its state cloned from this zygote process.
     * @param packageName null-ok the name of the package this process belongs to.
     * @param zygotePolicyFlags Flags used to determine how to launch the application.
     * @param isTopApp Whether the process starts for high priority application.
     * @param disabledCompatChanges a list of disabled compat changes for the process being started.
     * @param pkgDataInfoMap Map from related package names to private data directory volume UUID
     *                       and inode number.
     * @param allowlistedDataInfoList Map from allowlisted package names to private data directory
     *                       volume UUID and inode number.
     * @param bindMountAppsData whether zygote needs to mount CE and DE data.
     * @param bindMountAppStorageDirs whether zygote needs to mount Android/obb and Android/data.
     * @param extraArgs Additional arguments to supply to the zygote process.
     * @return An object that describes the result of the attempt to start the process.
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    private Process.ProcessStartResult startViaZygote(@NonNull final ZygoteExtraArgs zygoteExtArgs,
                                                      @NonNull final String processClass,
                                                      @Nullable final String niceName,
                                                      final int uid, final int gid,
                                                      @Nullable final int[] gids,
                                                      int runtimeFlags, int mountExternal,
                                                      int targetSdkVersion,
                                                      @Nullable String seInfo,
                                                      @NonNull String abi,
                                                      @Nullable String instructionSet,
                                                      @Nullable String appDataDir,
                                                      @Nullable String invokeWith,
                                                      boolean startChildZygote,
                                                      @Nullable String packageName,
                                                      int zygotePolicyFlags,
                                                      boolean isTopApp,
                                                      @Nullable long[] disabledCompatChanges,
                                                      @Nullable long[] enabledCompatChanges,
                                                      boolean useDeliQueue,
                                                      @Nullable Map<String, Pair<String, Long>>
                                                              pkgDataInfoMap,
                                                      @Nullable Map<String, Pair<String, Long>>
                                                              allowlistedDataInfoList,
                                                      boolean bindMountAppsData,
                                                      boolean bindMountAppStorageDirs,
                                                      boolean bindMountOverrideSysprops,
                                                      long startSeq,
                                                      @Nullable String[] extraArgs)
                                                      throws ZygoteStartFailedEx {
        ArrayList<String> argsForZygote = new ArrayList<>();
        zygoteExtArgs.toZygoteArgList(argsForZygote);

        // --runtime-args, --setuid=, --setgid=,
        // and --setgroups= must go first
        argsForZygote.add("--runtime-args");
        argsForZygote.add("--setuid=" + uid);
        argsForZygote.add("--setgid=" + gid);
        argsForZygote.add("--runtime-flags=" + runtimeFlags);

        if (mountExternal == Zygote.MOUNT_EXTERNAL_DEFAULT) {
            argsForZygote.add("--mount-external-default");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_INSTALLER) {
            argsForZygote.add("--mount-external-installer");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_PASS_THROUGH) {
            argsForZygote.add("--mount-external-pass-through");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_ANDROID_WRITABLE) {
            argsForZygote.add("--mount-external-android-writable");
        }

        argsForZygote.add("--target-sdk-version=" + targetSdkVersion);

        // --setgroups is a comma-separated list
        if (gids != null && gids.length > 0) {
            final StringBuilder sb = new StringBuilder();
            sb.append("--setgroups=");

            final int sz = gids.length;
            for (int i = 0; i < sz; i++) {
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(gids[i]);
            }

            argsForZygote.add(sb.toString());
        }

        if (niceName != null) {
            argsForZygote.add("--nice-name=" + niceName);
        }

        if (seInfo != null) {
            argsForZygote.add("--seinfo=" + seInfo);
        }

        if (instructionSet != null) {
            argsForZygote.add("--instruction-set=" + instructionSet);
        }

        if (appDataDir != null) {
            argsForZygote.add("--app-data-dir=" + appDataDir);
        }

        if (invokeWith != null) {
            argsForZygote.add("--invoke-with");
            argsForZygote.add(invokeWith);
        }

        if (startChildZygote) {
            argsForZygote.add("--start-child-zygote");
        }

        if (packageName != null) {
            argsForZygote.add("--package-name=" + packageName);
        }

        if (isTopApp) {
            argsForZygote.add(Zygote.START_AS_TOP_APP_ARG);
        }
        if (pkgDataInfoMap != null && pkgDataInfoMap.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(Zygote.PKG_DATA_INFO_MAP);
            sb.append("=");
            boolean started = false;
            for (Map.Entry<String, Pair<String, Long>> entry : pkgDataInfoMap.entrySet()) {
                if (started) {
                    sb.append(',');
                }
                started = true;
                sb.append(entry.getKey());
                sb.append(',');
                sb.append(entry.getValue().first);
                sb.append(',');
                sb.append(entry.getValue().second);
            }
            argsForZygote.add(sb.toString());
        }
        if (allowlistedDataInfoList != null && allowlistedDataInfoList.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(Zygote.ALLOWLISTED_DATA_INFO_MAP);
            sb.append("=");
            boolean started = false;
            for (Map.Entry<String, Pair<String, Long>> entry : allowlistedDataInfoList.entrySet()) {
                if (started) {
                    sb.append(',');
                }
                started = true;
                sb.append(entry.getKey());
                sb.append(',');
                sb.append(entry.getValue().first);
                sb.append(',');
                sb.append(entry.getValue().second);
            }
            argsForZygote.add(sb.toString());
        }

        if (bindMountAppStorageDirs) {
            argsForZygote.add(Zygote.BIND_MOUNT_APP_STORAGE_DIRS);
        }

        if (bindMountAppsData) {
            argsForZygote.add(Zygote.BIND_MOUNT_APP_DATA_DIRS);
        }

        if (bindMountOverrideSysprops) {
            argsForZygote.add(Zygote.BIND_MOUNT_SYSPROP_OVERRIDES);
        }

        if (disabledCompatChanges != null && disabledCompatChanges.length > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("--disabled-compat-changes=");

            int sz = disabledCompatChanges.length;
            for (int i = 0; i < sz; i++) {
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(disabledCompatChanges[i]);
            }

            argsForZygote.add(sb.toString());
        }

        if (enabledCompatChanges != null && enabledCompatChanges.length > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("--enabled-compat-changes=");

            int sz = enabledCompatChanges.length;
            for (int i = 0; i < sz; i++) {
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(enabledCompatChanges[i]);
            }

            argsForZygote.add(sb.toString());
        }

        argsForZygote.add(processClass);

        if (extraArgs != null) {
            Collections.addAll(argsForZygote, extraArgs);
        }

        // Ordering is significant; only args after processClass (android.app.ActivityThread if
        // started from ProcessList) are propagated to ActivityThread, where
        // Looper.prepareMainLooper() is called.
        StringBuilder deliQueueSb = new StringBuilder();
        deliQueueSb.append("--use-deliqueue=");
        deliQueueSb.append(useDeliQueue);
        argsForZygote.add(deliQueueSb.toString());

        synchronized(mLock) {
            // The USAP pool can not be used if the application will not use the systems graphics
            // driver.  If that driver is requested use the Zygote application start path.

            ZygoteState zygoteState = openZygoteSocketIfNeeded(abi, zygoteExtArgs.getZygoteSelectionMode());

            if (zygoteState == mZygoteStates[ZygoteType.Compat.ordinal()] && !zygoteState.lazyPreloadCompleted) {
                long start = SystemClock.elapsedRealtime();
                try {
                    if (!preloadDefault(zygoteState)) {
                        throw new ZygoteStartFailedEx("compat zygote preloading failed");
                    }
                } catch (IOException e) {
                    throw new ZygoteStartFailedEx("compat zygote preloading failed", e);
                }
                Log.i(LOG_TAG, "waited " + (SystemClock.elapsedRealtime() - start) + " ms for compat zygote preloading");
                zygoteState.lazyPreloadCompleted = true;
            }

            return zygoteSendArgsAndGetResult(zygoteState, zygotePolicyFlags, argsForZygote);
        }
    }

    private boolean fetchUsapPoolEnabledProp() {
        boolean origVal = mUsapPoolEnabled;

        mUsapPoolEnabled = ZygoteConfig.getBool(
            ZygoteConfig.USAP_POOL_ENABLED, ZygoteConfig.USAP_POOL_ENABLED_DEFAULT);

        boolean valueChanged = origVal != mUsapPoolEnabled;

        if (valueChanged) {
            Log.i(LOG_TAG, "usapPoolEnabled = " + mUsapPoolEnabled);
        }

        return valueChanged;
    }

    private boolean mIsFirstPropCheck = true;
    private long mLastPropCheckTimestamp = 0;

    private boolean fetchUsapPoolEnabledPropWithMinInterval() {
        // If this Zygote doesn't support USAPs there is no need to fetch any
        // properties.
        if (!mUsapPoolSupported) return false;

        final long currentTimestamp = SystemClock.elapsedRealtime();

        if (mIsFirstPropCheck
                || (currentTimestamp - mLastPropCheckTimestamp >= Zygote.PROPERTY_CHECK_INTERVAL)) {
            mIsFirstPropCheck = false;
            mLastPropCheckTimestamp = currentTimestamp;
            return fetchUsapPoolEnabledProp();
        }

        return false;
    }

    @Override
    public void close() {
        for (ZygoteState state : mZygoteStates) {
            if (state != null) {
                state.close();
            }
        }
    }

    /**
     * Tries to establish a connection to the zygote that handles a given {@code abi}. Might block
     * and retry if the zygote is unresponsive. This method is a no-op if a connection is
     * already open.
     */
    public void establishZygoteConnectionForAbi(String abi, ZygoteSelectionMode zsm) {
        try {
            synchronized(mLock) {
                openZygoteSocketIfNeeded(abi, zsm);
            }
        } catch (ZygoteStartFailedEx ex) {
            throw new RuntimeException("Unable to connect to zygote for abi: " + abi, ex);
        }
    }

    /**
     * Attempt to retrieve the PID of the zygote serving the given abi.
     */
    public int getZygotePid(String abi, ZygoteSelectionMode zsm) {
        try {
            synchronized (mLock) {
                ZygoteState state = openZygoteSocketIfNeeded(abi, zsm);

                // Each query starts with the argument count (1 in this case)
                state.mZygoteOutputWriter.write("1");
                // ... followed by a new-line.
                state.mZygoteOutputWriter.newLine();
                // ... followed by our only argument.
                state.mZygoteOutputWriter.write("--get-pid");
                state.mZygoteOutputWriter.newLine();
                state.mZygoteOutputWriter.flush();

                // The response is a length prefixed stream of ASCII bytes.
                int numBytes = state.mZygoteInputStream.readInt();
                byte[] bytes = new byte[numBytes];
                state.mZygoteInputStream.readFully(bytes);

                return Integer.parseInt(new String(bytes, StandardCharsets.US_ASCII));
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failure retrieving pid", ex);
        }
    }

    /**
     * Notify the Zygote processes that boot completed.
     */
    public void bootCompleted() {
        // Notify both the 32-bit and 64-bit zygote.
        if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            bootCompleted(Build.SUPPORTED_32_BIT_ABIS[0], ZygoteSelectionMode.Regular);
        }
        if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            bootCompleted(Build.SUPPORTED_64_BIT_ABIS[0], ZygoteSelectionMode.Regular);
            bootCompleted(Build.SUPPORTED_64_BIT_ABIS[0], ZygoteSelectionMode.PreferCompatZygote);
        }
    }

    private void bootCompleted(String abi, ZygoteSelectionMode zsm) {
        try {
            synchronized (mLock) {
                ZygoteState state = openZygoteSocketIfNeeded(abi, zsm);
                state.mZygoteOutputWriter.write("1\n--boot-completed\n");
                state.mZygoteOutputWriter.flush();
                state.mZygoteInputStream.readInt();
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to inform zygote of boot_completed", ex);
        }
    }

    /**
     * Push hidden API deny-listing exemptions into the zygote process(es).
     *
     * <p>The list of exemptions will take affect for all new processes forked from the zygote after
     * this call.
     *
     * @param exemptions List of hidden API exemption prefixes. Any matching members are treated as
     *        allowed/public APIs (i.e. allowed, no logging of usage).
     */
    public boolean setApiDenylistExemptions(List<String> exemptions) {
        synchronized (mLock) {
            mApiDenylistExemptions = exemptions;
            boolean ok = true;
            for (var type : ZygoteType.values()) {
                ZygoteState zygoteState = mZygoteStates[type.ordinal()];
                if (zygoteState == null && isLazilyStarted(type)) {
                    // maybeSetApiDenylistExemptions() is called during initial attemptConnectionToZygote()
                    continue;
                }

                if (!maybeSetApiDenylistExemptions(mZygoteStates[type.ordinal()], true)) {
                    ok = false;
                }
            }
            return ok;
        }
    }

    private static boolean isLazilyStarted(ZygoteType type) {
        switch (type) {
            case ZygoteType.Compat:
                return true;
            default:
                return false;
        }
    }

    /**
     * Set the precentage of detected hidden API accesses that are logged to the event log.
     *
     * <p>This rate will take affect for all new processes forked from the zygote after this call.
     *
     * @param rate An integer between 0 and 0x10000 inclusive. 0 means no event logging.
     */
    public void setHiddenApiAccessLogSampleRate(int rate) {
        synchronized (mLock) {
            mHiddenApiAccessLogSampleRate = rate;
            for (var type : ZygoteType.values()) {
                maybeSetHiddenApiAccessLogSampleRate(mZygoteStates[type.ordinal()]);
            }
        }
    }

    /**
     * Set the precentage of detected hidden API accesses that are logged to the new event log.
     *
     * <p>This rate will take affect for all new processes forked from the zygote after this call.
     *
     * @param rate An integer between 0 and 0x10000 inclusive. 0 means no event logging.
     */
    public void setHiddenApiAccessStatslogSampleRate(int rate) {
        synchronized (mLock) {
            mHiddenApiAccessStatslogSampleRate = rate;
            for (var type : ZygoteType.values()) {
                maybeSetHiddenApiAccessStatslogSampleRate(mZygoteStates[type.ordinal()]);
            }
        }
    }

    @GuardedBy("mLock")
    private boolean maybeSetApiDenylistExemptions(ZygoteState state, boolean sendIfEmpty) {
        if (state == null || state.isClosed()) {
            Slog.e(LOG_TAG, "Can't set API denylist exemptions: no zygote connection");
            return false;
        } else if (!sendIfEmpty && mApiDenylistExemptions.isEmpty()) {
            return true;
        }

        for (/* NonNull */ String s : mApiDenylistExemptions) {
            // indexOf() is intrinsified and faster than contains().
            if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\u0000') >= 0) {
                Slog.e(LOG_TAG, "Failed to set API denylist exemptions: Bad character");
                mApiDenylistExemptions = Collections.emptyList();
                return false;
            }
        }
        try {
            state.mZygoteOutputWriter.write(Integer.toString(mApiDenylistExemptions.size() + 1));
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--set-api-denylist-exemptions");
            state.mZygoteOutputWriter.newLine();
            for (int i = 0; i < mApiDenylistExemptions.size(); ++i) {
                state.mZygoteOutputWriter.write(mApiDenylistExemptions.get(i));
                state.mZygoteOutputWriter.newLine();
            }
            state.mZygoteOutputWriter.flush();
            int status = state.mZygoteInputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set API denylist exemptions; status " + status);
            }
            return true;
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set API denylist exemptions", ioe);
            mApiDenylistExemptions = Collections.emptyList();
            return false;
        }
    }

    private void maybeSetHiddenApiAccessLogSampleRate(ZygoteState state) {
        if (state == null || state.isClosed() || mHiddenApiAccessLogSampleRate == -1) {
            return;
        }

        try {
            state.mZygoteOutputWriter.write(Integer.toString(1));
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--hidden-api-log-sampling-rate="
                    + mHiddenApiAccessLogSampleRate);
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.flush();
            int status = state.mZygoteInputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set hidden API log sampling rate; status " + status);
            }
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set hidden API log sampling rate", ioe);
        }
    }

    private void maybeSetHiddenApiAccessStatslogSampleRate(ZygoteState state) {
        if (state == null || state.isClosed() || mHiddenApiAccessStatslogSampleRate == -1) {
            return;
        }

        try {
            state.mZygoteOutputWriter.write(Integer.toString(1));
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--hidden-api-statslog-sampling-rate="
                    + mHiddenApiAccessStatslogSampleRate);
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.flush();
            int status = state.mZygoteInputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set hidden API statslog sampling rate; status "
                        + status);
            }
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set hidden API statslog sampling rate", ioe);
        }
    }

    @GuardedBy("mLock")
    private ZygoteState attemptConnectionToZygote(ZygoteType type) throws IOException {
        int typeIdx = type.ordinal();
        ZygoteState zygoteState = mZygoteStates[typeIdx];
        if (zygoteState == null || zygoteState.isClosed()) {
            if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                Log.v(LOG_TAG, "attemptConnectionToZygote " + type, new Throwable());
            }
            zygoteState =
                    ZygoteState.connect(mZygoteSocketAddresses[typeIdx], mUsapPoolSocketAddresses[typeIdx]);
            mZygoteStates[typeIdx] = zygoteState;

            maybeSetApiDenylistExemptions(zygoteState, false);
            maybeSetHiddenApiAccessLogSampleRate(zygoteState);
        }
        return zygoteState;
    }

    /**
     * Creates a ZygoteState for the primary zygote if it doesn't exist or has been disconnected.
     */
    @GuardedBy("mLock")
    private ZygoteState attemptConnectionToPrimaryZygote() throws IOException {
        return attemptConnectionToZygote(ZygoteType.Primary);
    }

    /**
     * Creates a ZygoteState for the secondary zygote if it doesn't exist or has been disconnected.
     */
    @GuardedBy("mLock")
    private ZygoteState attemptConnectionToSecondaryZygote() throws IOException {
        return attemptConnectionToZygote(ZygoteType.Secondary);
    }

    /**
     * Tries to open a session socket to a Zygote process with a compatible ABI if one is not
     * already open. If a compatible session socket is already open that session socket is returned.
     * This function may block and may have to try connecting to multiple Zygotes to find the
     * appropriate one.  Requires that mLock be held.
     */
    @GuardedBy("mLock")
    private ZygoteState openZygoteSocketIfNeeded(String abi, ZygoteSelectionMode zsm) throws ZygoteStartFailedEx {
        try {
            ZygoteState primaryZygoteState = attemptConnectionToPrimaryZygote();
            if (primaryZygoteState.matches(abi)) {
                if (!mIsChildZygoteProcess && zsm == ZygoteSelectionMode.PreferCompatZygote) {
                    ZygoteState compatZygoteState = attemptConnectionToZygote(ZygoteType.Compat);
                    if (!compatZygoteState.matches(abi)) {
                        throw new IllegalStateException("primary and compat zygotes must match same ABIs");
                    }
                    return compatZygoteState;
                }
                return primaryZygoteState;
            }

            if (mZygoteSocketAddresses[ZygoteType.Secondary.ordinal()] != null) {
                // The primary zygote didn't match. Try the secondary.
                ZygoteState secondaryZygoteState = attemptConnectionToSecondaryZygote();

                if (secondaryZygoteState.matches(abi)) {
                    return secondaryZygoteState;
                }
            }
        } catch (IOException ioe) {
            throw new ZygoteStartFailedEx("Error connecting to zygote", ioe);
        }

        throw new ZygoteStartFailedEx("Unsupported zygote ABI: " + abi);
    }

    /**
     * Updates the timeout used when preloading code in the app-zygote
     *
     * @param timeoutMs timeout in milliseconds
     */
    public static void setAppZygotePreloadTimeout(int timeoutMs) {
        Slog.i(LOG_TAG, "Changing app-zygote preload timeout to " + timeoutMs + " ms.");

        sAppZygotePreloadTimeoutMs = timeoutMs;
    }

    @Override
    public boolean preloadApp(ApplicationInfo appInfo, String abi, ZygoteSelectionMode zsm)
            throws ZygoteStartFailedEx, IOException {
        synchronized (mLock) {
            int ret;
            ZygoteState state = openZygoteSocketIfNeeded(abi, zsm);
            int previousSocketTimeout = state.mZygoteSessionSocket.getSoTimeout();

            try {
                state.mZygoteSessionSocket.setSoTimeout(sAppZygotePreloadTimeoutMs);

                state.mZygoteOutputWriter.write("2");
                state.mZygoteOutputWriter.newLine();

                state.mZygoteOutputWriter.write("--preload-app");
                state.mZygoteOutputWriter.newLine();

                // Zygote args needs to be strings, so in order to pass ApplicationInfo,
                // write it to a Parcel, and base64 the raw Parcel bytes to the other side.
                Parcel parcel = Parcel.obtain();
                appInfo.writeToParcel(parcel, 0 /* flags */);
                String encodedParcelData = Base64.getEncoder().encodeToString(parcel.marshall());
                parcel.recycle();
                state.mZygoteOutputWriter.write(encodedParcelData);
                state.mZygoteOutputWriter.newLine();

                state.mZygoteOutputWriter.flush();

                ret = state.mZygoteInputStream.readInt();
            } finally {
                state.mZygoteSessionSocket.setSoTimeout(previousSocketTimeout);
            }
            return ret == 0;
        }
    }

    /**
     * Instructs the zygote to preload the default set of classes and resources. Returns
     * {@code true} if a preload was performed as a result of this call, and {@code false}
     * otherwise. The latter usually means that the zygote eagerly preloaded at startup
     * or due to a previous call to {@code preloadDefault}. Note that this call is synchronous.
     */
    public boolean preloadDefault(String abi) throws ZygoteStartFailedEx, IOException {
        synchronized (mLock) {
            ZygoteState state = openZygoteSocketIfNeeded(abi,
                    // preloadDefault(abi) is called only for 32-bit zygote
                    ZygoteSelectionMode.Regular);
            return preloadDefault(state);
        }
    }

    boolean preloadDefault(ZygoteState state) throws IOException {
        synchronized (mLock) {
            // Each query starts with the argument count (1 in this case)
            state.mZygoteOutputWriter.write("1");
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--preload-default");
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.flush();

            return (state.mZygoteInputStream.readInt() == 0);
        }
    }

    /**
     * Try connecting to the Zygote over and over again until we hit a time-out.
     * @param zygoteType The type of the zygote to connect to.
     */
    public static void waitForConnectionToZygote(ZygoteType zygoteType) {
        final LocalSocketAddress zygoteSocketAddress =
                new LocalSocketAddress(zygoteType.getSocketName(), LocalSocketAddress.Namespace.RESERVED);
        waitForConnectionToZygote(zygoteSocketAddress);
    }

    /**
     * Try connecting to the Zygote over and over again until we hit a time-out.
     * @param zygoteSocketAddress The name of the socket to connect to.
     */
    public static void waitForConnectionToZygote(LocalSocketAddress zygoteSocketAddress) {
        waitForConnectionToZygote(zygoteSocketAddress, false);
    }

    /**
     * Try connecting to the Native Zygote over and over again until we hit a time-out.
     * @param zygoteSocketAddress The name of the socket to connect to.
     */
    public static void waitForConnectionToNativeZygote(LocalSocketAddress zygoteSocketAddress) {
        waitForConnectionToZygote(zygoteSocketAddress, true);
    }

    private static void waitForConnectionToZygote(LocalSocketAddress zygoteSocketAddress,
                                                  boolean isNativeZygote) {
        int numRetries = ZYGOTE_CONNECT_TIMEOUT_MS / ZYGOTE_CONNECT_RETRY_DELAY_MS;
        for (int n = numRetries; n >= 0; n--) {
            try {
                if (isNativeZygote) {
                    final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
                    socket.connect(zygoteSocketAddress);
                    socket.close();
                } else {
                    final ZygoteState zs = ZygoteState.connect(zygoteSocketAddress, null);
                    zs.close();
                }
                return;
            } catch (IOException ioe) {
                Log.w(LOG_TAG,
                        "Got error connecting to zygote, retrying. msg= " + ioe.getMessage(), ioe);
            }

            try {
                Thread.sleep(ZYGOTE_CONNECT_RETRY_DELAY_MS);
            } catch (InterruptedException ignored) { }
        }
        Slog.wtf(LOG_TAG, "Failed to connect to Zygote through socket "
                + zygoteSocketAddress.getName());
    }

    /**
     * Sends messages to the zygotes telling them to change the status of their USAP pools.  If
     * this notification fails the ZygoteProcess will fall back to the previous behavior.
     */
    private void informZygotesOfUsapPoolStatus() {
        final String command = "1\n--usap-pool-enabled=" + mUsapPoolEnabled + "\n";

        synchronized (mLock) {
            for (var type : ZygoteType.values()) {
                if (mZygoteSocketAddresses[type.ordinal()] != null) {
                    try {
                        ZygoteState zygoteState = attemptConnectionToZygote(type);
                        zygoteState.mZygoteOutputWriter.write(command);
                        zygoteState.mZygoteOutputWriter.flush();
                    } catch (IOException e) {
                        mUsapPoolEnabled = !mUsapPoolEnabled;
                        Log.w(LOG_TAG, "Failed to inform zygote " + type + " of USAP pool status", e);
                        if (type == ZygoteType.Primary) {
                            return;
                        }
                    }
                }
            }

            for (var type : ZygoteType.values()) {
                var state = mZygoteStates[type.ordinal()];
                if (state != null) {
                    try {
                        // Wait for zygote to finish its work.
                        state.mZygoteInputStream.readInt();
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "USAP pool state change cause an irrecoverable error",
                                e);
                    }
                }
            }
        }
    }

    @Override
    public ChildZygoteProcess startChildZygote(final ZygoteExtraArgs zygoteExtArgs, final String processClass,
                                               final String niceName,
                                               int uid, int gid, int[] gids,
                                               int runtimeFlags,
                                               String seInfo,
                                               String abi,
                                               String acceptedAbiList,
                                               String instructionSet,
                                               int uidRangeStart,
                                               int uidRangeEnd,
                                               ApplicationInfo unused) {
        // Create an unguessable address in the global abstract namespace.
        final LocalSocketAddress serverAddress = new LocalSocketAddress(
                processClass + "/" + UUID.randomUUID().toString());

        final String[] extraArgs = {Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG + serverAddress.getName(),
                                    Zygote.CHILD_ZYGOTE_ABI_LIST_ARG + acceptedAbiList,
                                    Zygote.CHILD_ZYGOTE_UID_RANGE_START + uidRangeStart,
                                    Zygote.CHILD_ZYGOTE_UID_RANGE_END + uidRangeEnd};

        Process.ProcessStartResult result;
        try {
            // We will bind mount app data dirs so app zygote can't access /data/data, while
            // we don't need to bind mount storage dirs as /storage won't be mounted.
            result = startViaZygote(zygoteExtArgs, processClass, niceName, uid, gid,
                    gids, runtimeFlags, 0 /* mountExternal */, 0 /* targetSdkVersion */, seInfo,
                    abi, instructionSet, null /* appDataDir */, null /* invokeWith */,
                    true /* startChildZygote */, null /* packageName */,
                    ZYGOTE_POLICY_FLAG_SYSTEM_PROCESS /* zygotePolicyFlags */, false /* isTopApp */,
                    null /* disabledCompatChanges */, null /* enabledCompatChanges */,
                    true /* useDeliQueue */,
                    null /* pkgDataInfoMap */, null /* allowlistedDataInfoList */,
                    true /* bindMountAppsData*/, /* bindMountAppStorageDirs */ false,
                    /*bindMountOverrideSysprops */ false, /* startSeq */ 0, extraArgs);

        } catch (ZygoteStartFailedEx ex) {
            throw new RuntimeException("Starting child-zygote through Zygote failed", ex);
        }

        return ChildZygoteProcess.createManagedChildZygoteProcess(serverAddress, result.pid, uid);
    }
}
