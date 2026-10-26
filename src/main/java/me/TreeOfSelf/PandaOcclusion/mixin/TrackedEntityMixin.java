package me.TreeOfSelf.PandaOcclusion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.TreeOfSelf.PandaOcclusion.HiddenSounds;
import me.TreeOfSelf.PandaOcclusion.HiddenTracking;
import me.TreeOfSelf.PandaOcclusion.Occlusion;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements HiddenTracking {
	@Shadow @Final Entity entity;
	@Shadow @Final private Set<ServerPlayerConnection> seenBy;

	@Shadow
	protected abstract int getEffectiveRange();

	@Override
	public boolean panda$hiddenByOcclusion(ServerPlayer player) {
		if (seenBy.contains(player.connection) || player.level() != entity.level()) return false;
		ChunkMap chunkMap = ((ServerLevel) entity.level()).getChunkSource().chunkMap;
		Vec3 delta = player.position().subtract(entity.position());
		double range = Math.min(getEffectiveRange(), chunkMap.getPlayerViewDistance(player) * 16);
		return delta.x * delta.x + delta.z * delta.z <= range * range
			&& entity.broadcastToPlayer(player)
			&& chunkMap.isChunkTracked(player, entity.chunkPosition().x(), entity.chunkPosition().z());
	}

	@Inject(method = "sendToTrackingPlayers", at = @At("HEAD"))
	private void panda$hiddenSounds(Packet<? super ClientGamePacketListener> packet, CallbackInfo ci) {
		HiddenSounds.trackedPacket(entity, packet);
	}

	@WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;broadcastToPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z"))
	private boolean panda$hideOccluded(Entity entity, ServerPlayer player, Operation<Boolean> original) {
		if (!original.call(entity, player)) {
			if (entity instanceof Player target) Occlusion.trackHiddenPlayer(player, target, false);
			return false;
		}
		boolean visible = Occlusion.canSee(player, entity);
		if (entity instanceof Player target) Occlusion.trackHiddenPlayer(player, target, !visible);
		// hidden but vanilla would track it: the wither's boss bar stays
		if (!visible && entity instanceof WitherBoss) {
			Occlusion.keepBossBar = ((ServerLevel) entity.level()).getChunkSource().chunkMap
				.isChunkTracked(player, entity.chunkPosition().x(), entity.chunkPosition().z());
		}
		return visible;
	}

	@Inject(method = "updatePlayer", at = @At("TAIL"))
	private void panda$witherBar(ServerPlayer player, CallbackInfo ci) {
		if (entity instanceof WitherBoss wither && player != entity) {
			var bossEvent = ((WitherBossAccessor) wither).panda$getBossEvent();
			if (Occlusion.keepBossBar) bossEvent.addPlayer(player);
			else if (!seenBy.contains(player.connection)) bossEvent.removePlayer(player);
		}
		Occlusion.keepBossBar = false;
	}

	@Inject(method = "removePlayer", at = @At("HEAD"))
	private void panda$witherBarRemovePlayer(ServerPlayer player, CallbackInfo ci) {
		if (entity instanceof WitherBoss wither && !Occlusion.keepBossBar) ((WitherBossAccessor) wither).panda$getBossEvent().removePlayer(player);
	}

	@Inject(method = "broadcastRemoved", at = @At("HEAD"))
	private void panda$witherBarRemoved(CallbackInfo ci) {
		if (entity instanceof WitherBoss wither) ((WitherBossAccessor) wither).panda$getBossEvent().removeAllPlayers();
	}
}
