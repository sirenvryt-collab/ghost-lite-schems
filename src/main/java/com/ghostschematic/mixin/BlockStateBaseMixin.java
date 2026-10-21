package com.ghostschematic.mixin;

import com.ghostschematic.GhostManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes ghost blocks intangible: you can walk through them and your crosshair goes through them,
 * so you can still place/break the REAL blocks behind and inside the ghost area.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true, require = 0)
    private void ghostschematic$shape(BlockGetter level, BlockPos pos, CollisionContext ctx,
                                      CallbackInfoReturnable<VoxelShape> cir) {
        if (GhostManager.isGhost(level, pos, this)) cir.setReturnValue(Shapes.empty());
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true, require = 0)
    private void ghostschematic$collision3(BlockGetter level, BlockPos pos, CollisionContext ctx,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        if (GhostManager.isGhost(level, pos, this)) cir.setReturnValue(Shapes.empty());
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true, require = 0)
    private void ghostschematic$collision2(BlockGetter level, BlockPos pos,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        if (GhostManager.isGhost(level, pos, this)) cir.setReturnValue(Shapes.empty());
    }
}
