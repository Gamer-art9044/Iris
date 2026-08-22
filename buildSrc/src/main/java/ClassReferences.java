import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the {@code CONSTANT_Class} entries of a class file. That is exactly the set of types the
 * class links against - supertypes, field and method owners, casts, catches, constant class
 * literals. Annotation types live in the annotation attributes as plain UTF-8 descriptors and are
 * deliberately not reported: the JVM skips an annotation whose type is absent.
 */
public final class ClassReferences {
    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELDREF = 9;
    private static final int CONSTANT_METHODREF = 10;
    private static final int CONSTANT_INTERFACE_METHODREF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    private ClassReferences() {
    }

    public static Set<String> read(byte[] classFile) {
        Set<String> references = new LinkedHashSet<>();
        if (classFile.length < 10 || readInt(classFile, 0) != CLASS_FILE_MAGIC) {
            return references;
        }

        int constantCount = readUnsignedShort(classFile, 8);
        String[] utf8 = new String[constantCount];
        List<Integer> classNameIndexes = new ArrayList<>();
        int offset = 10;
        int index = 1;
        while (index < constantCount) {
            int tag = classFile[offset] & 0xFF;
            offset++;
            switch (tag) {
                case CONSTANT_UTF8 -> {
                    int length = readUnsignedShort(classFile, offset);
                    offset += 2;
                    utf8[index] = new String(classFile, offset, length, StandardCharsets.UTF_8);
                    offset += length;
                }
                case CONSTANT_CLASS -> {
                    classNameIndexes.add(readUnsignedShort(classFile, offset));
                    offset += 2;
                }
                case CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE -> offset += 2;
                case CONSTANT_METHOD_HANDLE -> offset += 3;
                case CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF,
                     CONSTANT_INTERFACE_METHODREF, CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC,
                     CONSTANT_INVOKE_DYNAMIC -> offset += 4;
                case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    offset += 8;
                    index++;
                }
                default -> {
                    return references;
                }
            }
            index++;
        }

        for (int nameIndex : classNameIndexes) {
            if (nameIndex <= 0 || nameIndex >= constantCount) {
                continue;
            }
            String name = normalize(utf8[nameIndex]);
            if (name != null) {
                references.add(name);
            }
        }
        return references;
    }

    private static String normalize(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return null;
        }

        String name = rawName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        return name.length() > 1 ? name : null;
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
