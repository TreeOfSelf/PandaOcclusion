package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.HiddenSounds;
import me.TreeOfSelf.PandaOcclusion.SectionVis;
import me.TreeOfSelf.PandaOcclusion.SectionVisHolder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements SectionVisHolder {
	@Unique
	private final AtomicLong panda$vis = new AtomicLong(SectionVis.UNKNOWN);

	@Unique
	private volatile long[] panda$faces;

	@Override
	public AtomicLong panda$vis() {
		return panda$vis;
	}

	@Unique
	private volatile long[] panda$opaque;

	@Override
	public long[] panda$opaque() {
		return panda$opaque;
	}

	@Override
	public void panda$setOpaque(long[] opaque) {
		panda$opaque = opaque;
	}

	@Override
	public long[] panda$faces() {
		return panda$faces;
	}

	@Override
	public void panda$setFaces(long[] faces) {
		panda$faces = faces;
	}

	@Unique
	private volatile byte panda$mayPlay = -1;

	@Override
	public byte panda$mayPlay() {
		return panda$mayPlay;
	}

	@Override
	public void panda$setMayPlay(byte mayPlay) {
		panda$mayPlay = mayPlay;
	}

	@Unique
	private volatile long[] panda$soundBits;

	@Override
	public long[] panda$soundBits() {
		return panda$soundBits;
	}

	@Override
	public void panda$setSoundBits(long[] bits) {
		panda$soundBits = bits;
	}

	@Unique
	private volatile java.lang.ref.SoftReference<me.TreeOfSelf.PandaOcclusion.SectionGroups> panda$groups;

	@Override
	public java.lang.ref.SoftReference<me.TreeOfSelf.PandaOcclusion.SectionGroups> panda$groups() {
		return panda$groups;
	}

	@Override
	public void panda$setGroups(java.lang.ref.SoftReference<me.TreeOfSelf.PandaOcclusion.SectionGroups> groups) {
		panda$groups = groups;
	}

	@Unique
	private volatile long[] panda$oreBits;

	@Override
	public long[] panda$oreBits() {
		return panda$oreBits;
	}

	@Override
	public void panda$setOreBits(long[] bits) {
		panda$oreBits = bits;
	}

	@Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
	private void panda$invalidateVis(int x, int y, int z, BlockState state, boolean checkThreading, CallbackInfoReturnable<BlockState> cir) {
		boolean mayPlay = HiddenSounds.mayPlay(state);
		if (panda$mayPlay == 0 && mayPlay) panda$mayPlay = 1;
		long[] bits = panda$soundBits;
		if (bits != null) {
			int index = x | z << 4 | y << 8;
			if (mayPlay) bits[index >> 6] |= 1L << index;
			else bits[index >> 6] &= ~(1L << index);
		}
		me.TreeOfSelf.PandaOcclusion.OreTrace.onSet(this, x, y, z, state);
		BlockState previous = cir.getReturnValue();
		if (previous != state && previous.isSolidRender() != state.isSolidRender()) {
			SectionVis.invalidate((LevelChunkSection) (Object) this);
		}
	}
}
