package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.Occlusion;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	// handlePlayerAction runs on the netty thread and again on the server thread
	@Inject(method = "handlePlayerAction", at = @At("HEAD"))
	private void panda$digStart(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && player.level().getServer().isSameThread()) {
			Occlusion.onDigStart(player, packet.getPos());
		}
	}

	@Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private void panda$revealDestination(PositionMoveRotation destination, Set<Relative> relatives, CallbackInfo ci) {
		Occlusion.onTeleport(player);
	}
}
