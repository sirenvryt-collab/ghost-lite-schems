package com.ghostschematic;

import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads .litematic, .schem (Sponge v1-3) and .nbt (vanilla structure) files. */
final class SchematicLoader {
    private SchematicLoader() {}

    static Schematic load(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, Object> root = Nbt.read(file);
        Schematic.Builder b = new Schematic.Builder();

        if (n.endsWith(".litematic")) {
            litematic(root, b);
        } else if (n.endsWith(".schem")) {
            sponge(root, b);
        } else if (n.endsWith(".nbt")) {
            structure(root, b);
        } else if (n.endsWith(".schematic")) {
            if (root.containsKey("Palette") || root.containsKey("Schematic")) {
                sponge(root, b);
            } else {
                throw new IOException("Legacy MCEdit .schematic files use pre-1.13 numeric block IDs and can't be "
                        + "loaded. Convert it to .schem or .litematic first (WorldEdit, Litematica or Amulet can do this).");
            }
        } else {
            throw new IOException("Unsupported file type (use .litematic, .schem or .nbt)");
        }

        if (b.isEmpty()) throw new IOException("No placeable blocks found in this file");
        return b.build(file.getFileName().toString());
    }

    // ------------------------------------------------------------------ litematic

    private static void litematic(Map<String, Object> root, Schematic.Builder b) throws IOException {
        Map<String, Object> regions = Nbt.map(root.get("Regions"));
        if (regions == null) throw new IOException("Not a valid .litematic file (no Regions)");

        for (Map.Entry<String, Object> e : regions.entrySet()) {
            Map<String, Object> r = Nbt.map(e.getValue());
            if (r == null) continue;
            Map<String, Object> pos = Nbt.map(r.get("Position"));
            Map<String, Object> size = Nbt.map(r.get("Size"));
            if (pos == null || size == null) continue;

            int px = Nbt.integer(pos.get("x")), py = Nbt.integer(pos.get("y")), pz = Nbt.integer(pos.get("z"));
            int sx = Nbt.integer(size.get("x")), sy = Nbt.integer(size.get("y")), sz = Nbt.integer(size.get("z"));
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            // Negative sizes mean the region extends in the negative direction from Position.
            int minX = sx < 0 ? px + sx + 1 : px;
            int minY = sy < 0 ? py + sy + 1 : py;
            int minZ = sz < 0 ? pz + sz + 1 : pz;
            b.extend(minX, minY, minZ);
            b.extend(minX + ax - 1, minY + ay - 1, minZ + az - 1);

            List<Object> pal = Nbt.list(r.get("BlockStatePalette"));
            Object bsObj = r.get("BlockStates");
            if (pal == null || !(bsObj instanceof long[] bits)) continue;

            int[] map = new int[pal.size()];
            for (int i = 0; i < map.length; i++) map[i] = paletteEntry(Nbt.map(pal.get(i)), b);

            int nbits = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.size() - 1));
            long mask = (1L << nbits) - 1L;
            long total = (long) ax * ay * az;

