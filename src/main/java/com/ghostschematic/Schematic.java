package com.ghostschematic;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** A loaded schematic: sparse list of (x,y,z,paletteIndex), anchored so its min corner is 0,0,0. */
final class Schematic {
    final String name;
    final int[] x, y, z, pal;
    final BlockState[] palette;
    final int sizeX, sizeY, sizeZ;
    final Set<String> missing;

    private Schematic(String name, int[] x, int[] y, int[] z, int[] pal, BlockState[] palette,
                      int sx, int sy, int sz, Set<String> missing) {
        this.name = name;
        this.x = x; this.y = y; this.z = z; this.pal = pal;
        this.palette = palette;
        this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
        this.missing = missing;
    }

    int size() { return x.length; }

    static final class Builder {
        private int[] x = new int[1 << 16], y = new int[1 << 16], z = new int[1 << 16], p = new int[1 << 16];
        private int n;
        private final List<BlockState> palette = new ArrayList<>();
        private final IdentityHashMap<BlockState, Integer> index = new IdentityHashMap<>();
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        final Set<String> missing = new TreeSet<>();

        void extend(int ex, int ey, int ez) {
            minX = Math.min(minX, ex); minY = Math.min(minY, ey); minZ = Math.min(minZ, ez);
            maxX = Math.max(maxX, ex); maxY = Math.max(maxY, ey); maxZ = Math.max(maxZ, ez);
        }

        int paletteIndex(BlockState s) {
            Integer i = index.get(s);
            if (i == null) {
                i = palette.size();
                palette.add(s);
                index.put(s, i);
            }
            return i;
        }

        void add(int ax, int ay, int az, int pi) {
            if (n == x.length) {
                int cap = n * 2;
                x = Arrays.copyOf(x, cap); y = Arrays.copyOf(y, cap);
                z = Arrays.copyOf(z, cap); p = Arrays.copyOf(p, cap);
            }
            x[n] = ax; y[n] = ay; z[n] = az; p[n] = pi;
            n++;
            extend(ax, ay, az);
        }

        boolean isEmpty() { return n == 0; }

        Schematic build(String name) {
            int[] rx = new int[n], ry = new int[n], rz = new int[n];
            for (int i = 0; i < n; i++) {
                rx[i] = x[i] - minX;
                ry[i] = y[i] - minY;
                rz[i] = z[i] - minZ;
            }
            return new Schematic(name, rx, ry, rz, Arrays.copyOf(p, n),
                    palette.toArray(new BlockState[0]),
                    maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, missing);
        }
    }
}
