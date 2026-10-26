package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.HiddenSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V", at = @At("HEAD"))
	private void panda$localSound(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay, CallbackInfo ci) {
		HiddenSounds.localSound((Level) (Object) this, x, y, z, sound, source, volume, pitch, distanceDelay);
	}

	@Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"))
	private void panda$localEntitySound(Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
		HiddenSounds.localEntitySound((Level) (Object) this, entity, sound, source, volume, pitch);
	}

	@Inject(method = "playPlayerSound", at = @At("HEAD"))
	private void panda$playerSound(SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
		HiddenSounds.playerSound((Level) (Object) this, sound, source, volume, pitch);
	}
}
