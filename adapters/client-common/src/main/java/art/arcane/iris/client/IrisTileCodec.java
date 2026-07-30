package art.arcane.iris.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class IrisTileCodec {
    public static final int MODE_RAW_RGB = 0;
    public static final int MODE_PALETTE = 1;
    private static final int MAX_DIMENSION = 512;
    private static final int OPAQUE = 0xFF000000;
    static final int MAX_DECODED_BYTES = 9 + 4 + 3 * MAX_DIMENSION * MAX_DIMENSION;

    private IrisTileCodec() {
    }

    public static IrisTileImage decode(byte[] deflatedBlob) {
        if (deflatedBlob == null || deflatedBlob.length == 0) {
            return null;
        }
        byte[] raw = inflate(deflatedBlob);
        if (raw == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int width = in.readInt();
            int height = in.readInt();
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                return null;
            }
            int mode = in.readUnsignedByte();
            int[] argb = new int[width * height];
            return switch (mode) {
                case MODE_PALETTE -> decodePalette(in, width, height, argb);
                case MODE_RAW_RGB -> decodeRaw(in, width, height, argb);
                default -> null;
            };
        } catch (IOException failure) {
            return null;
        }
    }

    private static IrisTileImage decodePalette(DataInputStream in, int width, int height, int[] argb) throws IOException {
        int paletteSize = in.readInt();
        if (paletteSize <= 0 || paletteSize > 256) {
            return null;
        }
        int[] palette = new int[paletteSize];
        for (int index = 0; index < paletteSize; index++) {
            int red = in.readUnsignedByte();
            int green = in.readUnsignedByte();
            int blue = in.readUnsignedByte();
            palette[index] = OPAQUE | red << 16 | green << 8 | blue;
        }
        for (int pixel = 0; pixel < argb.length; pixel++) {
            int paletteIndex = in.readUnsignedByte();
            if (paletteIndex >= paletteSize) {
                return null;
            }
            argb[pixel] = palette[paletteIndex];
        }
        return new IrisTileImage(width, height, argb);
    }

    private static IrisTileImage decodeRaw(DataInputStream in, int width, int height, int[] argb) throws IOException {
        for (int pixel = 0; pixel < argb.length; pixel++) {
            int red = in.readUnsignedByte();
            int green = in.readUnsignedByte();
            int blue = in.readUnsignedByte();
            argb[pixel] = OPAQUE | red << 16 | green << 8 | blue;
        }
        return new IrisTileImage(width, height, argb);
    }

    private static byte[] inflate(byte[] input) {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length * 2));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int produced = inflater.inflate(buffer);
                if (produced == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                if (out.size() + produced > MAX_DECODED_BYTES) {
                    return null;
                }
                out.write(buffer, 0, produced);
            }
        } catch (DataFormatException malformed) {
            return null;
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
