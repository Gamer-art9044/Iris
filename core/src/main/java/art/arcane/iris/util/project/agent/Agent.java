package art.arcane.iris.util.project.agent;

import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.IrisLogging;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class Agent {
    private static final String NAME = "art.arcane.iris.util.project.agent.Installer";
    public static final File AGENT_JAR = new File(IrisPlatforms.get().dataFolder(), "agent.jar");

    public static ClassReloadingStrategy installed() {
        return ClassReloadingStrategy.of(getInstrumentation());
    }

    public static boolean isInstalled() {
        return doGetInstrumentation() != null;
    }

    public static Instrumentation getInstrumentation() {
        Instrumentation instrumentation = doGetInstrumentation();
        if (instrumentation == null) throw new IllegalStateException("The agent is not initialized or unavailable");
        return instrumentation;
    }

    public static boolean install() {
        if (isInstalled())
            return true;

        if (!ensureAgentJar())
            return false;

        try {
            IrisLogging.info("Installing Java Agent...");
            IrisLogging.info("Note: JVM [Attach Listener/ERROR] [STDERR] warning lines during this step are expected and not Iris errors.");
            ByteBuddyAgent.attach(AGENT_JAR, ByteBuddyAgent.ProcessProvider.ForCurrentVm.INSTANCE);
        } catch (Throwable e) {
            IrisLogging.error("Failed to install Java Agent: " + e.getMessage());
            IrisLogging.reportError(e);
        }
        return doGetInstrumentation() != null;
    }

    private static boolean ensureAgentJar() {
        File parent = AGENT_JAR.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            IrisLogging.error("Failed to create Iris plugin data folder for Java agent: " + parent.getAbsolutePath());
            return false;
        }

        try (InputStream in = openBundledAgentJar()) {
            if (in == null) {
                if (AGENT_JAR.isFile() && AGENT_JAR.length() > 0) {
                    IrisLogging.warn("Bundled agent.jar not found in Iris plugin jar. Reusing existing " + AGENT_JAR.getAbsolutePath());
                    return true;
                }

                IrisLogging.error("Bundled agent.jar was not found in Iris plugin jar. Rebuild/deploy Iris with embedded agent.jar.");
                return false;
            }

            Files.copy(in, AGENT_JAR.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable e) {
            IrisLogging.error("Failed to prepare Java agent jar: " + e.getMessage());
            IrisLogging.reportError(e);
            return false;
        }
    }

    private static InputStream openBundledAgentJar() {
        return Agent.class.getClassLoader().getResourceAsStream("agent.jar");
    }

    private static Instrumentation doGetInstrumentation() {
        try {
            return (Instrumentation) Class.forName(NAME, true, ClassLoader.getSystemClassLoader()).getMethod("getInstrumentation").invoke(null);
        } catch (Exception ex) {
            return null;
        }
    }
}