            for (int i = 0; i < total; i++) {
                long start = (long) i * nbits;
                int si = (int) (start >>> 6);
                int ei = (int) (((long) (i + 1) * nbits - 1L) >>> 6);
                if (ei >= bits.length) break;
                int off = (int) (start & 63L);
                long v;
                if (si == ei) {
                    v = (bits[si] >>> off) & mask;
                } else {
                    v = ((bits[si] >>> off) | (bits[ei] << (64 - off))) & mask;
                }
                if (v >= map.length) continue;
                int pi = map[(int) v];
                if (pi < 0) continue;
                int lx = i % ax;
                int lz = (i / ax) % az;
                int ly = i / (ax * az);
                b.add(minX + lx, minY + ly, minZ + lz, pi);
            }
        }
    }

    /** Palette entry compound {Name, Properties} -> builder palette index, or -1 if not ghost-able. */
    private static int paletteEntry(Map<String, Object> c, Schematic.Builder b) {
        if (c == null) return -1;
        Object nameObj = c.get("Name");
        if (!(nameObj instanceof String name)) return -1;
        BlockState s = BlockParser.parseRaw(name, Nbt.map(c.get("Properties")));
        if (s == null) {
            b.missing.add(name);
            return -1;
        }
        BlockState prepared = BlockParser.prepare(s);
        return prepared == null ? -1 : b.paletteIndex(prepared);
    }

    // ------------------------------------------------------------------ sponge .schem

    private static void sponge(Map<String, Object> root, Schematic.Builder b) throws IOException {
        Map<String, Object> s = root;
        Map<String, Object> inner = Nbt.map(root.get("Schematic"));
        if (inner != null) s = inner;

        int w = Nbt.ushort(s.get("Width"));
        int h = Nbt.ushort(s.get("Height"));
        int l = Nbt.ushort(s.get("Length"));

        Map<String, Object> palMap;
        Object dataObj;
        Map<String, Object> blocks = Nbt.map(s.get("Blocks"));
        if (blocks != null) { // v3
            palMap = Nbt.map(blocks.get("Palette"));
            dataObj = blocks.get("Data");
        } else {              // v1 / v2
            palMap = Nbt.map(s.get("Palette"));
            dataObj = s.get("BlockData");
        }
        if (palMap == null || !(dataObj instanceof byte[] data)) {
            throw new IOException("Not a valid .schem file (missing palette or block data)");
        }

        int maxId = 0;
        for (Object v : palMap.values()) maxId = Math.max(maxId, Nbt.integer(v));
        int[] map = new int[maxId + 1];
        java.util.Arrays.fill(map, -1);
        for (Map.Entry<String, Object> e : palMap.entrySet()) {
            int id = Nbt.integer(e.getValue());
            BlockState st = BlockParser.fromString(e.getKey());
            if (st == null) {
                String key = e.getKey();
                int br = key.indexOf('[');
                b.missing.add(br < 0 ? key : key.substring(0, br));
                continue;
            }
            BlockState prepared = BlockParser.prepare(st);
            if (prepared != null) map[id] = b.paletteIndex(prepared);
        }

        b.extend(0, 0, 0);
        b.extend(w - 1, h - 1, l - 1);

        int pos = 0;
        long total = (long) w * h * l;
        for (int i = 0; i < total && pos < data.length; i++) {
            int v = 0, shift = 0;
            while (pos < data.length) {
                int by = data[pos++];
                v |= (by & 0x7F) << shift;
                if ((by & 0x80) == 0) break;
                shift += 7;
            }
            if (v < 0 || v >= map.length) continue;
            int pi = map[v];
            if (pi < 0) continue;
            int x = i % w;
            int z = (i / w) % l;
            int y = i / (w * l);
            b.add(x, y, z, pi);
        }
    }

    // ------------------------------------------------------------------ vanilla structure .nbt

    private static void structure(Map<String, Object> root, Schematic.Builder b) throws IOException {
        List<Object> size = Nbt.list(root.get("size"));
        List<Object> pal = Nbt.list(root.get("palette"));
        if (pal == null) {
            List<Object> palettes = Nbt.list(root.get("palettes"));
            if (palettes != null && !palettes.isEmpty()) pal = Nbt.list(palettes.get(0));
        }
        List<Object> blocks = Nbt.list(root.get("blocks"));
        if (size == null || size.size() < 3 || pal == null || blocks == null) {
            throw new IOException("Not a valid structure .nbt file");
        }

        b.extend(0, 0, 0);
        b.extend(Nbt.integer(size.get(0)) - 1, Nbt.integer(size.get(1)) - 1, Nbt.integer(size.get(2)) - 1);

        int[] map = new int[pal.size()];
        for (int i = 0; i < map.length; i++) map[i] = paletteEntry(Nbt.map(pal.get(i)), b);

        for (Object o : blocks) {
            Map<String, Object> c = Nbt.map(o);
            if (c == null) continue;
            List<Object> p = Nbt.list(c.get("pos"));
            if (p == null || p.size() < 3) continue;
            int st = Nbt.integer(c.get("state"));
            if (st < 0 || st >= map.length || map[st] < 0) continue;
            b.add(Nbt.integer(p.get(0)), Nbt.integer(p.get(1)), Nbt.integer(p.get(2)), map[st]);
        }
    }
}
