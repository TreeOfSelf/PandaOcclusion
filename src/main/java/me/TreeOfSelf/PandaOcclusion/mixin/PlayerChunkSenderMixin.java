package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.ChunkPacketContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.TreeOfSelf.PandaOcclusion.Occlusion;
import me.TreeOfSelf.PandaOcclusion.OcclusionHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
	@Inject(method = "sendChunk", at = @At("HEAD"))
	private static void panda$beginChunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
		ChunkPacketContext.begin(connection.player, chunk, true);
	}

	@Inject(method = "sendChunk", at = @At("RETURN"))
	private static void panda$endChunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
		ChunkPacketContext.end();
	}

	@Shadow
	@Final
	private LongSet pendingChunks;

	@WrapOperation(method = "sendNextChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/PlayerChunkSender;collectChunksToSend(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;"))
	private List<LevelChunk> panda$holdUncovered(PlayerChunkSender sender, ChunkMap chunkMap, ChunkPos playerPos, Operation<List<LevelChunk>> original, ServerPlayer player) {
		return Occlusion.holdUncovered(player, original.call(sender, chunkMap, playerPos), pendingChunks);
	}

	@Inject(method = "dropChunk", at = @At("HEAD"))
	private void panda$forgetChunk(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
		((OcclusionHolder) player).panda$getOcclusion().forget(pos.pack());
	}
}
