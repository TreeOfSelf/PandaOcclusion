package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.OcclusionHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	// vanilla counts pending chunks as untracked, which would take every entity in the column off the client
	@Inject(method = "isChunkTracked", at = @At("RETURN"), cancellable = true)
	private void panda$resendStillTracked(ServerPlayer player, int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || !player.getChunkTrackingView().contains(chunkX, chunkZ)) return;
		if (((OcclusionHolder) player).panda$getOcclusion().hasColumn(ChunkPos.pack(chunkX, chunkZ))) cir.setReturnValue(true);
	}
}
