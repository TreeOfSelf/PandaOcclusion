package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.ChunkPacketContext;
import me.TreeOfSelf.PandaOcclusion.Occlusion;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {
	@Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V", at = @At("TAIL"))
	private void panda$hideLight(LevelChunk chunk, LevelLightEngine lightEngine, BitSet skyFilter, BitSet blockFilter, CallbackInfo ci) {
		ChunkPacketContext context = ChunkPacketContext.get(chunk);
		if (context != null) {
			Occlusion.hideLight(((ClientboundLevelChunkWithLightPacket) (Object) this).lightData(), context.hidden(),
				context.skippedLight(lightEngine.getLightSectionCount()));
			if (context.fills() != null) Occlusion.zeroPocketLight(((ClientboundLevelChunkWithLightPacket) (Object) this).lightData(), context.fills());
		}
	}
}
