package com.ghostschematic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Turns "minecraft:oak_stairs[facing=north]" style data into BlockStates. */
final class BlockParser {
    private BlockParser() {}

    private static Map<String, Block> blocks;

    private static synchronized Block block(String id) {
        if (blocks == null) {
            Map<String, Block> m = new HashMap<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                m.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b);
            }
            blocks = m;
        }
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        return blocks.get(id);
    }

    /** Parses "name[a=b,c=d]". Returns null if the block doesn't exist in this version. */
    static BlockState fromString(String s) {
        int br = s.indexOf('[');
        String id = br < 0 ? s : s.substring(0, br);
        Map<String, String> props = new HashMap<>();
        if (br >= 0 && s.endsWith("]")) {
            for (String kv : s.substring(br + 1, s.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        return parse(id, props);
    }

    static BlockState parseRaw(String id, Map<String, Object> raw) {
        Map<String, String> props = new HashMap<>();
        if (raw != null) {
            for (Map.Entry<String, Object> e : raw.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return parse(id, props);
    }

    static BlockState parse(String id, Map<String, String> props) {
        Block b = block(id);
        if (b == null) return null;
        BlockState s = b.defaultBlockState();
        if (props != null && !props.isEmpty()) {
            for (Property<?> p : s.getProperties()) {
                String v = props.get(p.getName());
                if (v != null) s = with(s, p, v);
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        Optional<T> o = p.getValue(v);
        return o.isPresent() ? s.setValue(p, o.get()) : s;
    }

    /**
     * Cleans a state up for ghost rendering. Returns null for blocks we should never ghost:
     * air and anything containing fluid (client-only fluid would desync swimming physics).
     */
    static BlockState prepare(BlockState s) {
        if (s == null || s.isAir()) return null;
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
            s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        if (!s.getFluidState().isEmpty()) return null;
        return s;
    }
}
