package art.arcane.iris.core.safeguard;

import art.arcane.iris.core.safeguard.task.Diagnostic;
import art.arcane.iris.core.safeguard.task.Task;
import art.arcane.iris.core.safeguard.task.Tasks;
import art.arcane.iris.core.safeguard.task.ValueWithDiagnostics;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.format.C;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IrisSafeguard {
    private static Map<Task, ValueWithDiagnostics<Mode>> results = Collections.emptyMap();
    private static Map<String, String> context = Collections.emptyMap();
    private static Map<String, List<String>> attachment = Collections.emptyMap();
    private static Mode mode = Mode.STABLE;
    private static int count = 0;

    private IrisSafeguard() {
    }

    public static void execute() {
        List<Task> tasks = Tasks.getTasks();
        LinkedHashMap<Task, ValueWithDiagnostics<Mode>> resultValues = new LinkedHashMap<>(tasks.size());
        LinkedHashMap<String, String> contextValues = new LinkedHashMap<>(tasks.size());
        LinkedHashMap<String, List<String>> attachmentValues = new LinkedHashMap<>(tasks.size());
        Mode currentMode = Mode.STABLE;
        int issueCount = 0;

        for (Task task : tasks) {
            ValueWithDiagnostics<Mode> result;
            try {
                result = task.run();
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                result = new ValueWithDiagnostics<>(
                        Mode.WARNING,
                        new Diagnostic(Diagnostic.Logger.ERROR, "Error while running task " + task.getId(), e)
                );
            }

            currentMode = currentMode.highest(result.getValue());
            resultValues.put(task, result);
            contextValues.put(task.getId(), result.getValue().getId());

            List<String> lines = new ArrayList<>();
            for (Diagnostic diagnostic : result.getDiagnostics()) {
                String[] split = diagnostic.toString().split("\\n");
                Collections.addAll(lines, split);
            }
            attachmentValues.put(task.getId(), lines);

            if (result.getValue() != Mode.STABLE) {
                issueCount++;
            }
        }

        results = Collections.unmodifiableMap(resultValues);
        context = Collections.unmodifiableMap(contextValues);
        attachment = Collections.unmodifiableMap(attachmentValues);
        mode = currentMode;
        count = issueCount;
    }

    public static Mode mode() {
        return mode;
    }

    public static Map<String, String> asContext() {
        return context;
    }

    public static Map<String, List<String>> asAttachment() {
        return attachment;
    }

    public static void printReports() {
        switch (mode) {
            case STABLE -> IrisLogging.info(C.BLUE + "0 Conflicts found");
            case WARNING -> IrisLogging.warn(C.GOLD + "%s Issues found", count);
            case UNSTABLE -> IrisLogging.error(C.DARK_RED + "%s Issues found", count);
        }

        for (ValueWithDiagnostics<Mode> value : results.values()) {
            value.log(true, true);
        }
    }

    public static void printFooter() {
        switch (mode) {
            case STABLE -> IrisLogging.info(C.BLUE + "Iris is running Stable");
            case WARNING -> warning();
            case UNSTABLE -> unstable();
        }
    }

    private static void warning() {
        IrisLogging.warn(C.GOLD + "Iris is running in Warning Mode");
        IrisLogging.warn(C.GRAY + "Some startup checks need attention. Review the messages above for tuning suggestions.");
        IrisLogging.warn(C.GRAY + "Iris will continue startup normally.");
        IrisLogging.warn("");
    }

    private static void unstable() {
        IrisLogging.error(C.DARK_RED + "Iris is running in Danger Mode");
        IrisLogging.error("");
        IrisLogging.error(C.DARK_GRAY + "--==<" + C.RED + " IMPORTANT " + C.DARK_GRAY + ">==--");
        IrisLogging.error("Critical startup checks failed. Review and resolve the errors above as soon as possible.");
        // No startup sleep: blocking the boot thread protected nothing — world creation and
        // player admission are already gated by IrisStartupValidation.
        IrisLogging.info("");
    }
}
