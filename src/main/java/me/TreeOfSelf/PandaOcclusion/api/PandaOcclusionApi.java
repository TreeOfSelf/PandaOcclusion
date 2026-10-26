package me.TreeOfSelf.PandaOcclusion.api;

import me.TreeOfSelf.PandaOcclusion.Occlusion;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

public final class PandaOcclusionApi {
	private PandaOcclusionApi() {
	}

	public static boolean canSee(ServerPlayer player, AABB box) {
		return Occlusion.canSeeBox(player, box);
	}
}
