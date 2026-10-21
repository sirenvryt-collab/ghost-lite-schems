package com.ghostschematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** A schematic positioned (and rotated) in the world, indexed by chunk for fast scanning. */
final class Placement {
    /**
     * Blocks that have client-side effects when you stand in them (slowing, climbing, damage visuals...).
     * Showing these under the player would desync movement with the server, so they only ghost
     * while the player is a few blocks away.
     */
    private static final Set<String> RISKY = Set.of(
            "minecraft:ladder", "minecraft:vine", "minecraft:scaffolding", "minecraft:cobweb",
            "minecraft:powder_snow", "minecraft:honey_block", "minecraft:sweet_berry_bush",
            "minecraft:fire", "minecraft:soul_fire", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:magma_block", "minecraft:cactus", "minecraft:wither_rose",
            "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
            "minecraft:cave_vines", "minecraft:cave_vines_plant",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway");

    final Schematic schematic;
    final BlockPos origin;
    final int rotation;

    final int[] x, y, z, pal;
    final BlockState[] rpal;
    final Map<Long, int[]> chunks = new HashMap<>();
    final int[] risky;

    private static final class IntBuf {
        int[] a = new int[16];
        int n;
        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
        int[] done() { return Arrays.copyOf(a, n); }
    }

    Placement(Schematic s, BlockPos origin, int rotation) {
        this.schematic = s;
        this.origin = origin;
        this.rotation = rotation & 3;

        int pn = s.palette.length;
        rpal = new BlockState[pn];
        boolean[] isRisky = new boolean[pn];
        for (int i = 0; i < pn; i++) {
            BlockState st = s.palette[i];
            isRisky[i] = RISKY.contains(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
            for (int k = 0; k < this.rotation; k++) st = st.rotate(Rotation.CLOCKWISE_90);
            rpal[i] = st;
        }

        int n = s.size();
        x = new int[n];
        y = new int[n];
        z = new int[n];
        pal = s.pal;

        Map<Long, IntBuf> tmp = new HashMap<>();
        IntBuf riskyBuf = new IntBuf();

        for (int i = 0; i < n; i++) {
            int rx = s.x[i], rz = s.z[i];
            for (int k = 0; k < this.rotation; k++) { // (x,z) -> (-z,x) == 90 degrees clockwise
                int t = rx;
                rx = -rz;
                rz = t;
            }
            int wx = origin.getX() + rx;
            int wy = origin.getY() + s.y[i];
            int wz = origin.getZ() + rz;
            x[i] = wx;
            y[i] = wy;
            z[i] = wz;

            if (isRisky[pal[i]]) {
                riskyBuf.add(i);
            } else {
                tmp.computeIfAbsent(GhostStore.ck(wx >> 4, wz >> 4), k -> new IntBuf()).add(i);
            }
        }

        for (Map.Entry<Long, IntBuf> e : tmp.entrySet()) chunks.put(e.getKey(), e.getValue().done());
        risky = riskyBuf.done();
    }
}
