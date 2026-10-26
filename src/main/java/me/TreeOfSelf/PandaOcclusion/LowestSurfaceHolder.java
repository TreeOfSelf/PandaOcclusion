package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

public interface LowestSurfaceHolder {
	long REFRESH_NANOS = 5_000_000_000L;

	long panda$lowestSurfaceCache();

	void panda$setLowestSurfaceCache(long value);

	static int get(LevelChunk chunk) {
		if (chunk.getLevel().dimensionType().hasCeiling()) return Integer.MAX_VALUE;
		LowestSurfaceHolder holder = (LowestSurfaceHolder) chunk;
		long cached = holder.panda$lowestSurfaceCache();
		long now = System.nanoTime();
		// high 32 bits: when it was computed (nanoTime >> 20), low 32 bits: the height
		if (cached != 0 && (now >> 20) - (cached >> 32) < REFRESH_NANOS >> 20) return (int) cached;
		int lowest = Integer.MAX_VALUE;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				lowest = Math.min(lowest, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
			}
		}
		holder.panda$setLowestSurfaceCache((now >> 20) << 32 | (lowest & 0xFFFFFFFFL));
		return lowest;
	}
}
