package me.TreeOfSelf.PandaOcclusion;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public class VoicechatCompat implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "panda-occlusion";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(EntitySoundPacketEvent.class, VoicechatCompat::onEntitySound);
	}

	private static void onEntitySound(EntitySoundPacketEvent event) {
		VoicechatConnection receiver = event.getReceiverConnection();
		VoicechatConnection sender = event.getSenderConnection();
		if (receiver == null || sender == null) return;
		EntitySoundPacket packet = event.getPacket();
		if (!packet.getEntityUuid().equals(sender.getPlayer().getUuid())) return;
		if (!Occlusion.isPlayerHidden(receiver.getPlayer().getUuid(), packet.getEntityUuid())) return;
		if (!(sender.getPlayer().getPlayer() instanceof ServerPlayer speaker)) return;
		Vec3 eye = speaker.getEyePosition();
		event.cancel();
		event.getVoicechat().sendLocationalSoundPacketTo(receiver, packet.locationalSoundPacketBuilder()
			.position(event.getVoicechat().createPosition(eye.x, eye.y, eye.z))
			.distance(packet.getDistance())
			.build());
	}
}
