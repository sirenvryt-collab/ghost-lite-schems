package com.ghostschematic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which client-world positions currently hold a ghost block, grouped by chunk. */
final class GhostStore {
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, BlockState>> byChunk = new ConcurrentHashMap<>();

    static long ck(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    private static long chunkOf(long posKey) {
        return ck(BlockPos.getX(posKey) >> 4, BlockPos.getZ(posKey) >> 4);
    }

    boolean isEmpty() { return byChunk.isEmpty(); }

    BlockState get(long key) {
        Map<Long, BlockState> m = byChunk.get(chunkOf(key));
        return m == null ? null : m.get(key);
    }

    void put(long key, BlockState s) {
        byChunk.computeIfAbsent(chunkOf(key), k -> new ConcurrentHashMap<>()).put(key, s);
    }

    void remove(long key) {
        long c = chunkOf(key);
        ConcurrentHashMap<Long, BlockState> m = byChunk.get(c);
        if (m == null) return;
        m.remove(key);
        if (m.isEmpty()) byChunk.remove(c, m);
    }

    void dropChunk(int cx, int cz) {
        byChunk.remove(ck(cx, cz));
    }

    void clear() { byChunk.clear(); }

    int size() {
        int n = 0;
        for (Map<Long, BlockState> m : byChunk.values()) n += m.size();
        return n;
    }

    List<Long> keys() {
        List<Long> out = new ArrayList<>();
        for (Map<Long, BlockState> m : byChunk.values()) out.addAll(m.keySet());
        return out;
    }
}
