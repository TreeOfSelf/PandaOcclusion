package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.LowestSurfaceHolder;
import me.TreeOfSelf.PandaOcclusion.Occlusion;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements LowestSurfaceHolder {
	@Unique
	private volatile long panda$lowestSurface;

	@Override
	public long panda$lowestSurfaceCache() {
		return panda$lowestSurface;
	}

	@Override
	public void panda$setLowestSurfaceCache(long value) {
		panda$lowestSurface = value;
	}

	@Shadow
	@Final
	Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void panda$markDirty(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		BlockState previous = cir.getReturnValue();
		if (previous != null && previous.isSolidRender() != state.isSolidRender() && level instanceof ServerLevel serverLevel) {
			Occlusion.markDirty(serverLevel, pos);
		}
	}
}
