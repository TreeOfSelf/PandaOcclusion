package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

public final class SectionVis {
	public static final long UNKNOWN = -1L;
	public static final long ALL = (1L << 42) - 1;
	private static final int NEAR_FACE = 2;

	// every index on the section's outer shell, same order as VisGraph.INDEX_OF_EDGES
	private static final int[] EDGES = new int[1352];

	static {
		int i = 0;
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
						EDGES[i++] = x | z << 4 | y << 8;
					}
				}
			}
		}
	}

	private static final ThreadLocal<boolean[]> BLOCKED = ThreadLocal.withInitial(() -> new boolean[4096]);
	private static final ThreadLocal<int[]> QUEUE = ThreadLocal.withInitial(() -> new int[4096]);

	private SectionVis() {
	}

	public static long get(LevelChunkSection section) {
		AtomicLong cell = ((SectionVisHolder) section).panda$vis();
		long cached = cell.get();
		if (cached >= 0) return cached;
		long start = System.nanoTime();
		long vis;
		long[] faces;
		try {
			vis = compute(section);
			faces = LAST_FACES.get();
		} catch (RuntimeException e) {
			return ALL;
		}
		((SectionVisHolder) section).panda$setFaces(faces);
		((SectionVisHolder) section).panda$setOpaque(LAST_OPAQUE.get());
		cell.compareAndSet(cached, vis);
		Occlusion.STATS.visComputes.increment();
		Occlusion.STATS.visNanos.add(System.nanoTime() - start);
		return vis;
	}

	private static final AtomicLong INVALIDATIONS = new AtomicLong();

	public static void invalidate(LevelChunkSection section) {
		((SectionVisHolder) section).panda$vis().set(-2 - (INVALIDATIONS.incrementAndGet() & Long.MAX_VALUE >> 1));
	}

	public static final long[] ALL_OPEN = filled(-1L);
	public static final long[] NONE_OPEN = filled(0L);
	private static final ThreadLocal<long[]> LAST_FACES = new ThreadLocal<>();
	private static final ThreadLocal<long[]> LAST_OPAQUE = new ThreadLocal<>();
	public static final long[] NO_OPAQUE = new long[64];
	public static final long[] ALL_OPAQUE = filled64(-1L);

	private static long[] filled64(long value) {
		long[] bits = new long[64];
		java.util.Arrays.fill(bits, value);
		return bits;
	}

	public static long[] opaqueBits(LevelChunkSection section) {
		get(section);
		return ((SectionVisHolder) section).panda$opaque();
	}

	private static long[] filled(long value) {
		long[] faces = new long[24];
		java.util.Arrays.fill(faces, value);
		return faces;
	}

	public static long[] openFaces(LevelChunkSection section) {
		get(section);
		long[] faces = ((SectionVisHolder) section).panda$faces();
		return faces != null ? faces : ALL_OPEN;
	}

	public static boolean sealed(LevelChunkSection section, int face) {
		long[] faces = openFaces(section);
		return (faces[face * 4] | faces[face * 4 + 1] | faces[face * 4 + 2] | faces[face * 4 + 3]) == 0;
	}

	public static boolean open(long[] faces, int face, int u, int v) {
		int bit = u * 16 + v;
		return (faces[face * 4 + (bit >> 6)] >>> (bit & 63) & 1L) != 0;
	}

	public static int nearFaces(long vis) {
		return (int) (vis >>> 36) & 63;
	}

	public static int components(long vis) {
		return (int) (vis >>> COMPONENTS_SHIFT) & 255;
	}

	private static final int COMPONENTS_SHIFT = 42;

	private static int countComponents(boolean[] opaque) {
		boolean[] done = COUNT_DONE.get();
		System.arraycopy(opaque, 0, done, 0, 4096);
		int[] queue = QUEUE.get();
		int count = 0;
		for (int start = 0; start < 4096; start++) {
			if (done[start]) continue;
			count++;
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			done[start] = true;
			while (head < tail) {
				int index = queue[head++];
				int x = index & 15;
				int z = index >> 4 & 15;
				int y = index >> 8 & 15;
				if (y > 0 && !done[index - 256]) { done[index - 256] = true; queue[tail++] = index - 256; }
				if (y < 15 && !done[index + 256]) { done[index + 256] = true; queue[tail++] = index + 256; }
				if (z > 0 && !done[index - 16]) { done[index - 16] = true; queue[tail++] = index - 16; }
				if (z < 15 && !done[index + 16]) { done[index + 16] = true; queue[tail++] = index + 16; }
				if (x > 0 && !done[index - 1]) { done[index - 1] = true; queue[tail++] = index - 1; }
				if (x < 15 && !done[index + 1]) { done[index + 1] = true; queue[tail++] = index + 1; }
			}
		}
		return Math.min(count, 255);
	}

	private static final ThreadLocal<boolean[]> COUNT_DONE = ThreadLocal.withInitial(() -> new boolean[4096]);

	public static boolean connects(long vis, int from, int to) {
		return (vis >>> (from * 6 + to) & 1L) != 0;
	}

	private static final class Filler implements java.util.function.IntConsumer {
		boolean[] lookup;
		boolean[] out;
		int index;
		int opaque;

		@Override
		public void accept(int id) {
			boolean solid = lookup[id];
			out[index++] = solid;
			if (solid) opaque++;
		}
	}

	private static final ThreadLocal<Filler> FILLER = ThreadLocal.withInitial(Filler::new);
	private static volatile boolean[] globalOpaque;

	private static int fillOpaque(PalettedContainer<BlockState> states, boolean[] out) {
		PalettedContainer.Data<BlockState> data = states.data;
		Palette<BlockState> palette = data.palette();
		boolean[] lookup;
		if (palette instanceof GlobalPalette) {
			lookup = globalOpaque();
		} else {
			lookup = new boolean[palette.getSize()];
			for (int i = 0; i < lookup.length; i++) {
				lookup[i] = palette.valueFor(i).isSolidRender();
			}
		}
		Filler filler = FILLER.get();
		filler.lookup = lookup;
		filler.out = out;
		filler.index = 0;
		filler.opaque = 0;
		data.storage().getAll(filler);
		return filler.opaque;
	}

	private static boolean[] globalOpaque() {
		boolean[] lookup = globalOpaque;
		if (lookup == null) {
			lookup = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
			for (int i = 0; i < lookup.length; i++) {
				BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);
				lookup[i] = state != null && state.isSolidRender();
			}
			globalOpaque = lookup;
		}
		return lookup;
	}

	static long compute(LevelChunkSection section) {
		LAST_FACES.set(ALL_OPEN);
		LAST_OPAQUE.set(NO_OPAQUE);
		if (section.hasOnlyAir()) {
			return ALL;
		}
		PalettedContainer<BlockState> states = section.getStates();
		if (!states.maybeHas(BlockState::isSolidRender)) {
			return ALL;
		}
		if (!states.maybeHas(state -> !state.isSolidRender())) {
			LAST_FACES.set(NONE_OPEN);
			LAST_OPAQUE.set(ALL_OPAQUE);
			return 0;
		}

		boolean[] blocked = BLOCKED.get();
		int opaque = fillOpaque(states, blocked);
		long[] opaqueBits = new long[64];
		for (int index = 0; index < 4096; index++) {
			if (blocked[index]) opaqueBits[index >> 6] |= 1L << (index & 63);
		}
		LAST_OPAQUE.set(opaqueBits);
		long[] openFaces = new long[24];
		for (int a = 0; a < 16; a++) {
			for (int b = 0; b < 16; b++) {
				int bit = a * 16 + b;
				int word = bit >> 6;
				long mask = 1L << (bit & 63);
				if (!blocked[a | b << 4]) openFaces[word] |= mask;
				if (!blocked[a | b << 4 | 15 << 8]) openFaces[4 + word] |= mask;
				if (!blocked[a | b << 8]) openFaces[8 + word] |= mask;
				if (!blocked[a | 15 << 4 | b << 8]) openFaces[12 + word] |= mask;
				if (!blocked[b << 4 | a << 8]) openFaces[16 + word] |= mask;
				if (!blocked[15 | b << 4 | a << 8]) openFaces[20 + word] |= mask;
			}
		}
		LAST_FACES.set(openFaces);
		long components = (long) countComponents(blocked) << COMPONENTS_SHIFT;
		// same shortcut as the client: almost-empty sections count as fully open
		if (opaque < 256) {
			return ALL | components;
		}

		int near = 0;
		for (int index = 0; index < 4096; index++) {
			if (blocked[index]) continue;
			int x = index & 15;
			int z = index >> 4 & 15;
			int y = index >> 8 & 15;
			if (y < NEAR_FACE) near |= 1;
			if (y >= 16 - NEAR_FACE) near |= 2;
			if (z < NEAR_FACE) near |= 4;
			if (z >= 16 - NEAR_FACE) near |= 8;
			if (x < NEAR_FACE) near |= 16;
			if (x >= 16 - NEAR_FACE) near |= 32;
			if (near == 63) break;
		}
		long vis = (long) near << 36 | components;
		int[] queue = QUEUE.get();
		for (int start : EDGES) {
			if (blocked[start]) {
				continue;
			}
			int faces = 0;
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			blocked[start] = true;
			while (head < tail) {
				int index = queue[head++];
				int x = index & 15;
				int z = index >> 4 & 15;
				int y = index >> 8 & 15;
				if (y == 0) faces |= 1;
				else if (y == 15) faces |= 2;
				if (z == 0) faces |= 4;
				else if (z == 15) faces |= 8;
				if (x == 0) faces |= 16;
				else if (x == 15) faces |= 32;

				if (y > 0 && !blocked[index - 256]) { blocked[index - 256] = true; queue[tail++] = index - 256; }
				if (y < 15 && !blocked[index + 256]) { blocked[index + 256] = true; queue[tail++] = index + 256; }
				if (z > 0 && !blocked[index - 16]) { blocked[index - 16] = true; queue[tail++] = index - 16; }
				if (z < 15 && !blocked[index + 16]) { blocked[index + 16] = true; queue[tail++] = index + 16; }
				if (x > 0 && !blocked[index - 1]) { blocked[index - 1] = true; queue[tail++] = index - 1; }
				if (x < 15 && !blocked[index + 1]) { blocked[index + 1] = true; queue[tail++] = index + 1; }
			}
			for (int a = 0; a < 6; a++) {
				if ((faces >> a & 1) == 0) continue;
				for (int b = 0; b < 6; b++) {
					if ((faces >> b & 1) != 0) {
						vis |= 1L << (a * 6 + b);
					}
				}
			}
		}
		return vis;
	}
}
