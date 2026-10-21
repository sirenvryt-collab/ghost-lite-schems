package com.ghostschematic;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class GhostSchematicClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(GhostManager::tick);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> GhostManager.dropChunk(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> GhostManager.dropChunk(chunk));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> GhostCommands.register(dispatcher));
    }
}
