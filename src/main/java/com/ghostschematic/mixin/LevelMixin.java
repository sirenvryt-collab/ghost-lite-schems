package com.ghostschematic.mixin;

import com.ghostschematic.GhostManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Any block write that isn't ours (server update, your own placement, etc.) means the position
 * now holds a REAL block, so it must stop being treated as a ghost.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            require = 0
    )
    private void ghostschematic$onSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (GhostManager.applying) return;
        if ((Object) this instanceof ClientLevel) {
            GhostManager.onRealBlockSet(pos);
        }
    }
}
