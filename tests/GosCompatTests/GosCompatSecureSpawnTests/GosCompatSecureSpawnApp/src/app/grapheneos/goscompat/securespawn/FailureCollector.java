package app.grapheneos.goscompat.securespawn;

import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/** Collects readable failures separately from their diagnostic exception traces. */
public final class FailureCollector implements TestRule {
    public static final String SUMMARY_KEY = "secureSpawnFailureSummary";
    public static final String DIAGNOSTICS_KEY = "secureSpawnFailureDiagnostics";

    public interface Reporter {
        void report(String summary, String diagnostics);
    }

    private record Failure(String label, Throwable exception) {}

    private final List<Failure> failures = new ArrayList<>();
    private final String reportName;
    private final Reporter reporter;

    public FailureCollector() {
        this(null, null);
    }

    public FailureCollector(String reportName, Reporter reporter) {
        this.reportName = reportName;
        this.reporter = reporter;
    }

    public <T> T checkSucceeds(Callable<T> check) {
        return checkSucceeds(null, check);
    }

    public <T> T checkSucceeds(String label, Callable<T> check) {
        try {
            return check.call();
        } catch (Throwable failure) {
            addFailure(label, failure);
            return null;
        }
    }

    void expectEquals(String label, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            addFailure(label, new AssertionError("  expected: " + expected + "\n  actual:   " + actual));
        }
    }

    private void addFailure(String label, Throwable failure) {
        if (failure instanceof MultipleFailureException) {
            for (Throwable child : ((MultipleFailureException) failure).getFailures()) {
                addFailure(label, child);
            }
        } else {
            failures.add(new Failure(label, failure));
        }
    }

    /** Carries device details without embedding its serialized stack trace in another message. */
    public static AssertionError reportedFailure(String summary, String diagnostics) {
        return new ReportedFailure(summary, diagnostics);
    }

    private static final class ReportedFailure extends AssertionError {
        final String diagnostics;

        ReportedFailure(String summary, String diagnostics) {
            super(summary);
            this.diagnostics = diagnostics;
            setStackTrace(new StackTraceElement[0]);
        }
    }

    private static Set<Throwable> visited() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void appendSummary(StringBuilder output, Throwable failure, String covered,
            Set<Throwable> seen) {
        if (!seen.add(failure)) {
            return;
        }
        String message = failure instanceof AssertionError && failure.getMessage() != null
                ? failure.getMessage() : failure.toString();
        // Wrappers often repeat their cause's message. Suppressed failures remain independent.
        if (!covered.contains(message)) {
            if (output.length() != 0) {
                output.append('\n');
            }
            output.append(message);
        }
        if (failure.getCause() != null) {
            appendSummary(output, failure.getCause(), covered + "\n" + message, seen);
        }
        for (Throwable extra : failure.getSuppressed()) {
            appendSummary(output, extra, "", seen);
        }
    }

    public static String diagnostics(Throwable failure) {
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        failure.printStackTrace(writer);
        appendDeviceDiagnostics(writer, failure, visited());
        return output.toString();
    }

    private static void appendDeviceDiagnostics(PrintWriter output, Throwable failure,
            Set<Throwable> seen) {
        if (!seen.add(failure)) {
            return;
        }
        if (failure instanceof ReportedFailure reported && reported.diagnostics != null) {
            output.println("Device diagnostics:");
            output.println(reported.diagnostics);
        }
        if (failure.getCause() != null) {
            appendDeviceDiagnostics(output, failure.getCause(), seen);
        }
        for (Throwable extra : failure.getSuppressed()) {
            appendDeviceDiagnostics(output, extra, seen);
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                failures.clear();
                try {
                    base.evaluate();
                } catch (AssumptionViolatedException assumption) {
                    if (failures.isEmpty()) {
                        throw assumption;
                    }
                    addFailure(null, assumption);
                } catch (Throwable failure) {
                    // Keep earlier comparisons even if the body or teardown subsequently throws.
                    addFailure(null, failure);
                }
                if (failures.isEmpty()) {
                    return;
                }
                StringBuilder summary = new StringBuilder();
                StringBuilder traces = new StringBuilder();
                for (int i = 0; i < failures.size(); ++i) {
                    Failure failure = failures.get(i);
                    String heading = (i + 1) + ") "
                            + (failure.label() == null ? "" : failure.label() + "\n");
                    StringBuilder detail = new StringBuilder();
                    appendSummary(detail, failure.exception(), "", visited());
                    if (i != 0) {
                        summary.append("\n\n");
                    }
                    summary.append(heading);
                    if (failure.label() != null && failure.exception() instanceof ReportedFailure) {
                        summary.append("    ").append(detail.toString().replace("\n", "\n    "));
                    } else {
                        summary.append(detail);
                    }
                    traces.append(heading).append(diagnostics(failure.exception())).append('\n');
                }
                String message = failures.size() + " failure group(s)";
                if (reporter == null) {
                    message += "\n\n" + summary;
                } else {
                    try {
                        reporter.report(summary.toString(), traces.toString());
                        message += "; see " + reportName + "\n\n" + summary;
                    } catch (Throwable reportingFailure) {
                        // If publishing fails, retain all details in the ordinary JUnit failure.
                        throw new AssertionError(message + "\n\n" + summary
                                + "\n\nDiagnostics:\n" + traces, reportingFailure);
                    }
                }
                // JUnit splits MultipleFailureException into separate notifications. Use one
                // ordinary AssertionError, without repeating the report as suppressed exceptions.
                AssertionError combined = new AssertionError(message);
                combined.setStackTrace(new StackTraceElement[0]);
                throw combined;
            }
        };
    }
}
