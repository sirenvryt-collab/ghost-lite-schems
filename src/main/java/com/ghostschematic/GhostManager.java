package com.ghostschematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Core logic. Ghost blocks are written straight into the CLIENT world, so vanilla renders them
 * with proper models, lighting and transparency. They are only ever placed on positions that are
 * air, and the server never hears about them.
 */
public final class GhostManager {
    /** True while we are writing our own blocks, so the Level mixin doesn't mistake them for real changes. */
    public static volatile boolean applying = false;

    private static final int CHECK_BUDGET = 25000; // block lookups per tick
    private static final int SET_BUDGET = 600;     // ghost blocks written per tick
    private static final int MAX_RADIUS = 16;      // chunks

    private static final GhostStore store = new GhostStore();
    private static final ArrayDeque<Long> clearQueue = new ArrayDeque<>();
    private static final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

    private static Schematic schematic;
    private static Placement placement;
    private static BlockPos origin = BlockPos.ZERO;
    private static int rotation;
    private static boolean enabled = true;
    private static int generation;

    private static ClientLevel lastLevel;
    private static final List<Long> active = new ArrayList<>();
    private static int ci, ei, idle, lastPcx = Integer.MIN_VALUE, lastPcz = Integer.MIN_VALUE;
    private static boolean needRebuild = true, cycleChanged, budgetHit;
    private static int sets;

    private GhostManager() {}

    // ---------------------------------------------------------------- hooks used by mixins / events

    /** Called by the Collision/Shape mixins. */
    public static boolean isGhost(BlockGetter level, BlockPos pos, Object state) {
        if (store.isEmpty()) return false;
        if (!(level instanceof ClientLevel)) return false;
        return store.get(pos.asLong()) == state;
    }

    /** Called when something other than us changes a block in the client world. */
    public static void onRealBlockSet(BlockPos pos) {
        if (store.isEmpty()) return;
        store.remove(pos.asLong());
    }

    public static void dropChunk(LevelChunk chunk) {
        int cx = chunk.getPos().getMinBlockX() >> 4;
        int cz = chunk.getPos().getMinBlockZ() >> 4;
        store.dropChunk(cx, cz);
    }

    // ---------------------------------------------------------------- commands API

