package me.TreeOfSelf.PandaOcclusion;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PlayerOcclusion {
	ServerLevel level;
	boolean enabled;
	boolean bypass;
	Boolean bedrock;
	int blockBudgetTick;
	int blockBudgetUsed;
	int budgetBehindSince = -1;
	int minSectionY;
	int height;
	long fullMask;

	View view;
	volatile View finished;
	volatile boolean walking;
	volatile int generation;
	boolean dirty;
	boolean revealPending;
	boolean needsCoverage;
	int heldTicks;

	// chunk column -> sections sent for real; missing = the client doesn't have the chunk
	final Long2LongOpenHashMap revealed = new Long2LongOpenHashMap();
	final Long2LongOpenHashMap prefetchGrants = new Long2LongOpenHashMap();
	final Long2LongOpenHashMap shellSent = new Long2LongOpenHashMap();
	final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[][]> filled = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	long[][] sendFills;
	long sendPocketReveals;
	final net.minecraft.world.level.chunk.LevelChunkSection[] soundSections = new net.minecraft.world.level.chunk.LevelChunkSection[125];
	long soundSectionsCenter = Long.MIN_VALUE;
	long soundSectionsTick;
	boolean soundSectionsAny;
	long soundLoadedChunk = Long.MIN_VALUE;
	long soundLoadedTick;
	final int[][][][] soundWeights = new int[2][3][5][16];
	long soundWeightsPos = Long.MIN_VALUE;
	final long[] soundMass = new long[250];
	final java.util.List<net.minecraft.world.level.block.entity.BlockEntity> soundEntities = new java.util.ArrayList<>();
	long soundEntitiesChunk = Long.MIN_VALUE;
	long soundEntitiesTick;
	final it.unimi.dsi.fastutil.longs.LongOpenHashSet resent = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	final Long2LongOpenHashMap hideableAtSend = new Long2LongOpenHashMap();

	final Int2LongOpenHashMap losChecked = new Int2LongOpenHashMap();
	final Int2LongOpenHashMap losSeen = new Int2LongOpenHashMap();

	Vec3 lastPosition;
	Vec3 velocity = Vec3.ZERO;
	int stillTicks;
	long aimRevealLast = Long.MIN_VALUE;
	Vec3[] cameraPoints;
	long cameraTick = -1;
	Vec3[] walkCameraPoints = new Vec3[0];
	Vec3[] origins;
	Vec3 originsEye;
	long originsTick = Long.MIN_VALUE;
	final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap losOrigin = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
	int originsVersion;
	final Int2LongOpenHashMap losHidden = new Int2LongOpenHashMap();
	long surfaceBoostUntil;
	long lastWalkTick;
	long lastPrefetchTick = Long.MIN_VALUE / 2;
	View soon;
	Vec3 digPoint;
	boolean teleportPending;
	long teleportSerial;

	public PlayerOcclusion() {
		revealed.defaultReturnValue(-1L);
		losChecked.defaultReturnValue(Long.MIN_VALUE / 2);
		losSeen.defaultReturnValue(Long.MIN_VALUE / 2);
	}

	void reset(ServerLevel newLevel) {
		level = newLevel;
		minSectionY = newLevel.getMinSectionY();
		height = newLevel.getSectionsCount();
		enabled = Occlusion.isEnabled(newLevel) && height < 64;
		fullMask = height >= 64 ? -1L : (1L << height) - 1;
		generation++;
		view = null;
		finished = null;
		soon = null;
		lastPrefetchTick = Long.MIN_VALUE / 2;
		soundSectionsCenter = Long.MIN_VALUE;
		soundLoadedChunk = Long.MIN_VALUE;
		soundWeightsPos = Long.MIN_VALUE;
		soundEntitiesChunk = Long.MIN_VALUE;
		soundEntities.clear();
		java.util.Arrays.fill(soundSections, null);
		revealed.clear();
		prefetchGrants.clear();
		shellSent.clear();
		filled.clear();
		losChecked.clear();
		losSeen.clear();
		losOrigin.clear();
		losHidden.clear();
		origins = null;
		cameraPoints = null;
		lastPosition = null;
		velocity = Vec3.ZERO;
		stillTicks = 0;
	}

	boolean camerasMoved(Vec3[] cameras) {
		if (cameras.length != walkCameraPoints.length) return true;
		double rewalk = PandaOcclusionConfig.config.rewalkDistance;
		for (int i = 0; i < cameras.length; i++) {
			if (cameras[i].distanceToSqr(walkCameraPoints[i]) > rewalk * rewalk) return true;
		}
		return false;
	}

	boolean stoppedSinceWalk(Vec3 eye) {
		return stillTicks >= 2 && view != null && view.eye != null && view.eye.distanceToSqr(eye) > 0.0625;
	}

	boolean eyeSectionChanged(Vec3 eye) {
		double rewalk = velocity.length() >= PandaOcclusionConfig.config.fastSpeed ? PandaOcclusionConfig.config.fastRewalkDistance : PandaOcclusionConfig.config.rewalkDistance;
		return view == null
			|| view.eye != null && view.eye.distanceToSqr(eye) > rewalk * rewalk
			|| SectionPos.blockToSectionCoord(Mth.floor(eye.x)) != view.eyeSectionX
			|| SectionPos.blockToSectionCoord(Mth.floor(eye.y)) != view.eyeSectionY
			|| SectionPos.blockToSectionCoord(Mth.floor(eye.z)) != view.eyeSectionZ;
	}

	long columnMaskAt(int chunkX, int chunkZ) {
		return view == null ? 0L : view.columnMaskAt(chunkX, chunkZ);
	}

	long shellMaskAt(int chunkX, int chunkZ) {
		return view == null ? 0L : view.shellMaskAt(chunkX, chunkZ);
	}

	boolean isSectionVisible(int sectionX, int sectionY, int sectionZ) {
		int y = sectionY - minSectionY;
		if (y < 0 || y >= height) return true;
		return (columnMaskAt(sectionX, sectionZ) >> y & 1L) != 0;
	}

	boolean isBoxVisible(AABB box) {
		int minX = SectionPos.blockToSectionCoord(Mth.floor(box.minX - 0.5));
		int minY = SectionPos.blockToSectionCoord(Mth.floor(box.minY - 0.5));
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ - 0.5));
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(box.maxX + 0.5));
		int maxY = SectionPos.blockToSectionCoord(Mth.floor(box.maxY + 0.5));
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ + 0.5));
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					if (isSectionVisible(x, y, z)) return true;
				}
			}
		}
		return false;
	}

	boolean isBlockHidden(int x, int y, int z) {
		long key = ChunkPos.pack(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
		long mask = revealed.get(key);
		int index = SectionPos.blockToSectionCoord(y) - minSectionY;
		if (index < 0 || index >= height) return false;
		if ((mask >> index & 1L) == 0) return true;
		long[] pocket = pocketAt(key, index);
		if (pocket == null) return false;
		int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
		return (pocket[bit >> 6] >>> (bit & 63) & 1L) != 0;
	}

	boolean isSectionHidden(SectionPos pos) {
		int index = pos.y() - minSectionY;
		if (index < 0 || index >= height) return false;
		return (revealed.get(ChunkPos.pack(pos.x(), pos.z())) >> index & 1L) == 0;
	}

	long[] pocketAt(long chunkKey, int index) {
		if (filled.isEmpty()) return null;
		long[][] sections = filled.get(chunkKey);
		return sections == null ? null : sections[index];
	}

	public boolean hasColumn(long chunkKey) {
		return revealed.containsKey(chunkKey);
	}

	public void forget(long chunkKey) {
		revealed.remove(chunkKey);
		prefetchGrants.remove(chunkKey);
		shellSent.remove(chunkKey);
		filled.remove(chunkKey);
	}

	long hideableMask(LevelChunk chunk) {
		int lowestSurface = LowestSurfaceHolder.get(chunk);
		long mask = 0L;
		for (int i = 0; i < height; i++) {
			if (SectionPos.sectionToBlockCoord(minSectionY + i) + 15 < lowestSurface) {
				mask |= 1L << i;
			}
		}
		return mask;
	}

	long beginSend(LevelChunk chunk) {
		long key = chunk.getPos().pack();
		long hidden = 0L;
		if (enabled && !bypass) {
			boolean resend = revealed.containsKey(key);
			long already = resend ? revealed.get(key) : 0L;
			long hideable = view != null && (view.deep || !PandaOcclusionConfig.config.alwaysSendSurface) ? fullMask : hideableMask(chunk);
			hidden = hideable & ~columnMaskAt(chunk.getPos().x(), chunk.getPos().z()) & ~already & ~prefetchGrants.remove(key);
			long shell = shellMaskAt(chunk.getPos().x(), chunk.getPos().z());
			if (resend && PandaOcclusionConfig.config.generousReveal) {
				for (int i = 0; i < height; i++) {
					if ((hidden >> i & 1L) != 0 && (shell >> i & 1L) == 0 && SectionVis.get(chunk.getSection(i)) != 0) {
						hidden &= ~(1L << i);
					}
				}
			}
		}
		long alreadyReal = revealed.containsKey(key) ? revealed.get(key) : 0L;
		revealed.put(key, fullMask & ~hidden);
		pocketFills(chunk, key, hidden, alreadyReal);
		return hidden;
	}

	private void pocketFills(LevelChunk chunk, long key, long hidden, long alreadyReal) {
		long start = System.nanoTime();
		try {
			pocketFillsTimed(chunk, key, hidden, alreadyReal);
		} finally {
			Occlusion.STATS.pocketServerNanos += System.nanoTime() - start;
		}
	}

	private void pocketFillsTimed(LevelChunk chunk, long key, long hidden, long alreadyReal) {
		long[][] old = filled.get(key);
		long[][] fills = null;
		long pocketReveals = 0L;
		boolean active = enabled && !bypass && view != null && PandaOcclusionConfig.pocketsOn();
		long checked = active ? view.pocketCheckedAt(chunk.getPos().x(), chunk.getPos().z()) : 0L;
		View soon = active ? view.soon : null;
		long soonChecked = soon == null ? 0L : soon.pocketCheckedAt(chunk.getPos().x(), chunk.getPos().z()) & ~checked;
		for (int i = 0; i < height; i++) {
			if ((hidden >> i & 1L) != 0) continue;
			long[] was = old == null ? null : old[i];
			boolean had = (alreadyReal >> i & 1L) != 0;
			long[] now;
			if (!active) now = null;
			else if (((checked | soonChecked) >> i & 1L) == 0) now = had ? was : null;
			else {
				long[] found = ((checked >> i & 1L) != 0 ? view : soon).pocketsAt(chunk.getPos().x(), chunk.getPos().z(), i);
				now = had ? pocketsLeft(was, found, chunk, i) : found;
			}
			if (had && was != null && !java.util.Arrays.equals(was, now)) pocketReveals |= 1L << i;
			if (now != null) {
				if (fills == null) fills = new long[height][];
				fills[i] = now;
			}
		}
		if (fills == null) filled.remove(key);
		else filled.put(key, fills);
		sendFills = fills;
		sendPocketReveals = pocketReveals;
	}

	static long[] pocketsLeft(long[] was, long[] found, LevelChunk chunk, int index) {
		if (was == null) return null;
		LevelChunkSection section = chunk.getSection(index);
		long[] opaque = SectionVis.opaqueBits(section);
		if (opaque == null) opaque = SectionVis.NO_OPAQUE;
		long[] out = null;
		for (int word = 0; word < 64; word++) {
			long leaving = was[word] & (found == null ? -1L : ~found[word]);
			for (; leaving != 0; leaving &= leaving - 1) {
				int bit = word << 6 | Long.numberOfTrailingZeros(leaving);
				if (isOpaque(opaque, bit) && !nextToSeen(chunk, index, opaque, found, bit)) continue;
				if (out == null) out = was.clone();
				out[word] &= ~(1L << (bit & 63));
			}
		}
		if (out == null) return was;
		for (long word : out) if (word != 0) return out;
		return null;
	}

	private static boolean isOpaque(long[] opaque, int bit) {
		return (opaque[bit >> 6] >>> (bit & 63) & 1L) != 0;
	}

	private static boolean nextToSeen(LevelChunk chunk, int index, long[] opaque, long[] found, int bit) {
		int x = bit & 15, z = bit >> 4 & 15, y = bit >> 8 & 15;
		for (int dir = 0; dir < 6; dir++) {
			int nx = x + (dir == 4 ? -1 : dir == 5 ? 1 : 0), ny = y + (dir == 0 ? -1 : dir == 1 ? 1 : 0), nz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
			if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) {
				net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(chunk.getPos().getMinBlockX() + nx,
					SectionPos.sectionToBlockCoord(chunk.getMinSectionY() + index) + ny, chunk.getPos().getMinBlockZ() + nz);
				if (!chunk.getLevel().getBlockState(pos).isSolidRender()) return true;
				continue;
			}
			int n = nx | nz << 4 | ny << 8;
			if (!isOpaque(opaque, n) && (found == null || (found[n >> 6] >>> (n & 63) & 1L) == 0)) return true;
		}
		return false;
	}
}
