package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class AimReveal {
	private static final MethodHandle GET_CONTROLLER;
	private static final MethodHandle LEFT_CLICK;

	static {
		MethodHandle getController = null, leftClick = null;
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("antixray")) {
			try {
				Class<?> util = Class.forName("me.drex.antixray.common.util.Util");
				Class<?> controller = Class.forName("me.drex.antixray.common.util.controller.ChunkPacketBlockController");
				MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				getController = lookup.findStatic(util, "getBlockController", MethodType.methodType(controller, Level.class));
				leftClick = lookup.findVirtual(controller, "onPlayerLeftClickBlock", MethodType.methodType(void.class,
					net.minecraft.server.level.ServerPlayerGameMode.class, BlockPos.class, ServerboundPlayerActionPacket.Action.class, Direction.class, int.class));
			} catch (ReflectiveOperationException e) {
				PandaOcclusion.LOGGER.warn("revealAroundAim: AntiXray's API not found, off", e);
				getController = null;
				leftClick = null;
			}
		}
		GET_CONTROLLER = getController;
		LEFT_CLICK = leftClick;
	}

	static void tick(ServerPlayer player, PlayerOcclusion state) {
		if (LEFT_CLICK == null || !PandaOcclusionConfig.config.revealAroundAim || player.isSpectator() || state.bypass) return;
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
		// clip() loads every chunk it crosses and blocks the server thread until they exist. Right after a
		// player arrives (portal, login, pearl, fast elytra) their own chunk often isn't ready yet: skip until it is.
		if (!allLoaded(player.level(), eye, end)) return;
		BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		long key = pos.asLong();
		if (key == state.aimRevealLast) return;
		state.aimRevealLast = key;
		try {
			Object controller = GET_CONTROLLER.invoke((Level) player.level());
			if (controller != null) LEFT_CLICK.invoke(controller, player.gameMode, pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, hit.getDirection(), 0);
		} catch (Throwable t) {
			PandaOcclusion.LOGGER.warn("revealAroundAim failed", t);
		}
	}

	private static boolean allLoaded(Level level, Vec3 a, Vec3 b) {
		int x0 = net.minecraft.util.Mth.floor(Math.min(a.x, b.x)) >> 4, x1 = net.minecraft.util.Mth.floor(Math.max(a.x, b.x)) >> 4;
		int z0 = net.minecraft.util.Mth.floor(Math.min(a.z, b.z)) >> 4, z1 = net.minecraft.util.Mth.floor(Math.max(a.z, b.z)) >> 4;
		for (int x = x0; x <= x1; x++)
			for (int z = z0; z <= z1; z++)
				if (level.getChunkSource().getChunkNow(x, z) == null) return false;
		return true;
	}
}
