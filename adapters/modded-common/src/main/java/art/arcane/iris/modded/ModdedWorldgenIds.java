package art.arcane.iris.modded;

import art.arcane.iris.engine.framework.Engine;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ModdedWorldgenIds {
    private static final String NAMESPACE = "irisworldgen";

    private ModdedWorldgenIds() {
    }

    public static String presetRef(String pack, String dimension) {
        return NAMESPACE + ":" + scopedPath(pack, dimension) + "/preset";
    }

    public static String dimensionTypeRef(String pack, String dimension) {
        return NAMESPACE + ":" + scopedPath(pack, dimension) + "/dimension_type";
    }

    public static String biomePathPrefix(String pack, String dimension) {
        return scopedPath(pack, dimension) + "/biomes";
    }

    public static String biomeRef(String pack, String dimension, String biome) {
        return NAMESPACE + ":" + biomePathPrefix(pack, dimension) + "/"
                + biome.toLowerCase(Locale.ROOT);
    }

    public static String biomeRef(Engine engine, String biome) {
        return biomeRef(engine.getData().getDataFolder().getName(),
                engine.getDimension().getLoadKey(), biome);
    }

    public static String displayName(String presetPath) {
        String[] parts = presetPath.split("/");
        if (parts.length != 5 || !"packs".equals(parts[0])
                || !"dimensions".equals(parts[2]) || !"preset".equals(parts[4])) {
            return null;
        }
        String pack = decode(parts[1]);
        String dimension = decode(parts[3]);
        if (pack == null || dimension == null) {
            return null;
        }
        String packLabel = title(pack);
        return pack.equalsIgnoreCase(dimension)
                ? "IRIS:" + packLabel
                : "IRIS:" + packLabel + " / " + title(dimension);
    }

    public static String generatorIdentity(String packDimension) {
        int separator = packDimension.indexOf(':');
        String pack = separator >= 0 ? packDimension.substring(0, separator) : packDimension;
        String dimension = separator >= 0 ? packDimension.substring(separator + 1) : packDimension;
        return "iris:" + (pack.equalsIgnoreCase(dimension)
                ? pack.toLowerCase(Locale.ROOT)
                : pack.toLowerCase(Locale.ROOT) + "/" + dimension.toLowerCase(Locale.ROOT));
    }

    private static String scopedPath(String pack, String dimension) {
        return "packs/" + encode(pack) + "/dimensions/" + encode(dimension);
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            encoded.append(Character.forDigit((current >>> 4) & 0xF, 16));
            encoded.append(Character.forDigit(current & 0xF, 16));
        }
        return encoded.toString();
    }

    private static String decode(String value) {
        if ((value.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[value.length() / 2];
        try {
            for (int index = 0; index < bytes.length; index++) {
                int high = Character.digit(value.charAt(index * 2), 16);
                int low = Character.digit(value.charAt(index * 2 + 1), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes[index] = (byte) ((high << 4) | low);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String title(String value) {
        String normalized = value.replace('_', ' ').replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
