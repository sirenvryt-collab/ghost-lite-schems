package com.ghostschematic;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

final class GhostCommands {
    private GhostCommands() {}

    static Path dir() {
        Path d = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
        }
        return d;
    }

    private static boolean supported(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".nbt") || n.endsWith(".schematic");
    }

    static List<String> files() {
        Path d = dir();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(d, 3)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String rel = d.relativize(p).toString().replace('\\', '/');
                if (supported(rel)) out.add(rel);
            });
        } catch (IOException ignored) {
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    private static CompletableFuture<Suggestions> suggestFiles(CommandContext<FabricClientCommandSource> ctx,
                                                               SuggestionsBuilder b) {
        String rem = b.getRemainingLowerCase();
        for (String f : files()) {
            if (f.toLowerCase(Locale.ROOT).startsWith(rem)) b.suggest(f);
        }
        return b.buildFuture();
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("ghost")
                .then(ClientCommandManager.literal("load")
                        .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                                .suggests(GhostCommands::suggestFiles)
                                .executes(c -> load(StringArgumentType.getString(c, "file")))))
                .then(ClientCommandManager.literal("unload").executes(c -> {
                    GhostManager.unload();
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(c -> {
                    GhostManager.toggle();
                    return 1;
                }))
                .then(ClientCommandManager.literal("rotate").executes(c -> {
                    GhostManager.rotate();
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(c -> {
                    GhostManager.msg(GhostManager.describe());
                    return 1;
                }))
                .then(ClientCommandManager.literal("folder").executes(c -> {
                    GhostManager.msg("Put schematics in: " + dir().toAbsolutePath());
                    return 1;
                }))
                .then(ClientCommandManager.literal("list").executes(c -> {
                    List<String> f = files();
                    GhostManager.msg(f.isEmpty() ? "No schematics found in " + dir().toAbsolutePath()
                            : String.join(", ", f));
                    return 1;
                }))
                .then(ClientCommandManager.literal("origin")
                        .executes(c -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.player != null) GhostManager.setOrigin(mc.player.blockPosition());
                            return 1;
                        })
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(c -> {
                                                    GhostManager.setOrigin(new BlockPos(
                                                            IntegerArgumentType.getInteger(c, "x"),
                                                            IntegerArgumentType.getInteger(c, "y"),
                                                            IntegerArgumentType.getInteger(c, "z")));
                                                    return 1;
                                                })))))
                .then(ClientCommandManager.literal("move")
                        .then(ClientCommandManager.argument("dx", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("dy", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("dz", IntegerArgumentType.integer())
                                                .executes(c -> {
                                                    GhostManager.move(
                                                            IntegerArgumentType.getInteger(c, "dx"),
                                                            IntegerArgumentType.getInteger(c, "dy"),
                                                            IntegerArgumentType.getInteger(c, "dz"));
                                                    return 1;
                                                })))))
                .executes(c -> {
                    GhostManager.msg("/ghost load <file> | unload | toggle | origin [x y z] | move <dx dy dz> | rotate | status | list | folder");
                    return 1;
                }));
    }

    private static int load(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            GhostManager.msg("Join a world first.");
            return 0;
        }
        Path d = dir().toAbsolutePath().normalize();
        Path file = d.resolve(name).normalize();
        if (!file.startsWith(d) || !Files.isRegularFile(file)) {
            GhostManager.msg("File not found in " + d + ": " + name);
            return 0;
        }
        BlockPos origin = GhostManager.hasSchematic() ? GhostManager.origin() : mc.player.blockPosition();
        int rot = GhostManager.hasSchematic() ? GhostManager.rotation() : 0;
        GhostManager.msg("Loading " + name + "...");

        Thread t = new Thread(() -> {
            try {
                Schematic s = SchematicLoader.load(file);
                String done = "Loaded " + s.name + " (" + s.size() + " blocks). Origin = your position. "
                        + "Use /ghost origin or /ghost move to adjust."
                        + (s.missing.isEmpty() ? "" : " Skipped unknown blocks: " + String.join(", ", s.missing));
                GhostManager.applyAsync(s, origin, rot, done);
            } catch (Throwable e) {
                mc.execute(() -> GhostManager.msg("Failed to load: " + e.getMessage()));
            }
        }, "ghostschematic-loader");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
