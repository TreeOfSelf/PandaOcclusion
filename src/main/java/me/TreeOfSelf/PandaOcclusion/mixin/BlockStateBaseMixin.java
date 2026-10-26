package me.TreeOfSelf.PandaOcclusion.mixin;

import me.TreeOfSelf.PandaOcclusion.MayPlayHolder;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin implements MayPlayHolder {
	@Unique
	private byte panda$mayPlay = -1;

	@Override
	public byte panda$mayPlay() {
		return panda$mayPlay;
	}

	@Override
	public void panda$setMayPlay(byte mayPlay) {
		panda$mayPlay = mayPlay;
	}
}
