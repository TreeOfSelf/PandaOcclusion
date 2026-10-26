package me.TreeOfSelf.PandaOcclusion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.TreeOfSelf.PandaOcclusion.ChunkPacketContext;
import me.TreeOfSelf.PandaOcclusion.PandaOcclusionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataMixin {
	@org.spongepowered.asm.mixin.Shadow
	@org.spongepowered.asm.mixin.Final
	@org.spongepowered.asm.mixin.Mutable
	private Map<net.minecraft.world.level.levelgen.Heightmap.Types, long[]> heightmaps;

	@org.spongepowered.asm.mixin.injection.Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("RETURN"))
	private void panda$hideHeightmaps(LevelChunk chunk, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		ChunkPacketContext context = ChunkPacketContext.get(chunk);
		if (context != null) heightmaps = context.heightmaps(heightmaps);
		if (PandaOcclusionConfig.config.enabled && PandaOcclusionConfig.config.shareHeightmaps) heightmaps = ChunkPacketContext.shareHeightmaps(heightmaps);
	}

	@WrapOperation(method = {"calculateChunkSize", "extractChunkData"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
	private static LevelChunkSection[] panda$hideSections(LevelChunk chunk, Operation<LevelChunkSection[]> original) {
		ChunkPacketContext context = ChunkPacketContext.get(chunk);
		return context != null ? context.sections() : original.call(chunk);
	}

	@WrapOperation(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntities()Ljava/util/Map;"))
	private Map<BlockPos, BlockEntity> panda$hideBlockEntities(LevelChunk chunk, Operation<Map<BlockPos, BlockEntity>> original) {
		Map<BlockPos, BlockEntity> blockEntities = original.call(chunk);
		ChunkPacketContext context = ChunkPacketContext.get(chunk);
		return context != null ? context.filterBlockEntities(blockEntities) : blockEntities;
	}
}
