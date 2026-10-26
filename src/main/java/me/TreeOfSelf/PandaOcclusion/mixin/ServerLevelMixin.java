package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.HiddenSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"))
	private void panda$startTick(Entity entity, CallbackInfo ci) {
		HiddenSounds.startTicking(entity);
	}

	@Inject(method = "tickNonPassenger", at = @At("RETURN"))
	private void panda$endTick(Entity entity, CallbackInfo ci) {
		HiddenSounds.afterTick(entity);
	}

	@Inject(method = "tickPassenger", at = @At("HEAD"))
	private void panda$startPassengerTick(Entity vehicle, Entity entity, CallbackInfo ci) {
		HiddenSounds.startTicking(entity);
	}

	@Inject(method = "tickPassenger", at = @At("RETURN"))
	private void panda$endPassengerTick(Entity vehicle, Entity entity, CallbackInfo ci) {
		HiddenSounds.afterTick(entity);
	}
}