    public static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Ghost] " + s), false);
    }

    public static boolean hasSchematic() { return schematic != null; }
    public static BlockPos origin() { return origin; }
    public static int rotation() { return rotation; }

    /** Builds a placement on a worker thread, then swaps it in on the client thread. */
    public static void applyAsync(Schematic s, BlockPos o, int rot, String doneMessage) {
        Minecraft mc = Minecraft.getInstance();
        final int gen = ++generation;
        Thread t = new Thread(() -> {
            try {
                Placement p = new Placement(s, o, rot);
                mc.execute(() -> {
                    if (gen != generation) return;
                    schematic = s;
                    origin = o;
                    rotation = rot & 3;
                    requestClear();
                    placement = p;
                    enabled = true;
                    resetScan();
                    msg(doneMessage);
                });
            } catch (Throwable e) {
                mc.execute(() -> msg("Failed: " + e));
            }
        }, "ghostschematic-build");
        t.setDaemon(true);
        t.start();
    }

    public static void move(int dx, int dy, int dz) {
        if (schematic == null) { msg("No schematic loaded."); return; }
        BlockPos o = origin.offset(dx, dy, dz);
        applyAsync(schematic, o, rotation, "Origin is now " + fmt(o));
    }

    public static void setOrigin(BlockPos o) {
        if (schematic == null) { msg("No schematic loaded."); return; }
        applyAsync(schematic, o, rotation, "Origin is now " + fmt(o));
    }

    public static void rotate() {
        if (schematic == null) { msg("No schematic loaded."); return; }
        int r = (rotation + 1) & 3;
        applyAsync(schematic, origin, r, "Rotated to " + (r * 90) + " degrees");
    }

    public static void unload() {
        generation++;
        requestClear();
        placement = null;
        schematic = null;
        msg("Schematic unloaded.");
    }

    public static void toggle() {
        if (schematic == null) { msg("No schematic loaded."); return; }
        enabled = !enabled;
        if (!enabled) requestClear();
        else resetScan();
        msg(enabled ? "Ghost blocks ON" : "Ghost blocks OFF");
    }

    public static String describe() {
        if (schematic == null) return "No schematic loaded.";
        StringBuilder sb = new StringBuilder();
        sb.append(schematic.name).append(": ").append(schematic.size()).append(" blocks, size ")
                .append(schematic.sizeX).append('x').append(schematic.sizeY).append('x').append(schematic.sizeZ)
                .append(", origin ").append(fmt(origin)).append(", rotation ").append(rotation * 90)
                .append(", ").append(enabled ? "enabled" : "disabled")
                .append(", ghosts shown: ").append(store.size());
        if (!schematic.missing.isEmpty()) {
            sb.append(" | skipped unknown blocks: ").append(String.join(", ", schematic.missing));
        }
        return sb.toString();
    }

    private static String fmt(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    // ---------------------------------------------------------------- tick

    private static void resetScan() {
        active.clear();
        ci = 0;
        ei = 0;
        idle = 0;
        needRebuild = true;
        cycleChanged = true;
    }

    private static void requestClear() {
        clearQueue.addAll(store.keys());
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (level != lastLevel) {
            lastLevel = level;
            store.clear();
            clearQueue.clear();
            resetScan();
        }
        if (level == null || player == null) return;

        drainClearQueue(level);

        Placement p = placement;
        if (p == null || !enabled) return;

        sets = 0;
        budgetHit = false;

        tickRisky(level, p, player);

        if (idle > 0) {
            idle--;
            return;
        }

        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        if (needRebuild || pcx != lastPcx || pcz != lastPcz) {
            int radius = Math.min(mc.options.renderDistance().get(), MAX_RADIUS);
            rebuildActive(level, p, pcx, pcz, radius);
            if (pcx != lastPcx || pcz != lastPcz) {
                ci = 0;
                ei = 0;
            }
            lastPcx = pcx;
            lastPcz = pcz;
            needRebuild = false;
        }

        int checks = 0;
        while (checks < CHECK_BUDGET && !budgetHit) {
            if (ci >= active.size()) { // finished a full pass
                needRebuild = true;
                if (!cycleChanged) idle = 4;
                cycleChanged = false;
                ci = 0;
                ei = 0;
                break;
            }
            int[] ents = p.chunks.get(active.get(ci));
            if (ents == null || ei >= ents.length) {
                ci++;
                ei = 0;
                continue;
            }
            checkEntry(level, p, ents[ei++]);
            checks++;
        }
        if (budgetHit) cycleChanged = true;
    }

    private static void rebuildActive(ClientLevel level, Placement p, int pcx, int pcz, int radius) {
        active.clear();
        for (Map.Entry<Long, int[]> e : p.chunks.entrySet()) {
            long k = e.getKey();
            int cx = (int) k;
            int cz = (int) (k >> 32);
            if (Math.abs(cx - pcx) > radius || Math.abs(cz - pcz) > radius) continue;
            if (!level.hasChunk(cx, cz)) continue;
            active.add(k);
        }
        active.sort(Comparator.comparingLong(k -> {
            long dx = (int) (long) k - pcx;
            long dz = (int) (k >> 32) - pcz;
            return dx * dx + dz * dz;
        }));
    }

    private static void checkEntry(ClientLevel level, Placement p, int idx) {
        int x = p.x[idx], y = p.y[idx], z = p.z[idx];
        if (level.isOutsideBuildHeight(y)) return;
        mpos.set(x, y, z);
        BlockState target = p.rpal[p.pal[idx]];
        BlockState cur = level.getBlockState(mpos);
        long key = BlockPos.asLong(x, y, z);
        BlockState ex = store.get(key);

        if (ex != null) {
            if (cur == ex) {
                if (ex != target) { // schematic changed under an existing ghost
                    if (place(level, key, target)) cycleChanged = true;
                }
                return;
            }
            store.remove(key); // something real replaced it (or the chunk reloaded)
        }
        if (!cur.isAir()) return; // a real block is already here: show nothing

        if (sets >= SET_BUDGET) {
            budgetHit = true;
            return;
        }
        if (place(level, key, target)) {
            sets++;
            cycleChanged = true;
        }
    }

    private static void tickRisky(ClientLevel level, Placement p, LocalPlayer player) {
        int[] r = p.risky;
        if (r.length == 0) return;
        double px = player.getX(), py = player.getY() + 0.9, pz = player.getZ();
        for (int idx : r) {
            int x = p.x[idx], y = p.y[idx], z = p.z[idx];
            double dx = px - (x + 0.5), dy = py - (y + 0.5), dz = pz - (z + 0.5);
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > 900.0) continue;
            if (level.isOutsideBuildHeight(y) || !level.hasChunk(x >> 4, z >> 4)) continue;

            mpos.set(x, y, z);
            long key = BlockPos.asLong(x, y, z);
            BlockState cur = level.getBlockState(mpos);
            BlockState ex = store.get(key);

            if (ex != null) {
                if (cur == ex) {
                    if (d2 < 6.25) removeGhost(level, key); // player is walking into it: hide it
                    continue;
                }
                store.remove(key);
            }
            if (cur.isAir() && d2 > 12.25 && sets < SET_BUDGET) {
                if (place(level, key, p.rpal[p.pal[idx]])) sets++;
            }
        }
    }

    private static void drainClearQueue(ClientLevel level) {
        int n = 0;
        while (n < 4000 && !clearQueue.isEmpty()) {
            long key = clearQueue.pollFirst();
            BlockState g = store.get(key);
            if (g == null) continue;
            n++;
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            if (level.hasChunk(x >> 4, z >> 4)) {
                mpos.set(x, y, z);
                if (level.getBlockState(mpos) == g) {
                    removeGhost(level, key);
                    continue;
                }
            }
            store.remove(key);
        }
    }

    // ---------------------------------------------------------------- world writes

    private static boolean place(ClientLevel level, long key, BlockState state) {
        BlockPos pos = BlockPos.of(key);
        applying = true;
        try {
            // flags 18 = send to renderer + skip neighbour shape updates. Nothing goes to the server.
            level.setBlock(pos, state, 18);
            if (level.getBlockState(pos) == state) {
                store.put(key, state);
                return true;
            }
        } finally {
            applying = false;
        }
        return false;
    }

    private static void removeGhost(ClientLevel level, long key) {
        BlockPos pos = BlockPos.of(key);
        store.remove(key);
        applying = true;
        try {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        } finally {
            applying = false;
        }
    }
}
