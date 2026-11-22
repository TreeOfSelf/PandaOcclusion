package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.server.level.ServerPlayer;

public interface HiddenTracking {
	boolean panda$hiddenByOcclusion(ServerPlayer player);

	// vanilla currently sends this entity to the player
	boolean panda$seenBy(ServerPlayer player);

	net.minecraft.world.entity.Entity panda$entity();
}
