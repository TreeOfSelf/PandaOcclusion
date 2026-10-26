package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;
import java.util.Set;

public final class OreTrace {
	private OreTrace() {
	}

	private static volatile Set<Block> blocks = Set.of();
	private static volatile Map<Block, BlockState> fillers = Map.of();
	private static volatile int epoch = 1;

	static void set(Set<Block> traced) {
		Map<Block, BlockState> filler = new java.util.HashMap<>();
		for (Block block : traced) {
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			filler.put(block, path.startsWith("nether_") || path.equals("ancient_debris") ? Blocks.NETHERRACK.defaultBlockState()
				: path.startsWith("deepslate") ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState());
		}
		blocks = Set.copyOf(traced);
		fillers = filler;
		epoch++;
	}

	public static boolean isTrace(BlockState state) {
		return blocks.contains(state.getBlock());
	}

	public static BlockState filler(BlockState ore) {
		BlockState filler = fillers.get(ore.getBlock());
		return filler != null ? filler : Blocks.STONE.defaultBlockState();
	}

	static long[] bits(LevelChunkSection section) {
		SectionVisHolder holder = (SectionVisHolder) section;
		long[] cached = holder.panda$oreBits();
		int current = epoch;
		if (cached != null && cached[cached.length - 1] == current) return cached.length == 1 ? null : cached;
		Set<Block> traced = blocks;
		long[] out = null;
		if (!traced.isEmpty() && !section.hasOnlyAir() && section.getStates().maybeHas(state -> traced.contains(state.getBlock()))) {
			out = new long[65];
			boolean any = false;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (!traced.contains(section.getBlockState(x, y, z).getBlock())) continue;
						int bit = x | z << 4 | y << 8;
						out[bit >> 6] |= 1L << (bit & 63);
						any = true;
					}
				}
			}
			if (!any) out = null;
		}
		if (out == null) out = new long[1];
		out[out.length - 1] = current;
		holder.panda$setOreBits(out);
		return out.length == 1 ? null : out;
	}

	public static void onSet(SectionVisHolder holder, int x, int y, int z, BlockState state) {
		long[] bits = holder.panda$oreBits();
		if (bits == null) return;
		boolean traced = isTrace(state);
		if (bits.length == 1) {
			if (traced) holder.panda$setOreBits(null);
			return;
		}
		int bit = x | z << 4 | y << 8;
		if (traced) bits[bit >> 6] |= 1L << (bit & 63);
		else bits[bit >> 6] &= ~(1L << (bit & 63));
	}
}
