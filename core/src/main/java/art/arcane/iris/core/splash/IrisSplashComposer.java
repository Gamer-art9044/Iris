package art.arcane.iris.core.splash;

import art.arcane.iris.BuildConstants;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class IrisSplashComposer {
    private static final String SPLASH_PADDING = " ".repeat(4);

    private IrisSplashComposer() {
    }

    public static String[] composeInfo(String version, String serverLine, InfoStyle style) {
        String releaseTrain = releaseTrain(version);
        String prefix = style.linePrefix();
        return new String[]{
                "",
                prefix + style.title(" Iris, ") + style.subtitle("Dimension Engine ") + style.tag("[" + releaseTrain + "]"),
                prefix + style.label(" Version: ") + style.value(version),
                prefix + style.label(" By: ") + style.value("Volmit Software (Arcane Arts)"),
                prefix + style.label(" Web: ") + style.value("VolmitSoftware.com"),
                prefix + style.label(" Server: ") + style.value(serverLine),
                prefix + style.label(" Java: ") + style.value(String.valueOf(javaVersion())) + style.label(" | Date: ") + style.value(startupDate()),
                prefix + style.label(" Commit: ") + style.value(BuildConstants.COMMIT) + style.label("/") + style.value(BuildConstants.ENVIRONMENT),
                "",
                "",
                ""
        };
    }

    public static String compose(String[] splash, String[] info) {
        StringBuilder builder = new StringBuilder("\n\n");
        for (int i = 0; i < splash.length; i++) {
            builder.append(SPLASH_PADDING).append(splash[i]).append(info[i]).append('\n');
        }
        return builder.toString();
    }

    public static List<String> composePackLines(File packFolder, IrisSplashPackScanner.SplashPackErrorReporter reporter) {
        List<IrisSplashPackScanner.SplashPackMetadata> packs = IrisSplashPackScanner.collect(packFolder, reporter);
        if (packs.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(packs.size() + 1);
        lines.add("Custom Dimensions: " + packs.size());
        for (IrisSplashPackScanner.SplashPackMetadata pack : packs) {
            lines.add("  " + pack.name() + " v" + pack.version());
        }
        return lines;
    }

    public static int javaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf('.');
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    public static String releaseTrain(String version) {
        String value = version == null ? "unknown" : version;
        int suffixIndex = value.indexOf('-');
        if (suffixIndex >= 0) {
            value = value.substring(0, suffixIndex);
        }
        String[] split = value.split("\\.");
        if (split.length >= 2) {
            return split[0] + "." + split[1];
        }
        return value;
    }

    private static String startupDate() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public interface InfoStyle {
        InfoStyle PLAIN = new InfoStyle() {
        };

        default String linePrefix() {
            return "";
        }

        default String title(String text) {
            return text;
        }

        default String subtitle(String text) {
            return text;
        }

        default String tag(String text) {
            return text;
        }

        default String label(String text) {
            return text;
        }

        default String value(String text) {
            return text;
        }
    }
}
