package me.TreeOfSelf.PandaOcclusion.mixin;

import io.netty.channel.ChannelFutureListener;
import me.TreeOfSelf.PandaOcclusion.Occlusion;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
	@Shadow
	public abstract void send(Packet<?> packet, @Nullable ChannelFutureListener listener);

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
	private void panda$filter(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo ci) {
		if (!((Object) this instanceof ServerGamePacketListenerImpl game)) return;
		Packet<?> filtered = Occlusion.filter(game.player, packet);
		if (filtered == packet) return;
		ci.cancel();
		if (filtered != null) {
			send(filtered, listener);
		}
	}
}
