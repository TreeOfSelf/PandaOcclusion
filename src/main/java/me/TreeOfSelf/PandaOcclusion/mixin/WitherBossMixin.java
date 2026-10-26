package me.TreeOfSelf.PandaOcclusion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.TreeOfSelf.PandaOcclusion.Occlusion;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WitherBoss.class)
public abstract class WitherBossMixin {
	@WrapOperation(method = "stopSeenByPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerBossEvent;removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
	private void panda$keepBar(ServerBossEvent bossEvent, ServerPlayer player, Operation<Void> original) {
		if (!Occlusion.keepBossBar) original.call(bossEvent, player);
	}
}
