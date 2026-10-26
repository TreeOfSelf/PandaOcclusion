package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.OcclusionHolder;
import me.TreeOfSelf.PandaOcclusion.PlayerOcclusion;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements OcclusionHolder {
	@Unique
	private final PlayerOcclusion panda$occlusion = new PlayerOcclusion();

	@Override
	public PlayerOcclusion panda$getOcclusion() {
		return panda$occlusion;
	}
}
