package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.ref.SoftReference;

public final class SectionGroups {
	final long[] opaque;
	final long[][] faces;
	final long[][] cells;
	final long[] enclosed;

	private SectionGroups(long[] opaque, long[][] faces, long[][] cells, long[] enclosed) {
		this.opaque = opaque;
		this.faces = faces;
		this.cells = cells;
		this.enclosed = enclosed;
	}

	private static final ThreadLocal<int[]> QUEUE = ThreadLocal.withInitial(() -> new int[4096]);

	static SectionGroups of(LevelChunkSection section) {
		long[] opaque = SectionVis.opaqueBits(section);
		if (opaque == null) return null;
		SectionVisHolder holder = (SectionVisHolder) section;
		SoftReference<SectionGroups> cached = holder.panda$groups();
		SectionGroups groups = cached == null ? null : cached.get();
		if (groups != null && groups.opaque == opaque) return groups;
		groups = build(opaque);
		holder.panda$setGroups(new SoftReference<>(groups));
		return groups;
	}

	private static SectionGroups build(long[] opaque) {
		long[] reached = opaque.clone();
		java.util.List<long[]> faces = new java.util.ArrayList<>();
		java.util.List<long[]> cells = new java.util.ArrayList<>();
		int[] queue = QUEUE.get();
		for (int face = 0; face < 6; face++) {
			for (int a = 0; a < 16; a++) {
				for (int b = 0; b < 16; b++) {
					int start = faceIndex(face, a, b);
					if ((reached[start >> 6] >>> (start & 63) & 1L) != 0) continue;
					long[] groupCells = new long[64];
					int head = 0;
					int tail = 0;
					queue[tail++] = start;
					reached[start >> 6] |= 1L << (start & 63);
					while (head < tail) {
						int index = queue[head++];
						groupCells[index >> 6] |= 1L << (index & 63);
						int x = index & 15;
						int z = index >> 4 & 15;
						int y = index >> 8 & 15;
						if (y > 0) tail = visit(reached, queue, tail, index - 256);
						if (y < 15) tail = visit(reached, queue, tail, index + 256);
						if (z > 0) tail = visit(reached, queue, tail, index - 16);
						if (z < 15) tail = visit(reached, queue, tail, index + 16);
						if (x > 0) tail = visit(reached, queue, tail, index - 1);
						if (x < 15) tail = visit(reached, queue, tail, index + 1);
					}
					long[] groupFaces = new long[24];
					for (int f = 0; f < 6; f++) {
						for (int u = 0; u < 16; u++) {
							for (int v = 0; v < 16; v++) {
								int index = faceIndex(f, u, v);
								if ((groupCells[index >> 6] >>> (index & 63) & 1L) == 0) continue;
								int bit = u * 16 + v;
								groupFaces[f * 4 + (bit >> 6)] |= 1L << (bit & 63);
							}
						}
					}
					faces.add(groupFaces);
					cells.add(groupCells);
				}
			}
		}
		long[] enclosed = new long[64];
		for (int k = 0; k < 64; k++) enclosed[k] = ~reached[k];
		return new SectionGroups(opaque, faces.toArray(new long[0][]), cells.toArray(new long[0][]), enclosed);
	}

	private static int visit(long[] reached, int[] queue, int tail, int index) {
		if ((reached[index >> 6] >>> (index & 63) & 1L) != 0) return tail;
		reached[index >> 6] |= 1L << (index & 63);
		queue[tail] = index;
		return tail + 1;
	}

	static long[] flood(long[] open, int start) {
		long[] out = new long[64];
		int[] queue = new int[4096];
		int head = 0;
		int tail = 0;
		queue[tail++] = start;
		out[start >> 6] |= 1L << (start & 63);
		while (head < tail) {
			int index = queue[head++];
			int x = index & 15;
			int z = index >> 4 & 15;
			int y = index >> 8 & 15;
			int[] next = {y > 0 ? index - 256 : -1, y < 15 ? index + 256 : -1, z > 0 ? index - 16 : -1, z < 15 ? index + 16 : -1, x > 0 ? index - 1 : -1, x < 15 ? index + 1 : -1};
			for (int n : next) {
				if (n < 0 || (open[n >> 6] >>> (n & 63) & 1L) == 0 || (out[n >> 6] >>> (n & 63) & 1L) != 0) continue;
				out[n >> 6] |= 1L << (n & 63);
				queue[tail++] = n;
			}
		}
		return out;
	}

	// block index x | z << 4 | y << 8
	static int faceIndex(int face, int a, int b) {
		return switch (face) {
			case 0 -> a | b << 4;
			case 1 -> a | b << 4 | 15 << 8;
			case 2 -> a | b << 8;
			case 3 -> a | 15 << 4 | b << 8;
			case 4 -> b << 4 | a << 8;
			default -> 15 | b << 4 | a << 8;
		};
	}
}
