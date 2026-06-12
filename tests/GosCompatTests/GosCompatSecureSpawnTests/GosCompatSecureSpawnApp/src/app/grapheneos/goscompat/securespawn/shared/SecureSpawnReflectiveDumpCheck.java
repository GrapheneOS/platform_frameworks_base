package app.grapheneos.goscompat.securespawn.shared;

import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class SecureSpawnReflectiveDumpCheck {
    private static final String ROOT_CAUSE_LOG_TAG = "GosCompatSecureSpawnRc";
    // Deep enough to produce meaningful recursive traversal symptoms without intentionally
    // exhausting the Java thread stack on a correct hidden API path.
    private static final int ACYCLIC_FIXTURE_DEPTH = 16;
    private static final int STACK_TRACE_SAMPLE_FRAMES = 24;
    private static final int ROOT_CAUSE_LOG_LINE_LIMIT = 200;
    private static final int MAX_REPORTED_PATH_LENGTH = 240;
    private static final long ARRAYS_AS_LIST_TO_ARRAY_CHANGE_ID = 202956589L;
    private static final long OVERRIDDEN_THREAD_START_CHANGE_ID = 418924588L;
    private static final String ARRAYS_ARRAY_LIST_CLASS = "java.util.Arrays$ArrayList";
    private static final String ARRAY_LIST_CLASS = "java.util.ArrayList";
    private static final String COMPATIBILITY_CLASS = "android.compat.Compatibility";
    private static final AtomicInteger sNextAttemptId = new AtomicInteger();

    private SecureSpawnReflectiveDumpCheck() {
    }

    public static AcyclicReflectiveDump run(boolean execSpawned) {
        ReflectiveDumpWorker worker = new ReflectiveDumpWorker(
                sNextAttemptId.incrementAndGet(), execSpawned);
        Thread thread = new Thread(worker);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for reflective dump probe", e);
        }
        return worker.result();
    }

    private static ReflectiveDumpResult runAcyclicDump(DumpTrace trace) {
        try {
            String result = dumpObject(new RecursiveRequest(buildNodeChain(ACYCLIC_FIXTURE_DEPTH)),
                    trace, 0, "request");
            trace.log("completed resultLength=" + result.length());
            return ReflectiveDumpResult.completed(result.length());
        } catch (StackOverflowError e) {
            trace.log("stackOverflow message=" + safeString(e.getMessage()));
            return ReflectiveDumpResult.failed(e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            trace.log("failure throwable=" + e.getClass().getName()
                    + " message=" + safeString(e.getMessage()));
            return ReflectiveDumpResult.failed(e);
        }
    }

    private static Node buildNodeChain(int depth) {
        Node node = null;
        for (int i = 0; i < depth; i++) {
            node = new Node("node", node);
        }
        return node;
    }

    private static List<Field> getAllFields(Class<?> cls, DumpTrace trace, int recursionDepth) {
        ArrayList<Field> fields = new ArrayList<>();
        while (cls != null) {
            Field[] declaredFields = cls.getDeclaredFields();
            trace.log("getAllFields recursionDepth=" + recursionDepth
                    + " class=" + cls.getName()
                    + " declaredFieldCount=" + declaredFields.length
                    + " fields=" + describeFields(declaredFields));
            fields.addAll(Arrays.asList(declaredFields));
            cls = cls.getSuperclass();
        }
        return fields;
    }

    private static String dumpObject(Object obj, DumpTrace trace, int recursionDepth, String path)
            throws ReflectiveOperationException {
        if (obj == null) {
            trace.log("dumpObject recursionDepth=" + recursionDepth
                    + " path=" + path
                    + " object=null");
            return "";
        }
        trace.log("dumpObject recursionDepth=" + recursionDepth
                + " path=" + path
                + " class=" + obj.getClass().getName()
                + " identity=" + identityString(obj)
                + " " + trace.seenInfo(obj, path));
        if (obj instanceof String) {
            return (String) obj;
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return String.valueOf(obj);
        }
        if (obj instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder();
            for (Object key : map.keySet()) {
                String keyPath = trace.childPath(path, "<mapKey>");
                sb.append(dumpObject(key, trace, recursionDepth + 1, keyPath));
                sb.append(dumpObject(map.get(key), trace, recursionDepth + 1,
                        trace.childPath(path, "<mapValue>")));
            }
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder();
        for (Field field : getAllFields(obj.getClass(), trace, recursionDepth)) {
            String fieldPath = trace.childPath(path, field.getName());
            try {
                field.setAccessible(true);
                trace.log("field recursionDepth=" + recursionDepth
                        + " path=" + fieldPath
                        + " owner=" + field.getDeclaringClass().getName()
                        + " name=" + field.getName()
                        + " modifiers=" + modifierString(field.getModifiers())
                        + " synthetic=" + field.isSynthetic()
                        + " type=" + field.getType().getName()
                        + " setAccessible=ok");
            } catch (RuntimeException e) {
                trace.log("field recursionDepth=" + recursionDepth
                        + " path=" + fieldPath
                        + " owner=" + field.getDeclaringClass().getName()
                        + " name=" + field.getName()
                        + " setAccessible=failed:"
                        + e.getClass().getSimpleName());
                throw e;
            }
            Object value = field.get(obj);
            trace.log("fieldValue recursionDepth=" + recursionDepth
                    + " path=" + fieldPath
                    + " valueClass=" + className(value)
                    + " valueIdentity=" + identityString(value)
                    + " " + trace.peekSeenInfo(value));
            sb.append(field.getName());
            sb.append('{');
            sb.append(dumpObject(value, trace, recursionDepth + 1, fieldPath));
            sb.append('}');
        }
        return sb.toString();
    }

    private static boolean isRootCauseLoggingEnabled() {
        return Log.isLoggable(ROOT_CAUSE_LOG_TAG, Log.DEBUG);
    }

    private static String describeFields(Field[] fields) {
        if (fields.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Field field = fields[i];
            sb.append(field.getName())
                    .append(':')
                    .append(field.getType().getName())
                    .append(":modifiers=")
                    .append(modifierString(field.getModifiers()))
                    .append(":synthetic=")
                    .append(field.isSynthetic());
        }
        return sb.append(']').toString();
    }

    private static String modifierString(int modifiers) {
        String value = Modifier.toString(modifiers);
        return value.isEmpty() ? "<none>" : value;
    }

    private static String className(Object obj) {
        return obj == null ? "null" : obj.getClass().getName();
    }

    private static String identityString(Object obj) {
        if (obj == null) {
            return "null";
        }
        return "0x" + Integer.toHexString(System.identityHashCode(obj));
    }

    private static String currentTargetSdkVersion() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            Object targetSdkVersion =
                    vmRuntime.getMethod("getTargetSdkVersion").invoke(runtime);
            return String.valueOf(targetSdkVersion);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static String compatChangeEnabled(long changeId) {
        try {
            Class<?> compatibility = Class.forName("android.compat.Compatibility");
            Object enabled = compatibility.getMethod("isChangeEnabled", long.class)
                    .invoke(null, changeId);
            return String.valueOf(enabled);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static final class ReflectiveDumpWorker implements Runnable {
        private final int mAttemptId;
        private final boolean mExecSpawned;
        private AcyclicReflectiveDump mResult;
        private Throwable mFailure;

        ReflectiveDumpWorker(int attemptId, boolean execSpawned) {
            mAttemptId = attemptId;
            mExecSpawned = execSpawned;
        }

        @Override
        public void run() {
            DumpTrace trace = DumpTrace.create(mAttemptId);
            trace.logWorkerStart(mExecSpawned);
            try {
                ReflectiveDumpResult dumpResult = runAcyclicDump(trace);
                mResult = new AcyclicReflectiveDump(
                        mExecSpawned,
                        Thread.currentThread().getName(),
                        Process.myTid(),
                        ACYCLIC_FIXTURE_DEPTH,
                        dumpResult.completed(),
                        dumpResult.resultLength(),
                        dumpResult.failureClass(),
                        dumpResult.failureMessage(),
                        dumpResult.dumpObjectFrames(),
                        dumpResult.getAllFieldsFrames(),
                        dumpResult.arraysToArrayFrames(),
                        dumpResult.arrayListAddAllFrames(),
                        dumpResult.compatibilityFrames(),
                        dumpResult.stackTraceSample());
            } catch (Throwable e) {
                trace.log("workerFailure throwable=" + e.getClass().getName()
                        + " message=" + safeString(e.getMessage()));
                mFailure = e;
            }
        }

        AcyclicReflectiveDump result() {
            if (mFailure instanceof RuntimeException) {
                throw (RuntimeException) mFailure;
            }
            if (mFailure instanceof Error) {
                throw (Error) mFailure;
            }
            if (mFailure != null) {
                throw new IllegalStateException("Reflective dump probe failed", mFailure);
            }
            if (mResult == null) {
                throw new IllegalStateException("Reflective dump probe did not report a result");
            }
            return mResult;
        }
    }

    private static final class DumpTrace {
        private final int mAttemptId;
        private final boolean mEnabled;
        private final IdentityHashMap<Object, String> mSeen;
        private int mLines;
        private boolean mTruncated;

        private DumpTrace(int attemptId, boolean enabled) {
            mAttemptId = attemptId;
            mEnabled = enabled;
            mSeen = enabled ? new IdentityHashMap<>() : null;
        }

        static DumpTrace create(int attemptId) {
            return new DumpTrace(attemptId, isRootCauseLoggingEnabled());
        }

        void logWorkerStart(boolean execSpawned) {
            log("workerStart"
                    + " execSpawnedArg=" + execSpawned
                    + " envExecSpawned="
                    + safeString(System.getenv("IS_EXEC_SPAWNED_APP_PROCESS"))
                    + " envDisableHardenedMalloc="
                    + safeString(System.getenv("DISABLE_HARDENED_MALLOC"))
                    + " threadName=" + Thread.currentThread().getName()
                    + " threadTid=" + Process.myTid()
                    + " pid=" + Process.myPid()
                    + " targetSdk=" + currentTargetSdkVersion()
                    + " arraysAsListToArrayChange="
                    + compatChangeEnabled(ARRAYS_AS_LIST_TO_ARRAY_CHANGE_ID)
                    + " overriddenThreadStartChange="
                    + compatChangeEnabled(OVERRIDDEN_THREAD_START_CHANGE_ID));
        }

        void log(String message) {
            if (!mEnabled) {
                return;
            }
            if (mLines >= ROOT_CAUSE_LOG_LINE_LIMIT) {
                if (!mTruncated) {
                    Log.d(ROOT_CAUSE_LOG_TAG, prefix()
                            + "logLineLimitReached limit=" + ROOT_CAUSE_LOG_LINE_LIMIT);
                    mTruncated = true;
                }
                return;
            }
            Log.d(ROOT_CAUSE_LOG_TAG, prefix() + message);
            mLines++;
        }

        String seenInfo(Object obj, String path) {
            if (!mEnabled || obj == null) {
                return "seenBefore=false";
            }
            String firstPath = mSeen.get(obj);
            if (firstPath != null) {
                return "seenBefore=true firstPath=" + firstPath;
            }
            mSeen.put(obj, path);
            return "seenBefore=false";
        }

        String peekSeenInfo(Object obj) {
            if (!mEnabled || obj == null) {
                return "seenBefore=false";
            }
            String firstPath = mSeen.get(obj);
            if (firstPath == null) {
                return "seenBefore=false";
            }
            return "seenBefore=true firstPath=" + firstPath;
        }

        String childPath(String path, String child) {
            if (!mEnabled) {
                return "";
            }
            String next = path.isEmpty() ? child : path + "." + child;
            if (next.length() <= MAX_REPORTED_PATH_LENGTH) {
                return next;
            }
            return next.substring(0, MAX_REPORTED_PATH_LENGTH) + "...";
        }

        private String prefix() {
            return "attempt=" + mAttemptId + " ";
        }
    }

    private record ReflectiveDumpResult(
            boolean completed,
            int resultLength,
            String failureClass,
            String failureMessage,
            int dumpObjectFrames,
            int getAllFieldsFrames,
            int arraysToArrayFrames,
            int arrayListAddAllFrames,
            int compatibilityFrames,
            String stackTraceSample) {
        static ReflectiveDumpResult completed(int resultLength) {
            return new ReflectiveDumpResult(
                    true, resultLength, "", "", 0, 0, 0, 0, 0, "");
        }

        static ReflectiveDumpResult failed(Throwable t) {
            StackTraceElement[] stack = t.getStackTrace();
            String checkClass = SecureSpawnReflectiveDumpCheck.class.getName();
            return new ReflectiveDumpResult(
                    false,
                    0,
                    t.getClass().getName(),
                    safeString(t.getMessage()),
                    countFrames(stack, checkClass, "dumpObject"),
                    countFrames(stack, checkClass, "getAllFields"),
                    countFrames(stack, ARRAYS_ARRAY_LIST_CLASS, "toArray"),
                    countFrames(stack, ARRAY_LIST_CLASS, "addAll"),
                    countFrames(stack, COMPATIBILITY_CLASS, "isChangeEnabled"),
                    sampleFrames(stack));
        }

        private static int countFrames(
                StackTraceElement[] stack, String className, String methodName) {
            int count = 0;
            for (StackTraceElement frame : stack) {
                if (className.equals(frame.getClassName())
                        && methodName.equals(frame.getMethodName())) {
                    count++;
                }
            }
            return count;
        }

        private static String sampleFrames(StackTraceElement[] stack) {
            StringBuilder sample = new StringBuilder();
            int frameCount = Math.min(stack.length, STACK_TRACE_SAMPLE_FRAMES);
            for (int i = 0; i < frameCount; i++) {
                if (i > 0) {
                    sample.append('\n');
                }
                sample.append(i).append(' ').append(stack[i]);
            }
            if (stack.length > frameCount) {
                if (sample.length() > 0) {
                    sample.append('\n');
                }
                sample.append("... totalFrames=").append(stack.length);
            }
            return sample.toString();
        }
    }

    private static final class RecursiveRequest {
        private final String clientId = "client";
        private final String username = "user";
        private final String password = "secret";
        private final Node root;

        RecursiveRequest(Node root) {
            this.root = root;
        }
    }

    private static final class Node {
        private final String name;
        private final Node child;

        Node(String name, Node child) {
            this.name = name;
            this.child = child;
        }
    }

    public record AcyclicReflectiveDump(
            boolean execSpawned,
            String threadName,
            int threadTid,
            int fixtureDepth,
            boolean completed,
            int resultLength,
            String failureClass,
            String failureMessage,
            int dumpObjectFrames,
            int getAllFieldsFrames,
            int arraysToArrayFrames,
            int arrayListAddAllFrames,
            int compatibilityFrames,
            String stackTraceSample) {
        @Override
        public String toString() {
            return "execSpawned=" + execSpawned()
                    + "\nthreadName=" + threadName()
                    + "\nthreadTid=" + threadTid()
                    + "\nfixtureDepth=" + fixtureDepth()
                    + "\ncompleted=" + completed()
                    + "\nresultLength=" + resultLength()
                    + "\nfailureClass=" + failureClass()
                    + "\nfailureMessage=" + failureMessage()
                    + "\ndumpObjectFrames=" + dumpObjectFrames()
                    + "\ngetAllFieldsFrames=" + getAllFieldsFrames()
                    + "\narraysToArrayFrames=" + arraysToArrayFrames()
                    + "\narrayListAddAllFrames=" + arrayListAddAllFrames()
                    + "\ncompatibilityFrames=" + compatibilityFrames()
                    + "\nstackTraceSample=" + stackTraceSample();
        }
    }
}
