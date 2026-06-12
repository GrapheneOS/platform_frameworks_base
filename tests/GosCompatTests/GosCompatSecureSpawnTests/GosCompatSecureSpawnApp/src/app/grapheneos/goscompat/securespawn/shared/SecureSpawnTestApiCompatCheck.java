package app.grapheneos.goscompat.securespawn.shared;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class SecureSpawnTestApiCompatCheck {
    // From libcore/libart/src/main/java/dalvik/system/VMRuntime.java:
    // private static final long ALLOW_TEST_API_ACCESS = 166236554;
    public static final long ALLOW_TEST_API_ACCESS_CHANGE_ID = 166236554L;

    // These members are blocked,test-api in out/soong/hiddenapi/hiddenapi-flags.csv.
    private static final Candidate[] CANDIDATES = {
            /*
             * From frameworks/base/core/java/android/app/Activity.java:
             *
             * @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
             * @TestApi
             * public final boolean addDumpable(@NonNull Dumpable dumpable)
             */
            Candidate.method("android.app.Activity", "addDumpable", "android.util.Dumpable"),
            Candidate.field("android.app.Activity", "DUMP_ARG_DUMP_DUMPABLE", "--dump-dumpable"),
            Candidate.field("android.app.Activity", "DUMP_ARG_LIST_DUMPABLES", "--list-dumpables"),
            Candidate.field("android.Manifest$permission", "ACCESSIBILITY_MOTION_EVENT_OBSERVING",
                    "android.permission.ACCESSIBILITY_MOTION_EVENT_OBSERVING"),
    };

    private SecureSpawnTestApiCompatCheck() {
    }

    public static TestApiCompat run(boolean execSpawned) {
        return new TestApiCompat(
                execSpawned,
                ALLOW_TEST_API_ACCESS_CHANGE_ID,
                compatChangeEnabled(ALLOW_TEST_API_ACCESS_CHANGE_ID),
                probeCandidates());
    }

    private static AccessResult probeCandidates() {
        AccessResult lastMissing = null;
        for (Candidate candidate : CANDIDATES) {
            AccessResult result = probeCandidate(candidate);
            if (result.outcome() != AccessOutcome.MEMBER_NOT_PRESENT) {
                return result;
            }
            lastMissing = result;
        }
        return lastMissing == null
                ? AccessResult.failed("<none>", "no candidates configured")
                : lastMissing;
    }

    private static AccessResult probeCandidate(Candidate candidate) {
        Class<?> clazz;
        try {
            clazz = Class.forName(candidate.className());
        } catch (ClassNotFoundException e) {
            return AccessResult.missing(candidate, e);
        } catch (LinkageError e) {
            return AccessResult.failed(candidate, e);
        }

        if (candidate.isMethod()) {
            return probeMethodCandidate(clazz, candidate);
        } else {
            return probeFieldCandidate(clazz, candidate);
        }
    }

    private static AccessResult probeFieldCandidate(Class<?> clazz, Candidate candidate) {
        try {
            /*
             * Hidden/test API enforcement happens in this lookup. Class.getDeclaredField() is
             * native on Android and reaches ART's Class_getDeclaredField(), which throws
             * NoSuchFieldException when ShouldDenyAccessToMember(result->GetArtField(), ...)
             * rejects the blocked,test-api member. ACCESS_DENIED should exit through the catch
             * below before the probe can read the public static field.
             */
            Field field;
            field = clazz.getDeclaredField(candidate.memberName());
            Object value = field.get(null);
            String actualValue = String.valueOf(value);
            if (!candidate.expectedValue().equals(actualValue)) {
                return AccessResult.failed(candidate,
                        "expected value " + candidate.expectedValue()
                                + " but was " + actualValue);
            }
            return AccessResult.allowed(candidate, "value=" + actualValue);
        } catch (NoSuchFieldException e) {
            return AccessResult.denied(candidate, e);
        } catch (SecurityException e) {
            return AccessResult.denied(candidate, e);
        } catch (IllegalAccessException | RuntimeException e) {
            return AccessResult.denied(candidate, e);
        }
    }

    private static AccessResult probeMethodCandidate(Class<?> clazz, Candidate candidate) {
        Class<?>[] parameterTypes = new Class<?>[candidate.parameterClassNames().length];
        for (int i = 0; i < parameterTypes.length; ++i) {
            try {
                parameterTypes[i] = Class.forName(candidate.parameterClassNames()[i]);
            } catch (ClassNotFoundException e) {
                return AccessResult.missing(candidate, e);
            } catch (LinkageError e) {
                return AccessResult.failed(candidate, e);
            }
        }

        try {
            /*
             * Hidden/test API enforcement happens in this lookup. On Android,
             * Class.getDeclaredMethod() reaches ART's Class_getDeclaredMethodInternal(), which
             * returns null when ShouldDenyAccessToMember(result->GetArtMethod(), ...) rejects the
             * blocked,test-api member. Class.getMethod() then translates the null result into
             * NoSuchMethodException, so ACCESS_DENIED should exit through the catch below before
             * the probe can inspect the method.
             */
            Method method;
            method = clazz.getDeclaredMethod(candidate.memberName(), parameterTypes);
            return AccessResult.allowed(candidate,
                    "returnType=" + method.getReturnType().getName());
        } catch (NoSuchMethodException e) {
            return AccessResult.denied(candidate, e);
        } catch (SecurityException e) {
            return AccessResult.denied(candidate, e);
        } catch (RuntimeException e) {
            return AccessResult.denied(candidate, e);
        }
    }

    private static String compatChangeEnabled(long changeId) {
        try {
            Class<?> compatibility = Class.forName("android.compat.Compatibility");
            Object enabled = compatibility.getMethod("isChangeEnabled", long.class)
                    .invoke(null, changeId);
            return String.valueOf(enabled);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException | RuntimeException e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static String exceptionDetail(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ":" + message);
    }

    private record Candidate(
            String className,
            String memberName,
            String expectedValue,
            String[] parameterClassNames) {
        static Candidate field(String className, String fieldName, String expectedValue) {
            return new Candidate(className, fieldName, expectedValue, null);
        }

        static Candidate method(String className, String methodName,
                String... parameterClassNames) {
            return new Candidate(className, methodName, null, parameterClassNames);
        }

        boolean isMethod() {
            return parameterClassNames() != null;
        }

        @Override
        public String toString() {
            if (!isMethod()) {
                return className() + "#" + memberName();
            }

            return className() + "#" + memberName() + "("
                    + String.join(",", parameterClassNames()) + ")";
        }
    }

    public enum AccessOutcome {
        ACCESS_ALLOWED,
        ACCESS_DENIED,
        MEMBER_NOT_PRESENT,
        ACCESS_FAILED,
    }

    public record AccessResult(
            String candidate,
            AccessOutcome outcome,
            String detail) {
        static AccessResult allowed(Candidate candidate, String detail) {
            return new AccessResult(candidate.toString(), AccessOutcome.ACCESS_ALLOWED,
                    detail);
        }

        static AccessResult denied(Candidate candidate, Throwable t) {
            return new AccessResult(candidate.toString(), AccessOutcome.ACCESS_DENIED,
                    exceptionDetail(t));
        }

        static AccessResult missing(Candidate candidate, Throwable t) {
            return new AccessResult(candidate.toString(), AccessOutcome.MEMBER_NOT_PRESENT,
                    exceptionDetail(t));
        }

        static AccessResult failed(Candidate candidate, Throwable t) {
            return new AccessResult(candidate.toString(), AccessOutcome.ACCESS_FAILED,
                    exceptionDetail(t));
        }

        static AccessResult failed(Candidate candidate, String detail) {
            return new AccessResult(candidate.toString(), AccessOutcome.ACCESS_FAILED, detail);
        }

        static AccessResult failed(String candidate, String detail) {
            return new AccessResult(candidate, AccessOutcome.ACCESS_FAILED, detail);
        }
    }

    public record TestApiCompat(
            boolean execSpawned,
            long allowTestApiAccessChangeId,
            String frameworkCompatEnabled,
            AccessResult accessResult) {
        public boolean accessAllowed() {
            return accessResult().outcome() == AccessOutcome.ACCESS_ALLOWED;
        }

        @Override
        public String toString() {
            return "execSpawned=" + execSpawned()
                    + "\nallowTestApiAccessChangeId=" + allowTestApiAccessChangeId()
                    + "\nframeworkCompatEnabled=" + frameworkCompatEnabled()
                    + "\ncandidate=" + accessResult().candidate()
                    + "\noutcome=" + accessResult().outcome()
                    + "\ndetail=" + accessResult().detail()
                    + "\naccessAllowed=" + accessAllowed();
        }
    }
}
