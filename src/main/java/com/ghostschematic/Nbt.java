package com.ghostschematic;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Tiny standalone NBT reader so we don't depend on Minecraft's (frequently changing) NBT API. */
final class Nbt {
    private Nbt() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        InputStream in = new ByteArrayInputStream(data);
        if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            in = new GZIPInputStream(in);
        }
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            int type = d.readUnsignedByte();
            if (type != 10) throw new IOException("Not an NBT compound file");
            d.readUTF();
            return (Map<String, Object>) payload(d, 10);
        }
    }

    private static Object payload(DataInputStream d, int type) throws IOException {
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                byte[] a = new byte[d.readInt()];
                d.readFully(a);
                return a;
            }
            case 8: return d.readUTF();
            case 9: {
                int et = d.readUnsignedByte();
                int len = d.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) l.add(payload(d, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int t = d.readUnsignedByte();
                    if (t == 0) break;
                    String name = d.readUTF();
                    m.put(name, payload(d, t));
                }
                return m;
            }
            case 11: {
                int[] a = new int[d.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = d.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[d.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = d.readLong();
                return a;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static int integer(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static int ushort(Object o) {
        if (o instanceof Short s) return s & 0xFFFF;
        return integer(o);
    }
}
