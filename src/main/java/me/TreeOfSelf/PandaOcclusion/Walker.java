package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class Walker {
	private static final int FREE = 1 << 6;
	private static final int ADVANCED_CULLING_DISTANCE = SectionPos.blockToSectionCoord(60);
	private static final double SECTION_DIAGONAL = Math.ceil(Math.sqrt(3.0) * 16.0);

	private int minSectionY;
	private int height;
	private int radius = -1;
	private int width;
	private int originX;
	private int originZ;
	private int eyeSectionX;
	private int eyeSectionY;
	private int eyeSectionZ;
	boolean keepColumns;
	boolean hideSurface = true;

	private LevelChunk[] columns = new LevelChunk[0];
	private byte[] directions = new byte[0];
	private byte[] sources = new byte[0];
	private int[] seen = new int[0];
	private int[] queue = new int[0];
	private int[] visited = new int[0];
	private int[] parent = new int[0];
	private byte[] rays = new byte[0];
	private int[] topFilled = new int[0];
	private int[] lowestSurface = new int[0];
	private int[] lowestSurfaceEpoch = new int[0];
	private static final byte RAY_PENDING = 0;
	private static final byte RAY_VISIBLE = 1;
	private static final byte RAY_HIDDEN = 2;
	private int[] rayEpoch = new int[0];
	private long[][] opaqueCache = new long[0][];
	private int[] opaqueEpoch = new int[0];
	private static final long[] UNKNOWN_OPAQUE = new long[0];
	private byte[] rayState = new byte[0];
	private boolean rayPruning;
	private Vec3 eye;
	private Vec3[] sightPoints = new Vec3[0];
	Vec3 motion = Vec3.ZERO;
	Vec3[] cameraPoints = new Vec3[0];
	private boolean lineOfSight;
	private int head;
	private int tail;
	private int visitedCount;
	private boolean skyFlooded;
	private int epoch;
	private long[] shellMask = new long[0];

	private void allocate(int newRadius) {
		radius = newRadius;
		width = 2 * newRadius + 1;
		int cells = width * width * height;
		columns = new LevelChunk[width * width];
		directions = new byte[cells];
		sources = new byte[cells];
		seen = new int[cells];
		queue = new int[cells];
		visited = new int[cells];
		parent = new int[cells];
		rays = new byte[cells];
		topFilled = new int[width * width];
		lowestSurface = new int[width * width];
		lowestSurfaceEpoch = new int[width * width];
		rayEpoch = new int[cells];
		rayState = new byte[cells];
		opaqueCache = new long[cells][];
		opaqueEpoch = new int[cells];
		epoch = 0;
	}

	View walk(ServerLevel level, Vec3 eye, int viewDistance, boolean bypass) {
		minSectionY = level.getMinSectionY();
		height = level.getSectionsCount();
		long fullMask = height >= 64 ? -1L : (1L << height) - 1;
		int newRadius = viewDistance + 1;
		if (newRadius != radius || seen.length != (2 * newRadius + 1) * (2 * newRadius + 1) * height) {
			allocate(newRadius);
		}
		eyeSectionX = SectionPos.blockToSectionCoord(Mth.floor(eye.x));
		eyeSectionY = SectionPos.blockToSectionCoord(Mth.floor(eye.y));
		eyeSectionZ = SectionPos.blockToSectionCoord(Mth.floor(eye.z));
		originX = eyeSectionX - radius;
		originZ = eyeSectionZ - radius;

		Map<Long, LevelChunk> loaded = LoadedChunks.of(level);
		boolean[] covered = new boolean[width * width];
		for (int dz = 0; dz < width; dz++) {
			for (int dx = 0; dx < width; dx++) {
				LevelChunk chunk = loaded.get(ChunkPos.pack(originX + dx, originZ + dz));
				columns[dz * width + dx] = chunk;
				covered[dz * width + dx] = chunk != null;
			}
		}
		long[] columnMask = new long[width * width];
		shellMask = new long[width * width];

		if (bypass) {
			Arrays.fill(columnMask, fullMask);
			if (!keepColumns) Arrays.fill(columns, null);
			View view = new View(originX, originZ, width, radius, eyeSectionX, eyeSectionY, eyeSectionZ, columnMask, covered, 0);
			view.eye = eye;
			view.deepReason = "bypass";
			return view;
		}

		if (++epoch == 0) {
			Arrays.fill(seen, 0);
			Arrays.fill(rayEpoch, 0);
			Arrays.fill(opaqueEpoch, 0);
			Arrays.fill(lowestSurfaceEpoch, 0);
			epoch = 1;
		}
		this.eye = eye;
		lineOfSight = PandaOcclusionConfig.config.caveLineOfSight;
		rayPruning = false;
		sightPoints = sightPoints(eye);
		rayPruning = true;
		head = 0;
		tail = 0;
		visitedCount = 0;
		skyFlooded = false;
		int capacity = queue.length;
		int layer = width * width;
		for (int column = 0; column < layer; column++) {
			topFilled[column] = columns[column] == null ? -1 : columns[column].getHighestFilledSectionIndex();
		}

		int eyeY = eyeSectionY - minSectionY;
		if (eyeY < 0 || eyeY >= height) {
			floodSky();
		} else {
			for (int oy = -1; oy <= 1; oy++) {
				int y = eyeY + oy;
				if (y < 0 || y >= height) continue;
				for (int oz = -1; oz <= 1; oz++) {
					for (int ox = -1; ox <= 1; ox++) {
						int column = (radius + oz) * width + radius + ox;
						if (columns[column] == null) continue;
						int c = y * layer + column;
						if (y > topFilled[column]) {
							if (!skyFlooded && (ox == 0 && oy == 0 && oz == 0 || !lineOfSight || inSight(c))) floodSky();
							continue;
						}
						if ((ox != 0 || oy != 0 || oz != 0) && lineOfSight && !inSight(c)) continue;
						if (seen[c] == epoch) {
							directions[c] = 0;
							sources[c] = FREE;
						} else {
							push(c, 0, FREE, c);
						}
					}
				}
			}
		}

		while (head != tail) {
			int c = queue[head];
			head = (head + 1) % capacity;
			int column = c % layer;
			int y = c / layer;
			int dx = column % width;
			int dz = column / width;
			long vis = SectionVis.get(columns[column].getSection(y));
			int dirs = directions[c];
			int srcs = sources[c];
			boolean distant = Math.abs(originX + dx - eyeSectionX) > ADVANCED_CULLING_DISTANCE
				|| Math.abs(minSectionY + y - eyeSectionY) > ADVANCED_CULLING_DISTANCE
				|| Math.abs(originZ + dz - eyeSectionZ) > ADVANCED_CULLING_DISTANCE;

			for (int dir = 0; dir < 6; dir++) {
				// never walk back the way we came (the client's smart cull)
				if ((dirs >> (dir ^ 1) & 1) != 0) continue;
				if ((srcs & FREE) == 0) {
					boolean through = false;
					for (int s = 0; s < 6; s++) {
						if ((srcs >> s & 1) != 0 && SectionVis.connects(vis, s ^ 1, dir)) {
							through = true;
							break;
						}
					}
					if (!through) continue;
				}
				int n;
				int nColumn = column;
				switch (dir) {
					case 0 -> { if (y == 0) continue; n = c - layer; }
					case 1 -> { if (y == height - 1) continue; n = c + layer; }
					case 2 -> { if (dz == 0 || columns[column - width] == null) continue; n = c - width; nColumn = column - width; }
					case 3 -> { if (dz == width - 1 || columns[column + width] == null) continue; n = c + width; nColumn = column + width; }
					case 4 -> { if (dx == 0 || columns[column - 1] == null) continue; n = c - 1; nColumn = column - 1; }
					default -> { if (dx == width - 1 || columns[column + 1] == null) continue; n = c + 1; nColumn = column + 1; }
				}
				if (n / layer > topFilled[nColumn]) {
					if (!skyFlooded && (!lineOfSight || inSight(n))) floodSky();
					continue;
				}
				if (seen[n] != epoch && distant && PandaOcclusionConfig.config.farCulling) {
					int axis = dir >> 1;
					int cached = rays[c] >> (axis * 2);
					if ((cached & 1) == 0) {
						boolean pass = rayReachesCamera(dx, y, dz, dir, eye);
						rays[c] |= (byte) ((pass ? 3 : 1) << (axis * 2));
						cached = pass ? 3 : 1;
					}
					if ((cached & 2) == 0) continue;
				}
				if (seen[n] != epoch && lineOfSight && !inSight(n)) continue;
				if (seen[n] != epoch) {
					push(n, dirs | 1 << dir, 1 << dir, c);
				} else {
					sources[n] |= (byte) (1 << dir);
				}
			}
		}

		for (int i = 0; i < visitedCount; i++) {
			int c = visited[i];
			int column = c % layer;
			int y = c / layer;
			int dx = column % width;
			int dz = column / width;
			long bit = 1L << y;
			columnMask[column] |= bit;
			int open = openFaces(columns[column].getSection(y));
			if ((open & 1) != 0 && y > 0) touch(columnMask, column, y - 1, 1);
			if ((open & 2) != 0 && y < height - 1) touch(columnMask, column, y + 1, 0);
			if ((open & 4) != 0 && dz > 0) touch(columnMask, column - width, y, 3);
			if ((open & 8) != 0 && dz < width - 1) touch(columnMask, column + width, y, 2);
			if ((open & 16) != 0 && dx > 0) touch(columnMask, column - 1, y, 5);
			if ((open & 32) != 0 && dx < width - 1) touch(columnMask, column + 1, y, 4);
		}
		if (skyFlooded) {
			for (int column = 0; column < layer; column++) {
				if (columns[column] != null) columnMask[column] |= -1L << (topFilled[column] + 1);
			}
		}
		for (int i = 0; i < columnMask.length; i++) {
			columnMask[i] &= fullMask;
			shellMask[i] &= fullMask & ~columnMask[i];
		}
		View view = new View(originX, originZ, width, radius, eyeSectionX, eyeSectionY, eyeSectionZ, columnMask, covered, visitedCount);
		view.eye = eye;
		view.shellMask = shellMask;
		long[] surfaceMask = new long[layer];
		for (int column = 0; column < layer; column++) {
			if (columns[column] == null) continue;
			int lowest = lowestSurface(column);
			for (int y = height - 1; y >= 0; y--) {
				if (SectionPos.sectionToBlockCoord(minSectionY + y) + 15 < lowest) break;
				surfaceMask[column] |= 1L << y;
			}
		}
		view.surfaceMask = surfaceMask;
		deepReason = !PandaOcclusionConfig.config.hideSurfaceWhenDeep || !hideSurface ? "off" : skyFlooded ? "sky in view" : null;
		skySearched = 0;
		view.deep = deepReason == null && deep();
		view.deepReason = (view.deep ? "deep" : deepReason) + (skySearched > 0 ? String.format(" (sky search: %d open blocks, %.2f ms)", skySearched, skySearchNanos / 1e6) : "");
		if (PandaOcclusionConfig.pocketsOn() && lineOfSight) findPockets(view);
		if (!keepColumns) Arrays.fill(columns, null);
		return view;
	}

	private String deepReason;

	private int eyePocketFaces(LevelChunkSection section) {
		int ex = Mth.floor(eye.x) & 15;
		int ey = Mth.floor(eye.y) & 15;
		int ez = Mth.floor(eye.z) & 15;
		boolean[] done = new boolean[4096];
		int[] queue = new int[4096];
		int head = 0;
		int tail = 0;
		int startIndex = ex | ez << 4 | ey << 8;
		queue[tail++] = startIndex;
		done[startIndex] = true;
		int faces = 0;
		try {
			while (head < tail) {
				int index = queue[head++];
				int x = index & 15;
				int z = index >> 4 & 15;
				int y = index >> 8 & 15;
				if (index != startIndex && section.getBlockState(x, y, z).isSolidRender()) continue;
				if (y == 0) faces |= 1;
				if (y == 15) faces |= 2;
				if (z == 0) faces |= 4;
				if (z == 15) faces |= 8;
				if (x == 0) faces |= 16;
				if (x == 15) faces |= 32;
				if (y > 0 && !done[index - 256]) { done[index - 256] = true; queue[tail++] = index - 256; }
				if (y < 15 && !done[index + 256]) { done[index + 256] = true; queue[tail++] = index + 256; }
				if (z > 0 && !done[index - 16]) { done[index - 16] = true; queue[tail++] = index - 16; }
				if (z < 15 && !done[index + 16]) { done[index + 16] = true; queue[tail++] = index + 16; }
				if (x > 0 && !done[index - 1]) { done[index - 1] = true; queue[tail++] = index - 1; }
				if (x < 15 && !done[index + 1]) { done[index + 1] = true; queue[tail++] = index + 1; }
			}
		} catch (RuntimeException e) {
			return 63;
		}
		return faces;
	}

	private boolean deep() {
		int eyeY = eyeSectionY - minSectionY;
		if (eyeY < 0 || eyeY >= height) { deepReason = "outside the world"; return false; }
		double need = PandaOcclusionConfig.config.deepSurfaceDistance;
		int reach = (int) Math.ceil(need);
		int eyeBlockX = Mth.floor(eye.x);
		int eyeBlockZ = Mth.floor(eye.z);
		for (int oz = -reach; oz <= reach; oz++) {
			for (int ox = -reach; ox <= reach; ox++) {
				int blockX = eyeBlockX + ox;
				int blockZ = eyeBlockZ + oz;
				int dx = SectionPos.blockToSectionCoord(blockX) - originX;
				int dz = SectionPos.blockToSectionCoord(blockZ) - originZ;
				if (dx < 0 || dz < 0 || dx >= width || dz >= width) continue;
				LevelChunk chunk = columns[dz * width + dx];
				if (chunk == null) continue;
				int surface = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, blockX & 15, blockZ & 15) + 1;
				double horizontal = Math.hypot(Math.max(0, Math.abs(blockX + 0.5 - eye.x) - 0.5), Math.max(0, Math.abs(blockZ + 0.5 - eye.z) - 0.5));
				double vertical = Math.max(0, surface - eye.y);
				if (horizontal * horizontal + vertical * vertical < need * need) {
					deepReason = "surface within " + reach + " blocks";
					return false;
				}
			}
		}
		int steps = PandaOcclusionConfig.config.deepPathSections;
		int sectionSteps = (int) Math.ceil(steps * Math.sqrt(3)) + 1;
		int layer = width * width;
		it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap entered = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
		it.unimi.dsi.fastutil.ints.IntArrayList frontier = new it.unimi.dsi.fastutil.ints.IntArrayList();
		int start = eyeY * layer + radius * width + radius;
		if (columns[radius * width + radius] == null) {
			deepReason = "eye chunk not loaded";
			return false;
		}
		int eyeFaces = eyePocketFaces(columns[radius * width + radius].getSection(eyeY));
		if (eyeFaces == 0) return true;
		entered.put(start, FREE);
		frontier.add(start);
		for (int step = 0; step < sectionSteps && !frontier.isEmpty(); step++) {
			it.unimi.dsi.fastutil.ints.IntArrayList next = new it.unimi.dsi.fastutil.ints.IntArrayList();
			for (int i = 0; i < frontier.size(); i++) {
				int c = frontier.getInt(i);
				int column = c % layer;
				int y = c / layer;
				int dx = column % width;
				int dz = column / width;
				long vis = SectionVis.get(columns[column].getSection(y));
				int srcs = entered.get(c);
				for (int dir = 0; dir < 6; dir++) {
					if (c == start && (eyeFaces >> dir & 1) == 0) continue;
					if ((srcs & FREE) == 0) {
						boolean through = false;
						for (int f = 0; f < 6; f++) {
							if ((srcs >> f & 1) != 0 && SectionVis.connects(vis, f ^ 1, dir)) {
								through = true;
								break;
							}
						}
						if (!through) continue;
					}
					int n;
					int nColumn = column;
					switch (dir) {
						case 0 -> { if (y == 0) continue; n = c - layer; }
						case 1 -> { if (y == height - 1) { deepReason = "cave path leaves the grid"; return false; } n = c + layer; }
						case 2 -> { if (dz == 0) { deepReason = "cave path leaves the grid"; return false; } n = c - width; nColumn = column - width; }
						case 3 -> { if (dz == width - 1) { deepReason = "cave path leaves the grid"; return false; } n = c + width; nColumn = column + width; }
						case 4 -> { if (dx == 0) { deepReason = "cave path leaves the grid"; return false; } n = c - 1; nColumn = column - 1; }
						default -> { if (dx == width - 1) { deepReason = "cave path leaves the grid"; return false; } n = c + 1; nColumn = column + 1; }
					}
					if (columns[nColumn] == null) continue;
					if (SectionPos.sectionToBlockCoord(minSectionY + n / layer) + 15 >= lowestSurface(nColumn)) {
						String path = blockPathToSky(steps * 16);
						if (path == null) return true;
						deepReason = path;
						return false;
					}
					if (entered.containsKey(n)) {
						if ((entered.get(n) & FREE) == 0) entered.put(n, entered.get(n) | 1 << dir);
					} else {
						entered.put(n, 1 << dir);
						next.add(n);
					}
				}
			}
			frontier = next;
		}
		// section changed under us (walks run off the server thread)
		return true;
	}

	private static final int SKY_SEARCH_BLOCKS = 400_000;

	private int skySearched;
	private long skySearchNanos;

	private String blockPathToSky(int maxDistance) {
		long startNanos = System.nanoTime();
		try {
			return searchSky(maxDistance);
		} finally {
			skySearchNanos = System.nanoTime() - startNanos;
		}
	}

	private long[] skyVisited = new long[0];
	private int[] skyStack = new int[0];
	private int[] skyHeight = new int[0];

	private String searchSky(int r) {
		int size = 2 * r + 1;
		int cells = size * size * size;
		if (skyVisited.length < (cells + 63) >> 6) skyVisited = new long[(cells + 63) >> 6];
		else Arrays.fill(skyVisited, 0, (cells + 63) >> 6, 0L);
		if (skyHeight.length < size * size) skyHeight = new int[size * size];
		Arrays.fill(skyHeight, 0, size * size, Integer.MIN_VALUE);
		if (skyStack.length < 4096) skyStack = new int[4096];
		int eyeX = Mth.floor(eye.x), eyeY = Mth.floor(eye.y), eyeZ = Mth.floor(eye.z);
		int baseX = eyeX - r, baseY = eyeY - r, baseZ = eyeZ - r;
		int minY = SectionPos.sectionToBlockCoord(minSectionY);
		int maxY = minY + height * 16 - 1;
		long r2 = (long) r * r;
		// index: y + size * (z + size * x)
		int start = r + size * (r + size * r);
		skyVisited[start >> 6] |= 1L << (start & 63);
		int top = 0;
		skyStack[top++] = start;
		int open = 0;
		try {
			while (top > 0) {
				int index = skyStack[--top];
				int ly = index % size;
				int lz = index / size % size;
				int lx = index / (size * size);
				int x = baseX + lx, y = baseY + ly, z = baseZ + lz;
				if (index != start && opaqueAt(x, y, z)) continue;
				skySearched = ++open;
				if (open > SKY_SEARCH_BLOCKS) return "cave too big to search";
				int column = lx * size + lz;
				int surface = skyHeight[column];
				if (surface == Integer.MIN_VALUE) {
					int dx = SectionPos.blockToSectionCoord(x) - originX;
					int dz = SectionPos.blockToSectionCoord(z) - originZ;
					if (dx < 0 || dz < 0 || dx >= width || dz >= width) return "cave path leaves the grid";
					LevelChunk chunk = columns[dz * width + dx];
					surface = chunk == null ? Integer.MAX_VALUE : chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
					skyHeight[column] = surface;
				}
				if (y > surface) {
					return "cave path to the sky " + Math.round(Math.sqrt((double) (x - eyeX) * (x - eyeX) + (y - eyeY) * (y - eyeY) + (z - eyeZ) * (z - eyeZ))) + " blocks away";
				}
				if (top + 6 > skyStack.length) skyStack = Arrays.copyOf(skyStack, skyStack.length * 2);
				for (int dir = 0; dir < 6; dir++) {
					int nx = lx, ny = ly, nz = lz;
					switch (dir) {
						case 0 -> ny--;
						case 1 -> ny++;
						case 2 -> nz--;
						case 3 -> nz++;
						case 4 -> nx--;
						default -> nx++;
					}
					int wy = baseY + ny;
					if (wy < minY || wy > maxY) continue;
					long ddx = nx - r, ddy = ny - r, ddz = nz - r;
					if (ddx * ddx + ddy * ddy + ddz * ddz > r2) continue;
					int n = ny + size * (nz + size * nx);
					if ((skyVisited[n >> 6] >>> (n & 63) & 1L) != 0) continue;
					skyVisited[n >> 6] |= 1L << (n & 63);
					skyStack[top++] = n;
				}
			}
		} catch (RuntimeException e) {
			return "cave path unknown (blocks changed)";
		}
		return null;
	}

	private void findPockets(View view) {
		long start = System.nanoTime();
		int layer = width * width;
		long[] checked = new long[layer];
		it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<long[]> pockets = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
		boolean skipSurface = PandaOcclusionConfig.config.alwaysSendSurface && !view.deep;
		Vec3[] targets = new Vec3[sightPoints.length + cameraPoints.length];
		System.arraycopy(sightPoints, 0, targets, 0, sightPoints.length);
		System.arraycopy(cameraPoints, 0, targets, sightPoints.length, cameraPoints.length);
		it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<SectionPockets> found = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
		for (int column = 0; column < layer && PandaOcclusionConfig.config.hideSealedPockets; column++) {
			if (columns[column] == null) continue;
			long mask = view.columnMask[column];
			if (skipSurface && view.surfaceMask != null) mask &= ~view.surfaceMask[column];
			while (mask != 0) {
				int y = Long.numberOfTrailingZeros(mask);
				mask &= mask - 1;
				if (y > topFilled[column]) continue;
				checked[column] |= 1L << y;
				LevelChunkSection section = columns[column].getSection(y);
				int components = SectionVis.components(SectionVis.get(section));
				if (components == 0) continue;
				int c = y * layer + column;
				int minX = SectionPos.sectionToBlockCoord(originX + column % width);
				int minY = SectionPos.sectionToBlockCoord(minSectionY + y);
				int minZ = SectionPos.sectionToBlockCoord(originZ + column / width);
				boolean holdsTarget = false;
				for (Vec3 target : targets) {
					if (Mth.floor(target.x) >> 4 == minX >> 4 && Mth.floor(target.y) >> 4 == minY >> 4 && Mth.floor(target.z) >> 4 == minZ >> 4) {
						holdsTarget = true;
						break;
					}
				}
				if (components == 1 && !holdsTarget && rayEpoch[c] == epoch && rayState[c] == RAY_VISIBLE) continue;
				Occlusion.STATS.pocketSections.increment();
				SectionPockets result;
				try {
					result = sectionPockets(section, column, y, minX, minY, minZ, targets);
				} catch (RuntimeException e) {
					continue;
				}
				if (result != null) found.put(c, result);
			}
		}
		for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<SectionPockets> entry : found.int2ObjectEntrySet()) {
			int c = entry.getIntKey();
			SectionPockets result = entry.getValue();
			long[] hidden = result.enclosed;
			for (int g = 0; g < result.groupFaces.size(); g++) {
				long[] faces = result.groupFaces.get(g);
				if (seenAcross(view, found, checked, skipSurface, c, faces)) continue;
				long[] cells = result.groupCells.get(g);
				for (int k = 0; k < 64; k++) hidden[k] |= cells[k];
			}
			boolean any = false;
			for (long word : hidden) any |= word != 0;
			if (any) pockets.put(c, hidden);
		}
		if (skipSurface && view.surfaceMask != null) {
			for (int column = 0; column < layer; column++) checked[column] |= view.surfaceMask[column];
		}
		if (PandaOcclusionConfig.config.hideUnseenOres) {
			long oreStart = System.nanoTime();
			findUnseenOres(view, checked, pockets, targets);
			Occlusion.STATS.oreNanos.add(System.nanoTime() - oreStart);
		}
		view.pocketChecked = checked;
		view.pockets = pockets;
		Occlusion.STATS.pocketNanos.add(System.nanoTime() - start);
	}

	private void findUnseenOres(View view, long[] checked, it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<long[]> pockets, Vec3[] targets) {
		int layer = width * width;
		double max = PandaOcclusionConfig.config.unseenOreDistance;
		boolean surfaceReal = PandaOcclusionConfig.config.alwaysSendSurface && !view.deep && view.surfaceMask != null;
		for (int column = 0; column < layer; column++) {
			if (columns[column] == null) continue;
			long mask = view.columnMask[column] | (surfaceReal ? view.surfaceMask[column] : 0L);
			int top = topFilled[column];
			mask &= top >= 63 ? -1L : top < 0 ? 0L : (1L << (top + 1)) - 1;
			int minX = SectionPos.sectionToBlockCoord(originX + column % width);
			int minZ = SectionPos.sectionToBlockCoord(originZ + column / width);
			for (; mask != 0; mask &= mask - 1) {
				int y = Long.numberOfTrailingZeros(mask);
				int c = y * layer + column;
				int minY = SectionPos.sectionToBlockCoord(minSectionY + y);
				long[] ores;
				try {
					ores = OreTrace.bits(columns[column].getSection(y));
				} catch (RuntimeException e) {
					continue;
				}
				checked[column] |= 1L << y;
				if (ores == null) continue;
				long[] hidden = pockets.get(c);
				double nx = Math.max(minX - eye.x, Math.max(0, eye.x - (minX + 16)));
				double ny = Math.max(minY - eye.y, Math.max(0, eye.y - (minY + 16)));
				double nz = Math.max(minZ - eye.z, Math.max(0, eye.z - (minZ + 16)));
				if (nx * nx + ny * ny + nz * nz > max * max) {
					if (hidden == null) hidden = new long[64];
					for (int word = 0; word < 64; word++) hidden[word] |= ores[word];
					pockets.put(c, hidden);
					Occlusion.STATS.oreFarSections.increment();
					continue;
				}
				boolean added = false;
				for (int word = 0; word < 64; word++) {
					for (long left = ores[word] & (hidden == null ? -1L : ~hidden[word]); left != 0; left &= left - 1) {
						int bit = word << 6 | Long.numberOfTrailingZeros(left);
						int x = minX + (bit & 15), z = minZ + (bit >> 4 & 15), by = minY + (bit >> 8 & 15);
						int open = 0;
						for (int face = 0; face < 6; face++) {
							if (!opaqueAt(x + FACE_X[face], by + FACE_Y[face], z + FACE_Z[face])) open |= 1 << face;
						}
						if (open == 0) continue;
						Occlusion.STATS.oreChecks.increment();
						double ex = x + 0.5 - eye.x, ey = by + 0.5 - eye.y, ez = z + 0.5 - eye.z;
						boolean seen = false;
						if (ex * ex + ey * ey + ez * ez <= max * max) {
							long rayStart = System.nanoTime();
							seen = oreInSight(x, by, z, open, targets);
							Occlusion.STATS.oreRayNanos.add(System.nanoTime() - rayStart);
						}
						if (seen) continue;
						if (hidden == null) hidden = new long[64];
						hidden[word] |= 1L << (bit & 63);
						added = true;
						Occlusion.STATS.oresHidden.increment();
					}
				}
				if (added) pockets.put(c, hidden);
			}
		}
	}

	// Direction order: down, up, north, south, west, east
	private static final int[] FACE_X = {0, 0, 0, 0, -1, 1};
	private static final int[] FACE_Y = {-1, 1, 0, 0, 0, 0};
	private static final int[] FACE_Z = {0, 0, -1, 1, 0, 0};
	private static final double[][] FACE_POINTS = {{0.5, 0.5}, {0.1, 0.1}, {0.9, 0.1}, {0.1, 0.9}, {0.9, 0.9}};

	private boolean oreInSight(int x, int y, int z, int open, Vec3[] targets) {
		double fine = PandaOcclusionConfig.config.fineSightDistance / 2;
		for (int t = 0; t < targets.length; t++) {
			Vec3 target = targets[t];
			boolean camera = t >= sightPoints.length;
			if (t > 0 && !camera && target.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > fine * fine) continue;
			int points = t > 0 && !camera ? 1 : FACE_POINTS.length;
			for (int face = 0; face < 6; face++) {
				if ((open >> face & 1) == 0) continue;
				int nx = x + FACE_X[face], ny = y + FACE_Y[face], nz = z + FACE_Z[face];
				if (FACE_X[face] != 0 && (FACE_X[face] > 0 ? target.x <= nx : target.x >= x)) continue;
				if (FACE_Y[face] != 0 && (FACE_Y[face] > 0 ? target.y <= ny : target.y >= y)) continue;
				if (FACE_Z[face] != 0 && (FACE_Z[face] > 0 ? target.z <= nz : target.z >= z)) continue;
				for (int p = 0; p < points; p++) {
					double u = FACE_POINTS[p][0], v = FACE_POINTS[p][1];
					double px = FACE_X[face] != 0 ? (FACE_X[face] > 0 ? nx + 0.01 : x - 0.01) : x + u;
					double py = FACE_Y[face] != 0 ? (FACE_Y[face] > 0 ? ny + 0.01 : y - 0.01) : y + (FACE_X[face] != 0 ? u : v);
					double pz = FACE_Z[face] != 0 ? (FACE_Z[face] > 0 ? nz + 0.01 : z - 0.01) : z + v;
					Occlusion.STATS.oreRays.increment();
					if (clearLine(nx, ny, nz, px, py, pz, target)) return true;
				}
			}
		}
		return false;
	}

	private static final class SectionPockets {
		final long[] enclosed = new long[64];
		final java.util.List<long[]> groupFaces = new java.util.ArrayList<>();
		final java.util.List<long[]> groupCells = new java.util.ArrayList<>();
		long[] seenFaces;
	}

	private boolean seenAcross(View view, it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<SectionPockets> found, long[] checked, boolean skipSurface, int c, long[] faces) {
		int layer = width * width;
		int column = c % layer;
		int y = c / layer;
		int dx = column % width;
		int dz = column / width;
		for (int face = 0; face < 6; face++) {
			if ((faces[face * 4] | faces[face * 4 + 1] | faces[face * 4 + 2] | faces[face * 4 + 3]) == 0) continue;
			int ny = y;
			int nColumn = column;
			switch (face) {
				case 0 -> ny = y - 1;
				case 1 -> ny = y + 1;
				case 2 -> nColumn = dz > 0 ? column - width : -1;
				case 3 -> nColumn = dz < width - 1 ? column + width : -1;
				case 4 -> nColumn = dx > 0 ? column - 1 : -1;
				default -> nColumn = dx < width - 1 ? column + 1 : -1;
			}
			if (nColumn < 0 || ny < 0 || ny >= height || columns[nColumn] == null) continue;
			long sent = view.columnMask[nColumn] | (skipSurface && view.surfaceMask != null ? view.surfaceMask[nColumn] : 0L);
			if ((sent >> ny & 1L) == 0) continue;
			int n = ny * layer + nColumn;
			SectionPockets neighbour = found.get(n);
			long[] across = neighbour != null ? neighbour.seenFaces : SectionVis.openFaces(columns[nColumn].getSection(ny));
			int opposite = face ^ 1;
			for (int k = 0; k < 4; k++) {
				if ((faces[face * 4 + k] & across[opposite * 4 + k]) != 0) return true;
			}
		}
		return false;
	}

	private SectionPockets sectionPockets(LevelChunkSection section, int column, int y, int minX, int minY, int minZ, Vec3[] targets) {
		long labelStart = System.nanoTime();
		SectionGroups groups = SectionGroups.of(section);
		if (groups == null) return null;
		int count = groups.cells.length;
		boolean[] visible = new boolean[count];
		long[] enclosed = groups.enclosed.clone();
		for (Vec3 target : targets) {
			int tx = Mth.floor(target.x), ty = Mth.floor(target.y), tz = Mth.floor(target.z);
			if (tx >> 4 != minX >> 4 || ty >> 4 != minY >> 4 || tz >> 4 != minZ >> 4) continue;
			int cell = (tx & 15) | (tz & 15) << 4 | (ty & 15) << 8;
			int x = tx & 15, z = tz & 15, yy = ty & 15;
			int[] around = (groups.opaque[cell >> 6] >>> (cell & 63) & 1L) == 0 ? new int[]{cell}
				: new int[]{yy > 0 ? cell - 256 : -1, yy < 15 ? cell + 256 : -1, z > 0 ? cell - 16 : -1, z < 15 ? cell + 16 : -1, x > 0 ? cell - 1 : -1, x < 15 ? cell + 1 : -1};
			for (int n : around) {
				if (n < 0 || (groups.opaque[n >> 6] >>> (n & 63) & 1L) != 0) continue;
				if ((enclosed[n >> 6] >>> (n & 63) & 1L) != 0) {
					long[] seen = SectionGroups.flood(enclosed, n);
					for (int k = 0; k < 64; k++) enclosed[k] &= ~seen[k];
					continue;
				}
				for (int g = 0; g < count; g++) {
					if ((groups.cells[g][n >> 6] >>> (n & 63) & 1L) != 0) visible[g] = true;
				}
			}
		}
		long raysStart = System.nanoTime();
		Occlusion.STATS.pocketLabelNanos.add(raysStart - labelStart);
		boolean any = false;
		for (long word : enclosed) any |= word != 0;
		for (int g = 0; g < count; g++) {
			if (!visible[g]) visible[g] = groupInSight(column, y, minX, minY, minZ, groups.faces[g], targets);
			if (!visible[g]) any = true;
		}
		Occlusion.STATS.pocketRayNanos.add(System.nanoTime() - raysStart);
		if (!any) return null;
		SectionPockets result = new SectionPockets();
		System.arraycopy(enclosed, 0, result.enclosed, 0, 64);
		long[] seenFaces = SectionVis.openFaces(section).clone();
		for (int g = 0; g < count; g++) {
			if (visible[g]) continue;
			result.groupFaces.add(groups.faces[g]);
			result.groupCells.add(groups.cells[g]);
			for (int k = 0; k < 24; k++) seenFaces[k] &= ~groups.faces[g][k];
		}
		result.seenFaces = seenFaces;
		return result;
	}

	private boolean neighbourOutOfSight(int column, int y, int face) {
		int dx = column % width;
		int dz = column / width;
		int ny = y;
		int nColumn = column;
		switch (face) {
			case 0 -> ny = y - 1;
			case 1 -> ny = y + 1;
			case 2 -> nColumn = dz > 0 ? column - width : -1;
			case 3 -> nColumn = dz < width - 1 ? column + width : -1;
			case 4 -> nColumn = dx > 0 ? column - 1 : -1;
			default -> nColumn = dx < width - 1 ? column + 1 : -1;
		}
		if (nColumn < 0 || ny < 0 || ny >= height) return false;
		if (Math.abs(originX + nColumn % width - eyeSectionX) <= 1 && Math.abs(minSectionY + ny - eyeSectionY) <= 1
			&& Math.abs(originZ + nColumn / width - eyeSectionZ) <= 1) return false;
		int n = ny * width * width + nColumn;
		return rayEpoch[n] == epoch && rayState[n] == RAY_HIDDEN;
	}

	private boolean groupInSight(int column, int y, int minX, int minY, int minZ, long[] groupFaces, Vec3[] targets) {
		long[] faces = SectionVis.openFaces(columns[column].getSection(y));
		double fine = PandaOcclusionConfig.config.fineSightDistance;
		for (int t = 0; t < targets.length; t++) {
			Vec3 target = targets[t];
			boolean camera = t >= sightPoints.length;
			int tx = Mth.floor(target.x), ty = Mth.floor(target.y), tz = Mth.floor(target.z);
			double nearX = Mth.clamp(target.x, minX, minX + 16), nearY = Mth.clamp(target.y, minY, minY + 16), nearZ = Mth.clamp(target.z, minZ, minZ + 16);
			double distanceSqr = target.distanceToSqr(nearX, nearY, nearZ);
			boolean coarse = distanceSqr > fine * fine;
			boolean medium = !coarse && distanceSqr > fine * fine / 4;
			if (t > 0 && !camera && (coarse || medium)) continue;
			for (int axis = 0; axis < 3; axis++) {
				int coord = axis == 0 ? tx : axis == 1 ? ty : tz;
				int min = axis == 0 ? minX : axis == 1 ? minY : minZ;
				int fixed;
				if (coord < min) fixed = 0;
				else if (coord > min + 15) fixed = 15;
				else continue;
				int face = axis == 0 ? (fixed == 0 ? 4 : 5) : axis == 1 ? (fixed == 0 ? 0 : 1) : (fixed == 0 ? 2 : 3);
				if (neighbourOutOfSight(column, y, face)) continue;
				long[] faceMask = faceMask(faces, face, column, y);
				int portals = 0;
				for (int k = 0; k < 4; k++) {
					faceMask[k] &= groupFaces[face * 4 + k];
					portals += Long.bitCount(faceMask[k]);
				}
				if (portals == 0) continue;
				boolean narrow = portals <= NARROW_PORTAL;
				int sampling = narrow ? 0 : coarse ? 2 : camera ? 1 : t > 0 ? 2 : medium ? 1 : 0;
				for (int pu = 0; pu < 16; pu += 4) {
					for (int pv = 0; pv < 16; pv += 4) {
						int first = -1;
						int last = -1;
						int cellsDone = 0;
						for (int u = pu; u < pu + 4; u++) {
							for (int v = pv; v < pv + 4; v++) {
								int bit = u * 16 + v;
								if ((faceMask[bit >> 6] >>> (bit & 63) & 1L) == 0) continue;
								if (first < 0) first = bit;
								last = bit;
								if (sampling == 2) continue;
								if (sampling == 1) {
									int cell = 1 << (((u - pu) >> 1) * 2 + ((v - pv) >> 1));
									if ((cellsDone & cell) != 0) continue;
									cellsDone |= cell;
								}
								(camera ? Occlusion.STATS.pocketCameraRays : t > 0 ? Occlusion.STATS.pocketSightRays : Occlusion.STATS.pocketRays).increment();
								if (clearLine(minX + (axis == 0 ? fixed : u), minY + (axis == 1 ? fixed : axis == 0 ? u : v), minZ + (axis == 2 ? fixed : v), target)) return true;
							}
						}
						if (sampling != 2 || first < 0) continue;
						for (int bit : first == last ? new int[]{first} : new int[]{first, last}) {
							int u = bit >> 4;
							int v = bit & 15;
							(camera ? Occlusion.STATS.pocketCameraRays : t > 0 ? Occlusion.STATS.pocketSightRays : Occlusion.STATS.pocketRays).increment();
							if (clearLine(minX + (axis == 0 ? fixed : u), minY + (axis == 1 ? fixed : axis == 0 ? u : v), minZ + (axis == 2 ? fixed : v), target)) return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static int openFaces(LevelChunkSection section) {
		return SectionVis.nearFaces(SectionVis.get(section));
	}

	private static boolean sealedFrom(LevelChunkSection section, int srcs) {
		for (int dir = 0; dir < 6; dir++) {
			if ((srcs >> dir & 1) != 0 && !SectionVis.sealed(section, dir ^ 1)) return false;
		}
		return true;
	}

	private void touch(long[] columnMask, int column, int y, int face) {
		if (columns[column] != null && SectionVis.sealed(columns[column].getSection(y), face)) shellMask[column] |= 1L << y;
		else columnMask[column] |= 1L << y;
	}

	private boolean inSight(int c) {
		if (rayEpoch[c] == epoch) return rayState[c] != RAY_HIDDEN;
		rayEpoch[c] = epoch;
		rayState[c] = RAY_PENDING;
		boolean visible = computeInSight(c);
		rayState[c] = visible ? RAY_VISIBLE : RAY_HIDDEN;
		return visible;
	}

	private boolean computeInSight(int c) {
		int layer = width * width;
		int column = c % layer;
		int y = c / layer;
		int sectionX = originX + column % width;
		int sectionY = minSectionY + y;
		int sectionZ = originZ + column / width;
		if (sectionX == eyeSectionX && sectionY == eyeSectionY && sectionZ == eyeSectionZ) return true;
		int minX = SectionPos.sectionToBlockCoord(sectionX);
		int minY = SectionPos.sectionToBlockCoord(sectionY);
		int minZ = SectionPos.sectionToBlockCoord(sectionZ);
		Occlusion.STATS.caveRayChecks.increment();
		boolean surfaceBand = minY + 15 >= lowestSurface(column);
		if (surfaceBand) Occlusion.STATS.surfaceRayChecks.increment();
		long raysBefore = Occlusion.STATS.caveRays.sum();
		boolean result = computeInSightRays(c, column, y, minX, minY, minZ);
		if (surfaceBand) Occlusion.STATS.surfaceRays.add(Occlusion.STATS.caveRays.sum() - raysBefore);
		if (!result) Occlusion.STATS.hiddenChecks.increment();
		return result;
	}

	private boolean computeInSightRays(int c, int column, int y, int minX, int minY, int minZ) {
		long[] faces = SectionVis.openFaces(columns[column].getSection(y));
		double nearX = Mth.clamp(eye.x, minX, minX + 16), nearY = Mth.clamp(eye.y, minY, minY + 16), nearZ = Mth.clamp(eye.z, minZ, minZ + 16);
		double fine = PandaOcclusionConfig.config.fineSightDistance;
		double distanceSqr = eye.distanceToSqr(nearX, nearY, nearZ);
		boolean coarse = distanceSqr > fine * fine;
		boolean medium = !coarse && distanceSqr > fine * fine / 4;
		int eyeX = Mth.floor(eye.x);
		int eyeY = Mth.floor(eye.y);
		int eyeZ = Mth.floor(eye.z);
		try {
			for (int axis = 0; axis < 3; axis++) {
				int eyeCoord = axis == 0 ? eyeX : axis == 1 ? eyeY : eyeZ;
				int min = axis == 0 ? minX : axis == 1 ? minY : minZ;
				int fixed;
				if (eyeCoord < min) fixed = 0;
				else if (eyeCoord > min + 15) fixed = 15;
				else continue;
				int face = axis == 0 ? (fixed == 0 ? 4 : 5) : axis == 1 ? (fixed == 0 ? 0 : 1) : (fixed == 0 ? 2 : 3);
				long[] faceMask = faceMask(faces, face, column, y);
				int portals = Long.bitCount(faceMask[0]) + Long.bitCount(faceMask[1]) + Long.bitCount(faceMask[2]) + Long.bitCount(faceMask[3]);
				if (portals == 0) continue;
				boolean narrow = portals <= NARROW_PORTAL;
				boolean faceCoarse = coarse && !narrow;
				boolean faceMedium = medium && !narrow;
				for (int pu = 0; pu < 16; pu += 4) {
					for (int pv = 0; pv < 16; pv += 4) {
						int first = -1;
						int last = -1;
						int cellsDone = 0;
						for (int u = pu; u < pu + 4; u++) {
							for (int v = pv; v < pv + 4; v++) {
								int bit = u * 16 + v;
								if ((faceMask[bit >> 6] >>> (bit & 63) & 1L) == 0) continue;
								if (first < 0) first = u * 16 + v;
								last = u * 16 + v;
								if (faceCoarse) continue;
								if (faceMedium) {
									int cell = 1 << (((u - pu) >> 1) * 2 + ((v - pv) >> 1));
									if ((cellsDone & cell) != 0) continue;
									cellsDone |= cell;
								}
								int x = axis == 0 ? fixed : u;
								int yy = axis == 1 ? fixed : axis == 0 ? u : v;
								int z = axis == 2 ? fixed : v;
								Occlusion.STATS.caveRays.increment();
								if (clearLine(minX + x, minY + yy, minZ + z, eye)) return true;
							}
						}
						int points = coarse || medium ? 1 : sightPoints.length;
						if (first < 0 || points == 1 && !faceCoarse) continue;
						for (int cell : first == last ? new int[]{first} : new int[]{first, last}) {
							int u = cell >> 4;
							int v = cell & 15;
							int x = axis == 0 ? fixed : u;
							int yy = axis == 1 ? fixed : axis == 0 ? u : v;
							int z = axis == 2 ? fixed : v;
							for (int p = faceCoarse ? 0 : 1; p < points; p++) {
								Occlusion.STATS.caveRays.increment();
								if (clearLine(minX + x, minY + yy, minZ + z, sightPoints[p])) return true;
							}
						}
					}
				}
			}
			for (Vec3 camera : cameraPoints) {
				if (cameraSees(column, y, minX, minY, minZ, camera)) return true;
			}
		} catch (RuntimeException e) {
			return true;
		}
		return false;
	}

	private boolean cameraSees(int column, int y, int minX, int minY, int minZ, Vec3 camera) {
		long[] faces = SectionVis.openFaces(columns[column].getSection(y));
		int camX = Mth.floor(camera.x), camY = Mth.floor(camera.y), camZ = Mth.floor(camera.z);
		if (camX >> 4 == minX >> 4 && camY >> 4 == minY >> 4 && camZ >> 4 == minZ >> 4) return true;
		for (int axis = 0; axis < 3; axis++) {
			int camCoord = axis == 0 ? camX : axis == 1 ? camY : camZ;
			int min = axis == 0 ? minX : axis == 1 ? minY : minZ;
			int fixed;
			if (camCoord < min) fixed = 0;
			else if (camCoord > min + 15) fixed = 15;
			else continue;
			int face = axis == 0 ? (fixed == 0 ? 4 : 5) : axis == 1 ? (fixed == 0 ? 0 : 1) : (fixed == 0 ? 2 : 3);
			long[] faceMask = faceMask(faces, face, column, y);
			int portals = Long.bitCount(faceMask[0]) + Long.bitCount(faceMask[1]) + Long.bitCount(faceMask[2]) + Long.bitCount(faceMask[3]);
			if (portals == 0) continue;
			boolean narrow = portals <= NARROW_PORTAL;
			for (int cu = 0; cu < 16; cu += 2) {
				for (int cv = 0; cv < 16; cv += 2) {
					for (int u = cu; u < cu + 2; u++) {
						boolean done = false;
						for (int v = cv; v < cv + 2; v++) {
							int bit = u * 16 + v;
							if ((faceMask[bit >> 6] >>> (bit & 63) & 1L) == 0) continue;
							int x = axis == 0 ? fixed : u;
							int yy = axis == 1 ? fixed : axis == 0 ? u : v;
							int z = axis == 2 ? fixed : v;
							Occlusion.STATS.caveRays.increment();
							if (clearLine(minX + x, minY + yy, minZ + z, camera)) return true;
							if (!narrow) {
								done = true;
								break;
							}
						}
						if (done) break;
					}
				}
			}
		}
		return false;
	}

	private static final int NARROW_PORTAL = 32;

	private long[] faceMask(long[] faces, int face, int column, int y) {
		int dx = column % width;
		int dz = column / width;
		int ny = y;
		int nColumn = column;
		switch (face) {
			case 0 -> ny = y - 1;
			case 1 -> ny = y + 1;
			case 2 -> nColumn = dz > 0 ? column - width : -1;
			case 3 -> nColumn = dz < width - 1 ? column + width : -1;
			case 4 -> nColumn = dx > 0 ? column - 1 : -1;
			default -> nColumn = dx < width - 1 ? column + 1 : -1;
		}
		long[] faceMaskOut = new long[4];
		long[] neighbour = null;
		if (nColumn >= 0 && ny >= 0 && ny < height && columns[nColumn] != null) {
			LevelChunkSection section = columns[nColumn].getSection(ny);
			if (!section.hasOnlyAir()) neighbour = SectionVis.openFaces(section);
		}
		for (int k = 0; k < 4; k++) {
			long own = faces[face * 4 + k];
			faceMaskOut[k] = neighbour == null ? own : own & neighbour[(face ^ 1) * 4 + k];
		}
		return faceMaskOut;
	}

	private static final int[][] SIGHT_DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}, {0, 1, 0}, {0, -1, 0}};

	double marginOverride = -1;
	double marginVerticalOverride = -1;
	Vec3 heading;

	private Vec3[] sightPoints(Vec3 eye) {
		double h = marginOverride >= 0 ? marginOverride : PandaOcclusionConfig.config.sightMargin;
		double v = marginVerticalOverride >= 0 ? marginVerticalOverride : PandaOcclusionConfig.config.sightMarginVertical;
		java.util.List<Vec3> points = new java.util.ArrayList<>();
		points.add(eye);
		int ex = Mth.floor(eye.x), ey = Mth.floor(eye.y), ez = Mth.floor(eye.z);
		double reach = Math.max(h, v);
		if (reach > 0) {
			int r = (int) Math.ceil(reach);
			int size = 2 * r + 1;
			boolean[] seenBlock = new boolean[size * size * size];
			int[] queue = new int[size * size * size];
			int qHead = 0, qTail = 0;
			int startIndex = (r * size + r) * size + r;
			seenBlock[startIndex] = true;
			queue[qTail++] = startIndex;
			int[] best = new int[SIGHT_DIRECTIONS.length];
			double[] bestScore = new double[SIGHT_DIRECTIONS.length];
			Arrays.fill(best, -1);
			while (qHead < qTail) {
				int index = queue[qHead++];
				int lx = index / (size * size) - r, ly = index / size % size - r, lz = index % size - r;
				if (index != startIndex) {
					if (opaqueAt(ex + lx, ey + ly, ez + lz)) continue;
					double horizontal = Math.sqrt(lx * lx + lz * lz);
					if (horizontal > h + 0.01 && lx * lx + lz * lz > 0 || Math.abs(ly) > Math.max(v, 1) + 0.01) continue;
					for (int d = 0; d < SIGHT_DIRECTIONS.length; d++) {
						int[] dir = SIGHT_DIRECTIONS[d];
						if (heading != null && dir[0] * heading.x + dir[1] * heading.y + dir[2] * heading.z < -0.01) continue;
						double score = (lx * dir[0] + ly * dir[1] + lz * dir[2]) / Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
						if (score > bestScore[d] + 0.01 && clearLine(ex + lx, ey + ly, ez + lz, eye)) {
							bestScore[d] = score;
							best[d] = index;
						}
					}
				}
				for (int n = 0; n < 6; n++) {
					int nx = lx + (n == 0 ? 1 : n == 1 ? -1 : 0), ny = ly + (n == 2 ? 1 : n == 3 ? -1 : 0), nz = lz + (n == 4 ? 1 : n == 5 ? -1 : 0);
					if (Math.abs(nx) > r || Math.abs(ny) > r || Math.abs(nz) > r) continue;
					int ni = ((nx + r) * size + ny + r) * size + nz + r;
					if (seenBlock[ni]) continue;
					seenBlock[ni] = true;
					queue[qTail++] = ni;
				}
			}
			it.unimi.dsi.fastutil.ints.IntOpenHashSet added = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
			for (int d = 0; d < SIGHT_DIRECTIONS.length; d++) {
				if (best[d] < 0 || bestScore[d] < 1 || !added.add(best[d])) continue;
				int lx = best[d] / (size * size) - r, ly = best[d] / size % size - r, lz = best[d] % size - r;
				points.add(new Vec3(ex + lx + 0.5, ey + ly + (eye.y - ey), ez + lz + 0.5));
			}
		}
		double speed = motion.length();
		if (speed > 0.05) {
			Vec3 direction = motion.scale(1 / speed);
			for (int d = 1; d <= PandaOcclusionConfig.config.sightAheadBlocks; d++) {
				Vec3 point = eye.add(direction.scale(d));
				int bx = Mth.floor(point.x), by = Mth.floor(point.y), bz = Mth.floor(point.z);
				try {
					if (opaqueAt(bx, by, bz) || !clearLine(bx, by, bz, eye)) continue;
				} catch (RuntimeException e) {
					continue;
				}
				points.add(point);
			}
		}
		return points.toArray(new Vec3[0]);
	}

	private int lowestSurface(int column) {
		if (lowestSurfaceEpoch[column] == epoch) return lowestSurface[column];
		int lowest = LowestSurfaceHolder.get(columns[column]);
		lowestSurface[column] = lowest;
		lowestSurfaceEpoch[column] = epoch;
		return lowest;
	}

	private static final int NEAR_EYE_STEPS = 12;

	private boolean blockedNearEye(Vec3 eyePoint, double tx, double ty, double tz) {
		double dx = tx - eyePoint.x;
		double dy = ty - eyePoint.y;
		double dz = tz - eyePoint.z;
		int x = Mth.floor(eyePoint.x);
		int y = Mth.floor(eyePoint.y);
		int z = Mth.floor(eyePoint.z);
		int endX = Mth.floor(tx);
		int endY = Mth.floor(ty);
		int endZ = Mth.floor(tz);
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - eyePoint.x : eyePoint.x - x) * deltaX;
		double maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - eyePoint.y : eyePoint.y - y) * deltaY;
		double maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - eyePoint.z : eyePoint.z - z) * deltaZ;
		try {
			for (int steps = 0; steps < NEAR_EYE_STEPS; steps++) {
				if (maxX < maxY && maxX < maxZ) {
					x += stepX;
					maxX += deltaX;
				} else if (maxY < maxZ) {
					y += stepY;
					maxY += deltaY;
				} else {
					z += stepZ;
					maxZ += deltaZ;
				}
				if (x == endX && y == endY && z == endZ) return false;
				if (opaqueAt(x, y, z)) return true;
			}
		} catch (RuntimeException e) {
			return false;
		}
		return false;
	}

	private long[] sectionOpaque(int c) {
		if (opaqueEpoch[c] == epoch) return opaqueCache[c];
		int layer = width * width;
		LevelChunk chunk = columns[c % layer];
		long[] bits;
		if (chunk == null) {
			bits = null;
		} else {
			LevelChunkSection section = chunk.getSection(c / layer);
			if (section.hasOnlyAir()) {
				bits = null;
			} else {
				bits = SectionVis.opaqueBits(section);
				if (bits == null) bits = UNKNOWN_OPAQUE;
				else if (bits == SectionVis.NO_OPAQUE) bits = null;
			}
		}
		opaqueCache[c] = bits;
		opaqueEpoch[c] = epoch;
		return bits;
	}

	private boolean opaqueAt(int x, int y, int z) {
		int cx = (x >> 4) - originX;
		int cz = (z >> 4) - originZ;
		int cy = (y >> 4) - minSectionY;
		if (cx < 0 || cz < 0 || cx >= width || cz >= width || cy < 0 || cy >= height) return false;
		long[] bits = sectionOpaque((cy * width + cz) * width + cx);
		if (bits == null) return false;
		if (bits == UNKNOWN_OPAQUE) {
			return columns[cz * width + cx].getSection(cy).getBlockState(x & 15, y & 15, z & 15).isSolidRender();
		}
		int index = x & 15 | (z & 15) << 4 | (y & 15) << 8;
		return (bits[index >> 6] >>> (index & 63) & 1L) != 0;
	}

	private boolean clearLine(int startX, int startY, int startZ, Vec3 target) {
		return clearLine(startX, startY, startZ, startX + 0.5, startY + 0.5, startZ + 0.5, target);
	}

	private boolean clearLine(int startX, int startY, int startZ, double ox, double oy, double oz, Vec3 target) {
		if (blockedNearEye(target, ox, oy, oz)) return false;
		double dx = target.x - ox;
		double dy = target.y - oy;
		double dz = target.z - oz;
		int x = startX;
		int y = startY;
		int z = startZ;
		int endX = Mth.floor(target.x);
		int endY = Mth.floor(target.y);
		int endZ = Mth.floor(target.z);
		if (x == endX && y == endY && z == endZ) return true;
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - ox : ox - x) * deltaX;
		double maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - oy : oy - y) * deltaY;
		double maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - oz : oz - z) * deltaZ;
		long[] cached = null;
		LevelChunkSection cachedSection = null;
		int cachedKeyX = Integer.MIN_VALUE;
		int cachedKeyY = 0;
		int cachedKeyZ = 0;
		int minBlockY = SectionPos.sectionToBlockCoord(minSectionY);
		int steps = 0;
		try {
		for (; steps < 2048; steps++) {
			if (maxX < maxY && maxX < maxZ) {
				x += stepX;
				maxX += deltaX;
			} else if (maxY < maxZ) {
				y += stepY;
				maxY += deltaY;
			} else {
				z += stepZ;
				maxZ += deltaZ;
			}
			if (x == endX && y == endY && z == endZ) return true;
			int sx = x >> 4;
			int sy = y >> 4;
			int sz = z >> 4;
			if (sx != cachedKeyX || sy != cachedKeyY || sz != cachedKeyZ) {
				cachedKeyX = sx;
				cachedKeyY = sy;
				cachedKeyZ = sz;
				int cx = sx - originX;
				int cz = sz - originZ;
				int cy = sy - minSectionY;
				if (rayPruning && PandaOcclusionConfig.config.rayPruning && cx >= 0 && cz >= 0 && cx < width && cz < width && cy >= 0 && cy < height
					&& columns[cz * width + cx] != null && cy <= topFilled[cz * width + cx]) {
					if (!inSight(cy * width * width + cz * width + cx)) return false;
				}
				boolean inGrid = cx >= 0 && cz >= 0 && cx < width && cz < width && cy >= 0 && cy < height;
				cached = inGrid ? sectionOpaque((cy * width + cz) * width + cx) : null;
				cachedSection = null;
				if (cached == UNKNOWN_OPAQUE) {
					cachedSection = columns[cz * width + cx].getSection(cy);
					cached = null;
				}
			}
			if (cached != null) {
				int index = x & 15 | (z & 15) << 4 | (y & 15) << 8;
				if ((cached[index >> 6] >>> (index & 63) & 1L) != 0) return false;
			} else if (cachedSection != null && !cachedSection.hasOnlyAir() && y >= minBlockY
				&& cachedSection.getBlockState(x & 15, y & 15, z & 15).isSolidRender()) {
				return false;
			}
		}
		return true;
		} finally {
			Occlusion.STATS.caveRaySteps.add(steps);
		}
	}

	private void push(int c, int dirs, int srcs, int from) {
		seen[c] = epoch;
		directions[c] = (byte) dirs;
		sources[c] = (byte) srcs;
		parent[c] = from;
		rays[c] = 0;
		visited[visitedCount++] = c;
		queue[tail] = c;
		tail = (tail + 1) % queue.length;
	}

	private void floodSky() {
		skyFlooded = true;
		int layer = width * width;
		for (int column = 0; column < layer; column++) {
			if (columns[column] == null) continue;
			int top = topFilled[column];
			if (top < 0) continue;
			int dx = column % width;
			int dz = column / width;
			int west = dx > 0 && columns[column - 1] != null ? topFilled[column - 1] : Integer.MAX_VALUE;
			int east = dx < width - 1 && columns[column + 1] != null ? topFilled[column + 1] : Integer.MAX_VALUE;
			int north = dz > 0 && columns[column - width] != null ? topFilled[column - width] : Integer.MAX_VALUE;
			int south = dz < width - 1 && columns[column + width] != null ? topFilled[column + width] : Integer.MAX_VALUE;
			int lowest = Math.min(Math.min(west, east), Math.min(north, south));
			int relX = originX + dx - eyeSectionX;
			int relZ = originZ + dz - eyeSectionZ;
			int horizontal = (relX > 0 ? 1 << 5 : relX < 0 ? 1 << 4 : 0) | (relZ > 0 ? 1 << 3 : relZ < 0 ? 1 << 2 : 0);
			for (int y = Math.max(0, Math.min(top, lowest + 1)); y <= top; y++) {
				int srcs = 0;
				if (y == top && top < height - 1) srcs |= 1;
				if (y > west) srcs |= 1 << 5;
				if (y > east) srcs |= 1 << 4;
				if (y > north) srcs |= 1 << 3;
				if (y > south) srcs |= 1 << 2;
				if (srcs == 0) continue;
				int c = y * layer + column;
				if (sealedFrom(columns[column].getSection(y), srcs)) {
					shellMask[column] |= 1L << y;
					continue;
				}
				if (seen[c] == epoch) {
					if ((sources[c] & FREE) == 0) sources[c] |= (byte) srcs;
					continue;
				}
				int relY = minSectionY + y - eyeSectionY;
				if (Math.abs(relX) <= 1 && Math.abs(relY) <= 1 && Math.abs(relZ) <= 1) {
					push(c, 0, FREE, c);
				} else if (lineOfSight && !PandaOcclusionConfig.config.alwaysSendSurface && !inSight(c)) {
					continue;
				} else {
					push(c, horizontal | (relY < 0 ? 1 : relY > 0 ? 2 : 0), srcs, c);
				}
			}
		}
	}

	// the client's far-section check: a ray from the section corner toward the camera
	private boolean rayReachesCamera(int dx, int y, int dz, int dir, Vec3 eye) {
		int originBlockX = SectionPos.sectionToBlockCoord(originX + dx);
		int originBlockY = SectionPos.sectionToBlockCoord(minSectionY + y);
		int originBlockZ = SectionPos.sectionToBlockCoord(originZ + dz);
		int centerX = SectionPos.sectionToBlockCoord(eyeSectionX) + 8;
		int centerY = SectionPos.sectionToBlockCoord(eyeSectionY) + 8;
		int centerZ = SectionPos.sectionToBlockCoord(eyeSectionZ) + 8;
		boolean axisX = dir >= 4;
		boolean axisY = dir <= 1;
		boolean axisZ = dir == 2 || dir == 3;
		boolean maxX = axisX ? centerX > originBlockX : centerX < originBlockX;
		boolean maxY = axisY ? centerY > originBlockY : centerY < originBlockY;
		boolean maxZ = axisZ ? centerZ > originBlockZ : centerZ < originBlockZ;
		double px = originBlockX + (maxX ? 16 : 0);
		double py = originBlockY + (maxY ? 16 : 0);
		double pz = originBlockZ + (maxZ ? 16 : 0);
		double vx = eye.x - px;
		double vy = eye.y - py;
		double vz = eye.z - pz;
		double scale = SECTION_DIAGONAL / Math.sqrt(vx * vx + vy * vy + vz * vz);
		vx *= scale;
		vy *= scale;
		vz *= scale;
		int minY = SectionPos.sectionToBlockCoord(minSectionY);
		int maxBlockY = SectionPos.sectionToBlockCoord(minSectionY + height) - 1;
		int layer = width * width;
		while ((px - eye.x) * (px - eye.x) + (py - eye.y) * (py - eye.y) + (pz - eye.z) * (pz - eye.z) > 3600.0) {
			px += vx;
			py += vy;
			pz += vz;
			if (py > maxBlockY || py < minY) break;
			int cx = SectionPos.blockToSectionCoord(Mth.floor(px)) - originX;
			int cy = SectionPos.blockToSectionCoord(Mth.floor(py)) - minSectionY;
			int cz = SectionPos.blockToSectionCoord(Mth.floor(pz)) - originZ;
			if (cx < 0 || cz < 0 || cx >= width || cz >= width || cy < 0 || cy >= height) return false;
			if (seen[cy * layer + cz * width + cx] != epoch && !(skyFlooded && cy > topFilled[cz * width + cx])) return false;
		}
		return true;
	}

	String trace(int sectionX, int sectionY, int sectionZ) {
		int dx = sectionX - originX;
		int dz = sectionZ - originZ;
		int y = sectionY - minSectionY;
		if (dx < 0 || dz < 0 || dx >= width || dz >= width || y < 0 || y >= height) return "outside walk grid";
		int layer = width * width;
		int c = y * layer + dz * width + dx;
		StringBuilder out = new StringBuilder("sight points:");
		for (Vec3 point : sightPoints) out.append(String.format(" (%.1f %.1f %.1f)", point.x, point.y, point.z));
		out.append(" | ");
		if (seen[c] != epoch) return out + "not reached";
		for (int i = 0; i < 64; i++) {
			int column = c % layer;
			int cy = c / layer;
			long vis = columns[column] == null ? -2 : SectionVis.get(columns[column].getSection(cy));
			out.append(String.format("[%d %d %d dirs=%s src=%s vis=%s] ", originX + column % width, minSectionY + cy, originZ + column / width,
				Integer.toBinaryString(directions[c]), Integer.toBinaryString(sources[c] & 0xFF), Long.toHexString(vis)));
			if ((sources[c] & FREE) != 0 || parent[c] == c) break;
			c = parent[c];
		}
		return out.toString();
	}
}
