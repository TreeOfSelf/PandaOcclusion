package me.TreeOfSelf.PandaOcclusion;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.TreeOfSelf.PandaOcclusion.mixin.ClientboundSectionBlocksUpdatePacketAccessor;
import it.unimi.dsi.fastutil.shorts.ShortLinkedOpenHashSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Occlusion {
	public static final Stats STATS = new Stats();
	public static final java.util.Set<java.util.UUID> TOGGLED_BYPASS = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public static final Map<java.util.UUID, java.util.Set<java.util.UUID>> HIDDEN_PLAYERS = new java.util.concurrent.ConcurrentHashMap<>();
	public static boolean keepBossBar;
	private static final Map<ServerLevel, LongOpenHashSet> DIRTY = new IdentityHashMap<>();
	private static MinecraftServer server;
	private static final AtomicLong WALK_SERIAL = new AtomicLong();
	private static final ThreadLocal<Walker> WALKER = ThreadLocal.withInitial(Walker::new);
	private static final ExecutorService WALK_THREADS = Executors.newFixedThreadPool(
		Math.max(1, PandaOcclusionConfig.config.walkThreads),
		runnable -> {
			Thread thread = new Thread(runnable, "PandaOcclusion walker");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			return thread;
		});
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final boolean ANTIXRAY_LOADED = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("antixray");

	private Occlusion() {
	}

	public static class Stats {
		public final LongAdder walks = new LongAdder();
		public final LongAdder walkedSections = new LongAdder();
		public final LongAdder walkNanos = new LongAdder();
		public final LongAdder syncWalks = new LongAdder();
		public final LongAdder visComputes = new LongAdder();
		public final LongAdder visNanos = new LongAdder();
		public long reveals;
		public long chunksSent;
		public long chunksHeld;
		public long sectionsHidden;
		public long packetsDropped;
		public long raycasts;
		public long raycastsSkipped;
		public long raycastNanos;
		public long entityRefreshNanos;
		public long bytesSaved;
		public long revealBytes;
		public long lateReveals;
		public long columnResends;
		public long prefetchResends;
		public long prefetchSections;
		public long autoColumns;
		public long bedrockBlockChanges;
		public long bedrockBlocksDeferred;
		public long bedrockMaxBehindTicks;
		public long bedrockTotalLimited;
		public final long[] bedrockChangesBySection = new long[24];
		public final LongAdder caveRayChecks = new LongAdder();
		public final LongAdder caveRays = new LongAdder();
		public final LongAdder caveRaySteps = new LongAdder();
		public final LongAdder surfaceRayChecks = new LongAdder();
		public final LongAdder surfaceRays = new LongAdder();
		public final LongAdder hiddenChecks = new LongAdder();
		public long repeatResends;
		public long lightBytesSkipped;
		public long hiddenSounds;
		public long hiddenSoundNanos;
		public long hiddenSoundSamples;
		public long hiddenSoundHits;
		public final long[] revealDistance = new long[24];
		public final LongAdder pocketSections = new LongAdder();
		public final LongAdder pocketNanos = new LongAdder();
		public final LongAdder pocketRays = new LongAdder();
		public final LongAdder pocketSightRays = new LongAdder();
		public final LongAdder pocketLabelNanos = new LongAdder();
		public final LongAdder pocketRayNanos = new LongAdder();
		public final LongAdder pocketCameraRays = new LongAdder();
		public long pocketServerNanos;
		public long pocketSectionsSent;
		public long pocketBlocksSent;
		public long pocketReveals;
		public final LongAdder oreChecks = new LongAdder();
		public final LongAdder oreRays = new LongAdder();
		public final LongAdder oresHidden = new LongAdder();
		public final LongAdder oreNanos = new LongAdder();
		public final LongAdder oreRayNanos = new LongAdder();
		public final LongAdder oreFarSections = new LongAdder();
		public long oreReveals;
		public long digWalks;
		public long digReveals;

		public void reset() {
			walks.reset();
			walkedSections.reset();
			walkNanos.reset();
			syncWalks.reset();
			visComputes.reset();
			visNanos.reset();
			caveRayChecks.reset();
			caveRays.reset();
			caveRaySteps.reset();
			surfaceRayChecks.reset();
			surfaceRays.reset();
			hiddenChecks.reset();
			pocketSections.reset();
			pocketNanos.reset();
			pocketRays.reset();
			pocketSightRays.reset();
			pocketLabelNanos.reset();
			pocketRayNanos.reset();
			pocketCameraRays.reset();
			oreChecks.reset();
			oreRays.reset();
			oresHidden.reset();
			oreNanos.reset();
			oreRayNanos.reset();
			oreFarSections.reset();
			oreReveals = 0;
			pocketSectionsSent = pocketBlocksSent = pocketReveals = pocketServerNanos = digReveals = digWalks = 0;
			reveals = chunksSent = chunksHeld = sectionsHidden = packetsDropped = raycasts = raycastsSkipped = raycastNanos = entityRefreshNanos = bytesSaved = revealBytes = lateReveals = columnResends = repeatResends = autoColumns = bedrockBlockChanges = bedrockBlocksDeferred = bedrockMaxBehindTicks = bedrockTotalLimited = 0; java.util.Arrays.fill(bedrockChangesBySection, 0); bedrockTotalLimited = lightBytesSkipped = hiddenSounds = hiddenSoundNanos = hiddenSoundSamples = hiddenSoundHits = 0;
			java.util.Arrays.fill(revealDistance, 0);
		}
	}

	public static boolean isEnabled(ServerLevel level) {
		return PandaOcclusionConfig.config.enabled
			&& PandaOcclusionConfig.config.dimensions.contains(level.dimension().identifier().toString());
	}

	public static PlayerOcclusion state(ServerPlayer player) {
		PlayerOcclusion state = ((OcclusionHolder) player).panda$getOcclusion();
		if (state.level != player.level()) {
			state.reset(player.level());
		}
		boolean bypass = PandaOcclusionConfig.config.spectatorsSeeAll && player.isSpectator()
			|| PandaOcclusionConfig.config.bypassPlayers.contains(player.getGameProfile().name())
			|| TOGGLED_BYPASS.contains(player.getUUID());
		if (bypass != state.bypass) {
			state.bypass = bypass;
			state.dirty = true;
		}
		return state;
	}

	public static void markDirty(ServerLevel level, BlockPos pos) {
		if (server == null || !server.isSameThread()) return;
		DIRTY.computeIfAbsent(level, l -> new LongOpenHashSet()).add(SectionPos.asLong(pos));
	}

	public static void tick(MinecraftServer minecraftServer) {
		server = minecraftServer;
		if (!PandaOcclusionConfig.config.enabled) {
			DIRTY.clear();
			return;
		}
		long tick = minecraftServer.getTickCount();
		HiddenSounds.tick(minecraftServer);
		boolean losTick = PandaOcclusionConfig.config.hideEntities && PandaOcclusionConfig.config.playerLineOfSight
			&& tick % Math.max(1, PandaOcclusionConfig.config.lineOfSightInterval) == 0;

		for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
			PlayerOcclusion state = state(player);
			Vec3 position = player.position();
			// smoothed over a few ticks: movement packets arrive bunched up
			Vec3 step = state.lastPosition == null ? Vec3.ZERO : position.subtract(state.lastPosition);
			state.velocity = step.lengthSqr() > 100 ? Vec3.ZERO : state.velocity.scale(0.6).add(step.scale(0.4));
			state.lastPosition = position;
			state.stillTicks = step.lengthSqr() < 1.0E-6 ? state.stillTicks + 1 : 0;
			if (!state.enabled) continue;
			adopt(player, state);

			LongOpenHashSet dirty = DIRTY.get(state.level);
			if (dirty != null && !state.losHidden.isEmpty()) {
				double range = PandaOcclusionConfig.config.entityLineOfSightRange + 16;
				for (long section : dirty) {
					double dx = SectionPos.sectionToBlockCoord(SectionPos.x(section), 8) - position.x;
					double dz = SectionPos.sectionToBlockCoord(SectionPos.z(section), 8) - position.z;
					if (dx * dx + dz * dz < range * range) {
						state.losHidden.clear();
						break;
					}
				}
			}
			if (dirty != null && !state.dirty && state.view != null) {
				for (long section : dirty) {
					if (state.isSectionVisible(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section))) {
						state.dirty = true;
						break;
					}
				}
			}

			int refresh = PandaOcclusionConfig.config.refreshWalkTicks;
			if (state.view == null || state.dirty || state.needsCoverage || state.eyeSectionChanged(player.getEyePosition())
				|| state.stoppedSinceWalk(player.getEyePosition())
				|| state.camerasMoved(cameraPoints(player, state))
				|| refresh > 0 && server.getTickCount() - state.lastWalkTick >= refresh) {
				requestWalk(player, state);
			}
			reveal(player, state, PandaOcclusionConfig.config.maxRevealsPerTick, true);
			if (!state.revealPending) prefetch(player, state, PandaOcclusionConfig.config.prefetchColumnsPerTick);
			HiddenSounds.tickPlayer(player, state);
			AimReveal.tick(player, state);
			if (losTick) {
				refreshPlayers(player, state);
			}
			int entityInterval = entityInterval(state);
			if (PandaOcclusionConfig.config.hideEntities && PandaOcclusionConfig.config.entityLineOfSight
				&& (tick + player.getId()) % entityInterval == 0) {
				refreshNearbyEntities(player, state);
			}
		}
		DIRTY.clear();
	}

	static void requestWalk(ServerPlayer player, PlayerOcclusion state) {
		if (state.walking) return;
		state.walking = true;
		state.lastWalkTick = server.getTickCount();
		state.dirty = false;
		state.needsCoverage = false;
		ServerLevel level = state.level;
		Vec3 eye = player.getEyePosition();
		int viewDistance = server.getPlayerList().getViewDistance();
		boolean bypass = state.bypass;
		int generation = state.generation;
		long serial = WALK_SERIAL.incrementAndGet();
		Vec3[] ahead = lookahead(level, eye, state.velocity);
		Vec3 dig = state.digPoint;
		state.digPoint = null;
		Vec3 motion = state.velocity;
		Vec3[] cameras = cameraPoints(player, state);
		state.walkCameraPoints = cameras;
		boolean prefetch = PandaOcclusionConfig.config.prefetchDistance > 0 && !bypass
			&& server.getTickCount() - state.lastPrefetchTick >= PandaOcclusionConfig.config.prefetchWalkTicks;
		if (prefetch) state.lastPrefetchTick = server.getTickCount();
		Vec3[] momentumPoints = prefetch ? momentumPoints(level, eye, motion) : new Vec3[0];
		boolean hideSurface = !bedrock(player, state) || PandaOcclusionConfig.config.bedrockHideSurfaceWhenDeep;
		try {
			WALK_THREADS.execute(() -> {
				try {
					Walker walker = WALKER.get();
					walker.motion = motion;
					walker.cameraPoints = cameras;
					walker.hideSurface = hideSurface;
					View view = timedWalk(walker, level, eye, viewDistance, bypass);
					for (Vec3 point : ahead) {
						view.merge(timedWalk(walker, level, point, viewDistance, bypass));
					}
					if (dig != null) view.merge(timedWalk(walker, level, dig, viewDistance, bypass));
					if (prefetch) {
						walker.marginOverride = PandaOcclusionConfig.config.prefetchDistance;
						walker.marginVerticalOverride = PandaOcclusionConfig.config.prefetchVertical;
						double speed = motion.length();
						walker.heading = speed > 0.05 ? motion.scale(1 / speed) : null;
						View soon;
						try {
							soon = timedWalk(walker, level, eye, viewDistance, bypass);
						} finally {
							walker.marginOverride = -1;
							walker.marginVerticalOverride = -1;
							walker.heading = null;
						}
						for (Vec3 point : momentumPoints) {
							soon.merge(timedWalk(walker, level, point, viewDistance, bypass));
						}
						state.soon = soon;
					}
					view.soon = state.soon;
					view.serial = serial;
					if (state.generation == generation) state.finished = view;
				} catch (Throwable t) {
					PandaOcclusion.LOGGER.error("Walk failed", t);
				} finally {
					state.walking = false;
				}
			});
		} catch (RuntimeException e) {
			state.walking = false;
		}
	}

	static Vec3[] momentumPoints(ServerLevel level, Vec3 eye, Vec3 motion) {
		double speed = motion.length();
		if (speed < 0.05 || speed > 10) return new Vec3[0];
		Vec3 direction = motion.scale(1 / speed);
		double reach = Math.min(PandaOcclusionConfig.config.prefetchMaxDistance, speed * PandaOcclusionConfig.config.prefetchLeadTicks);
		double open = openDistance(level, eye, direction, (int) Math.ceil(reach));
		java.util.List<Vec3> points = new java.util.ArrayList<>();
		double last = 0;
		for (int i = 1; i <= 3; i++) {
			double distance = Math.min(reach * i / 3, open);
			if (distance - last < 2) continue;
			points.add(eye.add(direction.scale(distance)));
			last = distance;
		}
		return points.toArray(new Vec3[0]);
	}

	static Vec3[] lookahead(ServerLevel level, Vec3 eye, Vec3 velocity) {
		double speed = velocity.length();
		if (speed < PandaOcclusionConfig.config.lookaheadMinSpeed || speed > 10) return new Vec3[0];
		Vec3 direction = velocity.scale(1 / speed);
		int ground = surface(level, eye.x, eye.z);
		double aboveGround = eye.y - ground;
		boolean followTerrain = ground != Integer.MIN_VALUE && aboveGround >= 0 && aboveGround < 12;
		List<Integer> distances = PandaOcclusionConfig.config.lookaheadBlocks;
		Vec3[] points = new Vec3[distances.size()];
		double open = Double.MAX_VALUE;
		if (!followTerrain) {
			int furthest = 0;
			for (int d : distances) furthest = Math.max(furthest, d);
			open = openDistance(level, eye, direction, furthest);
		}
		for (int i = 0; i < points.length; i++) {
			Vec3 point = eye.add(direction.scale(Math.min(distances.get(i), open)));
			if (followTerrain) {
				int there = surface(level, point.x, point.z);
				if (there != Integer.MIN_VALUE) point = new Vec3(point.x, there + aboveGround, point.z);
			}
			points[i] = point;
		}
		return points;
	}

	private static double openDistance(ServerLevel level, Vec3 eye, Vec3 direction, int max) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int d = 1; d <= max; d++) {
			pos.set(Mth.floor(eye.x + direction.x * d), Mth.floor(eye.y + direction.y * d), Mth.floor(eye.z + direction.z * d));
			LevelChunk chunk = LoadedChunks.get(level, pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk == null || level.isOutsideBuildHeight(pos) || chunk.getBlockState(pos).isSolidRender()) return Math.max(0, d - 1.5);
		}
		return max;
	}

	private static int surface(ServerLevel level, double x, double z) {
		int blockX = Mth.floor(x);
		int blockZ = Mth.floor(z);
		LevelChunk chunk = LoadedChunks.get(level, blockX >> 4, blockZ >> 4);
		return chunk == null ? Integer.MIN_VALUE : chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1;
	}

	static View timedWalk(Walker walker, ServerLevel level, Vec3 eye, int viewDistance, boolean bypass) {
		long start = System.nanoTime();
		long scans = STATS.visNanos.sum();
		View view = walker.walk(level, eye, viewDistance, bypass);
		STATS.walkNanos.add(System.nanoTime() - start - (STATS.visNanos.sum() - scans));
		STATS.walks.increment();
		STATS.walkedSections.add(view.walked);
		return view;
	}

	public static void adopt(ServerPlayer player, PlayerOcclusion state) {
		View view = state.finished;
		if (view == null) return;
		state.finished = null;
		adoptView(player, state, view);
	}

	private static void adoptView(ServerPlayer player, PlayerOcclusion state, View view) {
		// a slower walk can finish after a newer one
		if (state.view != null && state.view.serial > view.serial) return;
		if (view.serial > state.teleportSerial) state.teleportPending = false;
		if (state.view != null && state.view.deep && !view.deep) {
			state.surfaceBoostUntil = server.getTickCount() + 60;
		}
		if (PandaOcclusionConfig.config.debugDeep && (state.view == null || state.view.deep != view.deep)) {
			PandaOcclusion.LOGGER.info("{} at y {}: surface {}", player.getName().getString(), String.format("%.1f", view.eye.y), view.deepReason);
		}
		state.view = view;
		state.revealPending = true;
		refreshEntities(player, state);
	}

	public static void onTeleport(ServerPlayer player) {
		if (!PandaOcclusionConfig.config.enabled || server == null) return;
		PlayerOcclusion state = state(player);
		if (!state.enabled) return;
		Vec3 eye = player.getEyePosition();
		View view = state.view;
		if (view != null
			&& Math.abs(SectionPos.blockToSectionCoord(Mth.floor(eye.x)) - view.eyeSectionX) <= 1
			&& Math.abs(SectionPos.blockToSectionCoord(Mth.floor(eye.y)) - view.eyeSectionY) <= 1
			&& Math.abs(SectionPos.blockToSectionCoord(Mth.floor(eye.z)) - view.eyeSectionZ) <= 1) {
			return;
		}
		state.teleportSerial = WALK_SERIAL.get();
		state.teleportPending = true;
		state.dirty = true;
		requestWalk(player, state);
	}

	public static String trace(ServerPlayer player, BlockPos pos) {
		PlayerOcclusion state = state(player);
		Walker walker = new Walker();
		walker.keepColumns = true;
		walker.walk(state.level, player.getEyePosition(), server.getPlayerList().getViewDistance(), false);
		return walker.trace(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
	}

	public static String sectionState(ServerPlayer player, BlockPos pos) {
		PlayerOcclusion state = state(player);
		View view = state.view;
		int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
		int index = (pos.getY() >> 4) - state.minSectionY;
		long key = ChunkPos.pack(cx, cz);
		String inView = "no view";
		String inSend = "";
		if (view != null) {
			int dx = cx - view.originX, dz = cz - view.originZ;
			if (dx < 0 || dz < 0 || dx >= view.width || dz >= view.width) inView = "outside the view grid";
			else {
				int column = dz * view.width + dx;
				inView = "view " + ((view.columnMask[column] >> index & 1L) != 0) + ", send " + ((view.sendMaskAt(column) >> index & 1L) != 0)
					+ ", view eye " + Mth.floor(view.eye.x) + " " + Mth.floor(view.eye.y) + " " + Mth.floor(view.eye.z) + " serial " + view.serial + (view.deep ? " deep" : "")
					+ (view.shellMask != null && (view.shellMask[column] >> index & 1L) != 0 ? ", shell" : "")
					+ ", lowest surface " + LowestSurfaceHolder.get(player.level().getChunk(cx, cz));
			}
		}
		String pockets = "";
		if (index >= 0 && index < state.height) {
			long[] filled = state.pocketAt(key, index);
			long[] found = view == null ? null : view.pocketsAt(cx, cz, index);
			int bit = (pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8;
			pockets = String.format(" | pockets: checked %b, view hides %d (this block %b), client has %d as filler (this block %b)",
				view != null && (view.pocketCheckedAt(cx, cz) >> index & 1L) != 0, count(found), found != null && (found[bit >> 6] >>> (bit & 63) & 1L) != 0,
				count(filled), filled != null && (filled[bit >> 6] >>> (bit & 63) & 1L) != 0);
		}
		return String.format("section %d %d %d: %s | client has column %b, revealed %b, resend pending %b | revealPending %b walking %b dirty %b%s",
			cx, pos.getY() >> 4, cz, inView, state.revealed.containsKey(key), state.revealed.containsKey(key) && (state.revealed.get(key) >> index & 1L) != 0,
			player.connection.chunkSender.isPending(key), state.revealPending, state.walking, state.dirty, pockets);
	}

	private static int count(long[] bits) {
		int n = 0;
		if (bits != null) for (long word : bits) n += Long.bitCount(word);
		return n;
	}

	public static double bench(ServerPlayer player) {
		PlayerOcclusion state = state(player);
		Walker walker = new Walker();
		walker.cameraPoints = cameraPoints(player, state);
		int viewDistance = server.getPlayerList().getViewDistance();
		for (int i = 0; i < 200; i++) walker.walk(state.level, player.getEyePosition(), viewDistance, false);
		long start = System.nanoTime();
		for (int i = 0; i < 500; i++) walker.walk(state.level, player.getEyePosition(), viewDistance, false);
		return (System.nanoTime() - start) / 1e6 / 500;
	}

	public static List<LevelChunk> holdUncovered(ServerPlayer player, List<LevelChunk> chunks, it.unimi.dsi.fastutil.longs.LongSet pending) {
		if (!PandaOcclusionConfig.config.enabled || chunks.isEmpty()) return chunks;
		PlayerOcclusion state = state(player);
		if (!state.enabled || state.bypass) return chunks;
		adopt(player, state);
		View view = state.view;
		List<LevelChunk> ready = new ArrayList<>(chunks.size());
		List<LevelChunk> held = null;
		for (LevelChunk chunk : chunks) {
			int x = chunk.getPos().x();
			int z = chunk.getPos().z();
			if (view != null && !state.teleportPending && (view.covers(x, z) || !view.inGrid(x, z))) {
				ready.add(chunk);
			} else {
				if (held == null) held = new ArrayList<>();
				held.add(chunk);
			}
		}
		if (held == null) {
			state.heldTicks = 0;
			return chunks;
		}
		if (++state.heldTicks > 20) {
			state.heldTicks = 0;
			return chunks;
		}
		state.needsCoverage = true;
		STATS.chunksHeld += held.size();
		for (LevelChunk chunk : held) {
			pending.add(chunk.getPos().pack());
		}
		return ready;
	}

	static void reveal(ServerPlayer player, PlayerOcclusion state, int limit, boolean countLate) {
		View view = state.view;
		if (!state.revealPending || view == null) return;
		if (server.getTickCount() < state.surfaceBoostUntil) limit *= 4;
		LongArrayList candidates = new LongArrayList();
		int width = view.width;
		int eyeY = view.eyeSectionY - state.minSectionY;
		for (int column = 0; column < view.columnMask.length; column++) {
			if (!view.covered[column]) continue;
			int dx = column % width;
			int dz = column / width;
			long key = ChunkPos.pack(view.originX + dx, view.originZ + dz);
			if (!state.revealed.containsKey(key)) continue;
			if (player.connection.chunkSender.isPending(key)) continue;
			long need = view.sendMaskAt(column) & ~state.revealed.get(key);
			while (need != 0) {
				int y = Long.numberOfTrailingZeros(need);
				need &= need - 1;
				long distance = (long) (dx - view.radius) * (dx - view.radius) + (long) (dz - view.radius) * (dz - view.radius)
					+ (long) (y - eyeY) * (y - eyeY);
				candidates.add(distance << 32 | (long) column << 8 | y);
			}
		}
		long pocketStart = System.nanoTime();
		if (view.pocketChecked != null) {
			for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[][]> entry : state.filled.long2ObjectEntrySet()) {
				long key = entry.getLongKey();
				int chunkX = ChunkPos.getX(key), chunkZ = ChunkPos.getZ(key);
				int dx = chunkX - view.originX, dz = chunkZ - view.originZ;
				if (dx < 0 || dz < 0 || dx >= width || dz >= width) continue;
				int column = dz * width + dx;
				if (!view.covered[column] || player.connection.chunkSender.isPending(key)) continue;
				long[][] fills = entry.getValue();
				for (int y = 0; y < fills.length; y++) {
					if (fills[y] == null || (view.pocketChecked[column] >> y & 1L) == 0) continue;
					if (pocketFill(view, state, key, column, y, false) == fills[y]) continue;
					long distance = (long) (dx - view.radius) * (dx - view.radius) + (long) (dz - view.radius) * (dz - view.radius)
						+ (long) (y - eyeY) * (y - eyeY);
					candidates.add(distance << 32 | (long) column << 8 | POCKET | y);
				}
			}
		}
		STATS.pocketServerNanos += System.nanoTime() - pocketStart;
		candidates.sort(null);
		it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap byColumn = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
		it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap pocketsByColumn = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
		int taken = 0;
		for (int i = 0; i < candidates.size(); i++) {
			long candidate = candidates.getLong(i);
			int column = (int) (candidate >>> 8 & 0xFFFFFF);
			int y = (int) (candidate & 63);
			if (!byColumn.containsKey(column) && !pocketsByColumn.containsKey(column) && byColumn.size() + pocketsByColumn.size() >= limit) continue;
			taken++;
			if ((candidate & POCKET) != 0) {
				pocketsByColumn.put(column, pocketsByColumn.get(column) | 1L << y);
				continue;
			}
			byColumn.put(column, byColumn.get(column) | 1L << y);
			int cdx = column % width - view.radius;
			int cdz = column / width - view.radius;
			STATS.revealDistance[Math.min(23, Math.max(Math.abs(cdx), Math.abs(cdz)))]++;
			if (countLate && candidate >>> 32 <= 3) {
				STATS.lateReveals++;
			}
		}
		it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet columns = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
		for (int i = 0; i < candidates.size(); i++) {
			int column = (int) (candidates.getLong(i) >>> 8 & 0xFFFFFF);
			if (byColumn.containsKey(column) || pocketsByColumn.containsKey(column)) columns.add(column);
		}
		boolean deferred = false;
		for (int column : columns) {
			LevelChunk chunk = state.level.getChunkSource().getChunkNow(view.originX + column % width, view.originZ + column / width);
			if (chunk == null) continue;
			long revealedKey = ChunkPos.pack(view.originX + column % width, view.originZ + column / width);
			long sections = byColumn.get(column);
			long pocketSections = pocketsByColumn.get(column);
			String mode = revealMode(player, state);
			if (sections == 0) {
				long[][] fills = pocketFills(view, state, revealedKey, column, 0L, pocketSections);
				int[] cost = new int[1];
				int[][] changes = changedBlocks(state.level, chunk, 0L, pocketSections, fills, state.filled.get(revealedKey), cost);
				boolean oresOnly = onlyOres(changes);
				if (oresOnly || !mode.equals("column")) {
					STATS.pocketReveals += Long.bitCount(pocketSections);
					if (oresOnly) STATS.oreReveals += Long.bitCount(pocketSections);
					if (!oresOnly && cost[0] > PandaOcclusionConfig.config.autoColumnBlocks * 4 && !mode.equals("blocks")) {
						STATS.columnResends++;
						resendColumn(player, chunk);
					} else {
						if (bedrock(player, state) && !takeBlocks(state, changes)) {
							deferred = true;
							continue;
						}
						revealSections(player, state, chunk, 0L, pocketSections, fills, changes, !oresOnly);
					}
					continue;
				}
			}
			long[][] blockFills = null;
			int[][] blockChanges = null;
			if (mode.equals("blocks")) {
				blockFills = pocketFills(view, state, revealedKey, column, sections, pocketSections);
				blockChanges = changedBlocks(state.level, chunk, sections, pocketSections, blockFills, state.filled.get(revealedKey), new int[1]);
				if (bedrock(player, state) && !takeBlocks(state, blockChanges)) {
					deferred = true;
					continue;
				}
			}
			STATS.pocketReveals += Long.bitCount(pocketSections);
			STATS.columnResends++;
			if (state.resent.contains(revealedKey)) STATS.repeatResends++;
			state.resent.add(revealedKey);
			if (PandaOcclusionConfig.config.debugReveals) {
				StringBuilder ys = new StringBuilder();
				for (long left = sections; left != 0; left &= left - 1) {
					ys.append(ys.isEmpty() ? "" : ",").append(SectionPos.sectionToBlockCoord(state.minSectionY + Long.numberOfTrailingZeros(left)));
				}
				PandaOcclusion.LOGGER.info("{} at {} {} {}: reveal chunk {} {} section y {}", player.getName().getString(), Mth.floor(view.eye.x),
					Mth.floor(view.eye.y), Mth.floor(view.eye.z), view.originX + column % width, view.originZ + column / width, ys);
			}
			if (mode.equals("column")) {
				STATS.reveals += Long.bitCount(sections);
				resendColumn(player, chunk);
			} else if (mode.equals("auto")) {
				int[] cost = new int[1];
				long[][] fills = pocketFills(view, state, revealedKey, column, sections, pocketSections);
				int[][] changes = changedBlocks(state.level, chunk, sections, pocketSections, fills, state.filled.get(revealedKey), cost);
				if (cost[0] > PandaOcclusionConfig.config.autoColumnBlocks * 4) {
					STATS.reveals += Long.bitCount(sections);
					STATS.autoColumns++;
					resendColumn(player, chunk);
				} else {
					if (bedrock(player, state) && !takeBlocks(state, changes)) {
						deferred = true;
						continue;
					}
					revealSections(player, state, chunk, sections, pocketSections, fills, changes, true);
				}
			} else {
				revealSections(player, state, chunk, sections, pocketSections, blockFills, blockChanges, true);
			}
		}
		state.revealPending = candidates.size() > taken || deferred;
		if (deferred) {
			if (state.budgetBehindSince < 0) state.budgetBehindSince = server.getTickCount();
		} else if (state.budgetBehindSince >= 0) {
			STATS.bedrockMaxBehindTicks = Math.max(STATS.bedrockMaxBehindTicks, server.getTickCount() - state.budgetBehindSince);
			state.budgetBehindSince = -1;
		}
	}

	static void prefetch(ServerPlayer player, PlayerOcclusion state, int limit) {
		View view = state.view;
		if (limit <= 0 || view == null || view.soon == null) return;
		View soon = view.soon;
		double near = PandaOcclusionConfig.config.prefetchDistance * 2 + state.velocity.length() * PandaOcclusionConfig.config.prefetchWalkTicks * 2;
		if (soon.eye == null || view.eye == null || soon.eye.distanceToSqr(view.eye) > near * near) return;
		int width = view.width;
		int eyeY = view.eyeSectionY - state.minSectionY;
		long[] best = new long[limit];
		java.util.Arrays.fill(best, Long.MAX_VALUE);
		for (int column = 0; column < view.columnMask.length; column++) {
			if (!view.covered[column]) continue;
			int dx = column % width;
			int dz = column / width;
			long key = ChunkPos.pack(view.originX + dx, view.originZ + dz);
			if (!state.revealed.containsKey(key) || state.prefetchGrants.containsKey(key)) continue;
			long need = soon.columnMaskAt(view.originX + dx, view.originZ + dz) & ~view.sendMaskAt(column) & ~state.revealed.get(key);
			if (need == 0 || player.connection.chunkSender.isPending(key)) continue;
			int nearestY = Integer.MAX_VALUE;
			for (long left = need; left != 0; left &= left - 1) {
				int y = Long.numberOfTrailingZeros(left);
				nearestY = Math.min(nearestY, Math.abs(y - eyeY));
			}
			long distance = (long) (dx - view.radius) * (dx - view.radius) + (long) (dz - view.radius) * (dz - view.radius) + (long) nearestY * nearestY;
			long candidate = distance << 32 | column;
			int worst = 0;
			for (int i = 1; i < limit; i++) if (best[i] > best[worst]) worst = i;
			if (candidate < best[worst]) best[worst] = candidate;
		}
		for (long candidate : best) {
			if (candidate == Long.MAX_VALUE) continue;
			int column = (int) (candidate & 0xFFFFFFFFL);
			LevelChunk chunk = state.level.getChunkSource().getChunkNow(view.originX + column % width, view.originZ + column / width);
			if (chunk == null) continue;
			long key = chunk.getPos().pack();
			long need = soon.columnMaskAt(chunk.getPos().x(), chunk.getPos().z()) & ~view.sendMaskAt(column) & ~state.revealed.get(key);
			STATS.prefetchResends++;
			STATS.prefetchSections += Long.bitCount(need);
			if (bedrock(player, state) && !revealMode(player, state).equals("column")) {
				long[][] fills = pocketFills(view, state, key, column, need, 0L);
				int[] cost = new int[1];
				int[][] changes = changedBlocks(state.level, chunk, need, 0L, fills, state.filled.get(key), cost);
				if (asBlocks(player, state, cost[0])) {
					if (!takeBlocks(state, changes)) return;
					revealSections(player, state, chunk, need, 0L, fills, changes, true);
					continue;
				}
			}
			state.prefetchGrants.put(key, need);
			STATS.columnResends++;
			resendColumn(player, chunk);
		}
	}

	public static void onDigStart(ServerPlayer player, BlockPos pos) {
		if (!PandaOcclusionConfig.config.enabled || server == null) return;
		PlayerOcclusion state = ((OcclusionHolder) player).panda$getOcclusion();
		if (state.level != player.level() || !state.enabled || state.bypass) return;
		if (!player.isWithinBlockInteractionRange(pos, 1.0)) return;
		if (PandaOcclusionConfig.config.revealBehindDig && state.level.getBlockState(pos).isSolidRender()) {
			state.digPoint = Vec3.atCenterOf(pos);
			STATS.digWalks++;
			if (state.walking) state.dirty = true;
			else requestWalk(player, state);
		}
		if (!state.isBlockHidden(pos.getX(), pos.getY(), pos.getZ())) return;
		BlockState real = state.level.getBlockState(pos);
		if (!real.isAir() && !OreTrace.isTrace(real)) return;
		LevelChunk chunk = state.level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
		if (chunk == null) return;
		long key = chunk.getPos().pack();
		int index = SectionPos.blockToSectionCoord(pos.getY()) - state.minSectionY;
		boolean wholeSection = state.isSectionHidden(SectionPos.of(pos));
		PandaOcclusion.LOGGER.info("{} dug at hidden {} {} {} {}: sending its {} real", player.getName().getString(), real.isAir() ? "air" : "ore",
			pos.getX(), pos.getY(), pos.getZ(), wholeSection ? "section" : "pocket");
		STATS.digReveals++;
		long[][] digFills = new long[state.height][];
		int[] digCost = new int[1];
		int[][] digChanges = wholeSection && bedrock(player, state) && !revealMode(player, state).equals("column")
			? changedBlocks(state.level, chunk, 1L << index, 0L, digFills, state.filled.get(key), digCost) : null;
		if (digChanges != null && asBlocks(player, state, digCost[0])) {
			revealSections(player, state, chunk, 1L << index, 0L, digFills, digChanges, true);
		} else if (wholeSection) {
			state.prefetchGrants.put(key, state.prefetchGrants.get(key) | 1L << index);
			STATS.columnResends++;
			if (!player.connection.chunkSender.isPending(key)) resendColumn(player, chunk);
		} else {
			long[][] fills = new long[state.height][];
			int[][] changes = changedBlocks(state.level, chunk, 0L, 1L << index, fills, state.filled.get(key), new int[1]);
			STATS.pocketReveals++;
			revealSections(player, state, chunk, 0L, 1L << index, fills, changes, true);
		}
	}

	static boolean asBlocks(ServerPlayer player, PlayerOcclusion state, int cost) {
		String mode = revealMode(player, state);
		return mode.equals("blocks") || mode.equals("auto") && cost <= PandaOcclusionConfig.config.autoColumnBlocks * 4;
	}

	private static int totalBudgetTick = -1;
	private static int totalBudgetUsed;
	private static int bedrockPlayers;

	static boolean takeBlocks(PlayerOcclusion state, int[][] changes) {
		int count = 0;
		for (int[] section : changes) if (section != null) count += section.length;
		int tick = server.getTickCount();
		if (state.blockBudgetTick != tick) {
			state.blockBudgetTick = tick;
			state.blockBudgetUsed = 0;
		}
		if (totalBudgetTick != tick) {
			totalBudgetTick = tick;
			totalBudgetUsed = 0;
			bedrockPlayers = 0;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (Boolean.TRUE.equals(((OcclusionHolder) player).panda$getOcclusion().bedrock)) bedrockPlayers++;
			}
		}
		int budget = PandaOcclusionConfig.config.bedrockBlocksPerTick;
		if (budget > 0 && state.blockBudgetUsed > 0 && state.blockBudgetUsed + count > budget) {
			STATS.bedrockBlocksDeferred++;
			return false;
		}
		int total = PandaOcclusionConfig.config.bedrockBlocksPerTickTotal;
		if (total > 0 && totalBudgetUsed + count > total) {
			int share = total / Math.max(1, bedrockPlayers);
			if (state.blockBudgetUsed + count > share && !(state.blockBudgetUsed == 0 && totalBudgetUsed < total)) {
				STATS.bedrockBlocksDeferred++;
				STATS.bedrockTotalLimited++;
				return false;
			}
		}
		totalBudgetUsed += count;
		state.blockBudgetUsed += count;
		STATS.bedrockBlockChanges += count;
		for (int i = 0; i < changes.length && i < 24; i++) if (changes[i] != null) STATS.bedrockChangesBySection[i] += changes[i].length;
		return true;
	}

	static boolean bedrock(ServerPlayer player, PlayerOcclusion state) {
		revealMode(player, state);
		return Boolean.TRUE.equals(state.bedrock);
	}

	static String revealMode(ServerPlayer player, PlayerOcclusion state) {
		if (state.bedrock == null) state.bedrock = Bedrock.is(player.getUUID());
		return Boolean.TRUE.equals(state.bedrock) ? PandaOcclusionConfig.config.bedrockRevealMode : PandaOcclusionConfig.config.revealMode;
	}

	static void resendColumn(ServerPlayer player, LevelChunk chunk) {
		// not marked revealed here, or the packet would leave out the light of newly shown sections
		for (LevelChunkSection section : chunk.getSections()) {
			STATS.revealBytes += section.getSerializedSize();
		}
		player.connection.chunkSender.markChunkPendingToSend(chunk);
	}

	private static final long POCKET = 1L << 7;

	static long[] pocketFill(View view, PlayerOcclusion state, long key, int column, int y, boolean wasHidden) {
		long[] found = view.pocketChecked != null && (view.pocketChecked[column] >> y & 1L) != 0 ? view.pockets.get(y * view.width * view.width + column) : null;
		if (!PandaOcclusionConfig.pocketsOn()) found = null;
		if (wasHidden) return found;
		LevelChunk chunk = state.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
		long[] was = state.pocketAt(key, y);
		return chunk == null ? was : PlayerOcclusion.pocketsLeft(was, found, chunk, y);
	}

	static long[][] pocketFills(View view, PlayerOcclusion state, long key, int column, long sections, long pocketSections) {
		long[][] fills = new long[state.height][];
		for (long left = sections | pocketSections; left != 0; left &= left - 1) {
			int y = Long.numberOfTrailingZeros(left);
			fills[y] = pocketFill(view, state, key, column, y, (sections >> y & 1L) != 0);
		}
		return fills;
	}

	static int[][] changedBlocks(ServerLevel level, LevelChunk chunk, long sections, long pocketSections, long[][] fills, long[][] had, int[] cost) {
		int[][] result = new int[chunk.getSections().length][];
		int[] entries = new int[4096];
		for (long left = sections | pocketSections; left != 0; left &= left - 1) {
			int index = Long.numberOfTrailingZeros(left);
			int sectionY = level.getMinSectionY() + index;
			LevelChunkSection section = chunk.getSection(index);
			BlockState filler = ChunkPacketContext.filler(level, sectionY);
			long[] keep = fills[index];
			long[] only = (pocketSections >> index & 1L) != 0 && had != null ? had[index] : null;
			int count = 0;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int bit = x | z << 4 | y << 8;
						if (only != null && (only[bit >> 6] >>> (bit & 63) & 1L) == 0) continue;
						if (keep != null && (keep[bit >> 6] >>> (bit & 63) & 1L) != 0) continue;
						BlockState real = section.getBlockState(x, y, z);
						if (real == filler) continue;
						if (keepHidden(real) && enclosed(level, chunk, section, sectionY, x, y, z)) continue;
						entries[count++] = Block.getId(real) << 12 | x << 8 | z << 4 | y;
						cost[0] += net.minecraft.world.level.lighting.LightEngine.hasDifferentLightProperties(filler, real) ? 4 : 1;
					}
				}
			}
			java.util.Arrays.sort(entries, 0, count);
			result[index] = java.util.Arrays.copyOf(entries, count);
		}
		return result;
	}

	static boolean onlyOres(int[][] changes) {
		boolean any = false;
		for (int[] section : changes) {
			if (section == null) continue;
			for (int entry : section) {
				if (!OreTrace.isTrace(Block.stateById(entry >>> 12))) return false;
				any = true;
			}
		}
		return any;
	}

	static void revealSections(ServerPlayer player, PlayerOcclusion state, LevelChunk chunk, long sections, long pocketSections, long[][] fills, int[][] changed, boolean light) {
		ChunkPos chunkPos = chunk.getPos();
		long key = chunkPos.pack();
		// mark first so the outgoing filter lets these through
		state.revealed.put(key, state.revealed.get(key) | sections);
		long[][] filled = state.filled.get(key);
		for (long left = sections | pocketSections; left != 0; left &= left - 1) {
			int y = Long.numberOfTrailingZeros(left);
			if (fills[y] != null && filled == null) {
				filled = new long[state.height][];
				state.filled.put(key, filled);
			}
			if (filled != null) filled[y] = fills[y];
		}
		if (filled != null) {
			boolean any = false;
			for (long[] section : filled) any |= section != null;
			if (!any) state.filled.remove(key);
		}
		ServerLevel level = state.level;
		long all = sections | pocketSections;
		if (light) {
			BitSet lightMask = lightSections(all);
			player.connection.send(new ClientboundLightUpdatePacket(chunkPos, level.getLightEngine(), lightMask, lightMask));
			STATS.revealBytes += 2L * 2048 * Long.bitCount(all);
		}

		while (all != 0) {
			int index = Long.numberOfTrailingZeros(all);
			all &= all - 1;
			int sectionY = level.getMinSectionY() + index;
			int[] entries = changed[index];
			if ((sections >> index & 1L) != 0) STATS.reveals++;
			if (entries.length > 0) {
				ShortLinkedOpenHashSet changes = new ShortLinkedOpenHashSet(entries.length);
				for (int entry : entries) {
					changes.add((short) (entry & 0xFFF));
				}
				STATS.revealBytes += changes.size() * 4L;
				player.connection.send(new ClientboundSectionBlocksUpdatePacket(SectionPos.of(chunkPos, sectionY), changes, chunk.getSection(index)));
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (SectionPos.blockToSectionCoord(blockEntity.getBlockPos().getY()) != sectionY) continue;
				if (state.isBlockHidden(blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(), blockEntity.getBlockPos().getZ())) continue;
				Packet<ClientGamePacketListener> update = blockEntity.getUpdatePacket();
				if (update != null) player.connection.send(update);
			}
		}
	}

	private static boolean keepHidden(BlockState state) {
		return ANTIXRAY_LOADED && PandaOcclusionConfig.keepHidden().contains(state.getBlock());
	}

	private static boolean enclosed(ServerLevel level, LevelChunk chunk, LevelChunkSection section, int sectionY, int x, int y, int z) {
		for (Direction direction : DIRECTIONS) {
			int nx = x + direction.getStepX();
			int ny = y + direction.getStepY();
			int nz = z + direction.getStepZ();
			BlockState neighbor;
			if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
				neighbor = section.getBlockState(nx, ny, nz);
			} else {
				neighbor = level.getBlockState(new BlockPos(chunk.getPos().getMinBlockX() + nx, SectionPos.sectionToBlockCoord(sectionY) + ny, chunk.getPos().getMinBlockZ() + nz));
			}
			if (!neighbor.isSolidRender()) return false;
		}
		return true;
	}

	static void refreshEntities(ServerPlayer player, PlayerOcclusion state) {
		if (!PandaOcclusionConfig.config.hideEntities) return;
		long start = System.nanoTime();
		ChunkMap chunkMap = state.level.getChunkSource().chunkMap;
		for (ChunkMap.TrackedEntity tracked : chunkMap.entityMap.values()) {
			tracked.updatePlayer(player);
		}
		STATS.entityRefreshNanos += System.nanoTime() - start;
	}

	static void refreshPlayers(ServerPlayer viewer, PlayerOcclusion state) {
		ChunkMap chunkMap = state.level.getChunkSource().chunkMap;
		for (ServerPlayer other : state.level.players()) {
			if (other == viewer) continue;
			ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(other.getId());
			if (tracked != null) {
				tracked.updatePlayer(viewer);
			}
		}
		pruneLineOfSight(state);
	}

	// vanilla only re-checks tracking when the entity or player changes section
	static void refreshNearbyEntities(ServerPlayer viewer, PlayerOcclusion state) {
		ChunkMap chunkMap = state.level.getChunkSource().chunkMap;
		double range = PandaOcclusionConfig.config.entityLineOfSightRange;
		long start = System.nanoTime();
		for (Entity entity : state.level.getEntities((Entity) null, viewer.getBoundingBox().inflate(range), entity -> !(entity instanceof Player))) {
			ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(entity.getId());
			if (tracked != null) tracked.updatePlayer(viewer);
		}
		STATS.entityRefreshNanos += System.nanoTime() - start;
		pruneLineOfSight(state);
	}

	static int entityInterval(PlayerOcclusion state) {
		if (isFast(state)) return 1;
		return Math.max(1, PandaOcclusionConfig.config.entityLineOfSightInterval);
	}

	static int entityInterval(ServerPlayer viewer, PlayerOcclusion state, Entity entity) {
		if (!isFast(state)) return Math.max(1, PandaOcclusionConfig.config.entityLineOfSightInterval);
		double near = PandaOcclusionConfig.config.fastEntityNearDistance;
		return entity.distanceToSqr(viewer) < near * near ? 1 : Math.max(1, PandaOcclusionConfig.config.fastEntityFarInterval);
	}

	private static boolean velocityIsZero(PlayerOcclusion state) {
		return state.velocity.lengthSqr() < 1.0E-4;
	}

	private static boolean isFast(PlayerOcclusion state) {
		return state.velocity.lengthSqr() > PandaOcclusionConfig.config.fastSpeed * PandaOcclusionConfig.config.fastSpeed;
	}

	private static void pruneLineOfSight(PlayerOcclusion state) {
		if (state.losChecked.size() <= 4096) return;
		long now = server.getTickCount();
		state.losChecked.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > 200);
		state.losSeen.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > 200);
		state.losOrigin.clear();
		state.losHidden.clear();
	}

	public static void trackHiddenPlayer(ServerPlayer viewer, Player target, boolean hidden) {
		if (hidden) {
			HIDDEN_PLAYERS.computeIfAbsent(viewer.getUUID(), uuid -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(target.getUUID());
		} else {
			java.util.Set<java.util.UUID> hiddenPlayers = HIDDEN_PLAYERS.get(viewer.getUUID());
			if (hiddenPlayers != null) hiddenPlayers.remove(target.getUUID());
		}
	}

	public static boolean isPlayerHidden(java.util.UUID viewer, java.util.UUID target) {
		java.util.Set<java.util.UUID> hiddenPlayers = HIDDEN_PLAYERS.get(viewer);
		return hiddenPlayers != null && hiddenPlayers.contains(target);
	}

	public static boolean canSee(ServerPlayer player, Entity entity) {
		if (!PandaOcclusionConfig.config.enabled || !PandaOcclusionConfig.config.hideEntities) return true;
		PlayerOcclusion state = ((OcclusionHolder) player).panda$getOcclusion();
		if (state.level != player.level() || !state.enabled || state.bypass || state.view == null) return true;
		if (entity.level() != state.level) return true;
		if (entity == player || entity.getRootVehicle() == player.getRootVehicle()) return true;
		// glowing shows through walls in vanilla, and the dragon is too big to pop in and out
		if (entity.isCurrentlyGlowing() || entity instanceof EnderDragon) return true;
		if (entity instanceof net.minecraft.world.entity.LightningBolt) return true;
		double vanilla = entity instanceof Player ? PandaOcclusionConfig.config.playerVanillaDistance : PandaOcclusionConfig.config.entityVanillaDistance;
		if (vanilla > 0 && entity.distanceToSqr(player) < vanilla * vanilla) return true;
		if (!state.isBoxVisible(entity.getBoundingBox())) return false;
		if (entity instanceof Player target) {
			return !PandaOcclusionConfig.config.playerLineOfSight
				|| lineOfSight(player, state, target, PandaOcclusionConfig.config.lineOfSightInterval);
		}
		// projectiles are too quick to wait for a check
		if (PandaOcclusionConfig.config.entityLineOfSight && !(entity instanceof Projectile)) {
			return lineOfSight(player, state, entity, entityInterval(player, state, entity));
		}
		return true;
	}

	public static boolean canSeeBox(ServerPlayer player, AABB box) {
		if (!PandaOcclusionConfig.config.enabled || !PandaOcclusionConfig.config.hideEntities) return true;
		PlayerOcclusion state = ((OcclusionHolder) player).panda$getOcclusion();
		if (state.level != player.level() || !state.enabled || state.bypass || state.view == null) return true;
		double vanilla = PandaOcclusionConfig.config.entityVanillaDistance;
		if (vanilla > 0 && box.distanceToSqr(player.position()) < vanilla * vanilla) return true;
		if (!state.isBoxVisible(box)) return false;
		if (!PandaOcclusionConfig.config.entityLineOfSight) return true;
		int id = Integer.MIN_VALUE | Long.hashCode(Double.doubleToLongBits(box.minX) * 31 + Double.doubleToLongBits(box.minY) * 17 + Double.doubleToLongBits(box.minZ)
			^ Double.doubleToLongBits(box.maxX) * 13 ^ Double.doubleToLongBits(box.maxY) * 7 ^ Double.doubleToLongBits(box.maxZ));
		return lineOfSight(player, state, id, box, Vec3.ZERO, entityInterval(state));
	}

	static String why(ServerPlayer viewer, Entity target) {
		PlayerOcclusion state = state(viewer);
		long now = server.getTickCount();
		int lead = PandaOcclusionConfig.config.lineOfSightLeadTicks + viewer.connection.latency() / 50;
		Vec3 eye = viewer.getEyePosition();
		Vec3 leadEye = eye.add(state.velocity.scale(lead));
		AABB box = target.getBoundingBox();
		double margin = PandaOcclusionConfig.config.lineOfSightMargin;
		Vec3[] origins = origins(viewer, state);
		int from = LineOfSight.visibleFrom(state.level, eye, leadEye, box, margin, origins, -1);
		return String.format("%s id %d dist %.1f | canSee %s | section visible %s | line of sight now %s (eye %s, lead %s, from spot %s of %d) | seen %d ticks ago, checked %d ago | velocity %.2f",
			target.getType().toShortString(), target.getId(), target.distanceTo(viewer), canSee(viewer, target), state.isBoxVisible(target.getBoundingBox()),
			from != LineOfSight.NOT_SEEN, LineOfSight.visible(state.level, eye, eye, box, margin), LineOfSight.visible(state.level, leadEye, leadEye, box, margin),
			from >= 0 ? origins[from].toString() : "-", origins.length,
			now - state.losSeen.get(target.getId()), now - state.losChecked.get(target.getId()), state.velocity.length());
	}

	static boolean lineOfSight(ServerPlayer viewer, PlayerOcclusion state, Entity target, int interval) {
		Vec3 targetVelocity = target instanceof ServerPlayer serverTarget
			? ((OcclusionHolder) serverTarget).panda$getOcclusion().velocity
			: target instanceof net.minecraft.world.entity.item.ItemEntity || target instanceof net.minecraft.world.entity.ExperienceOrb
			? Vec3.ZERO
			: target.getDeltaMovement();
		return lineOfSight(viewer, state, target.getId(), target.getBoundingBox(), targetVelocity, interval);
	}

	static boolean lineOfSight(ServerPlayer viewer, PlayerOcclusion state, int id, AABB targetBox, Vec3 targetVelocity, int interval) {
		long now = server == null ? 0 : server.getTickCount();
		if (now - state.losChecked.get(id) >= Math.max(1, interval)) {
			state.losChecked.put(id, now);
			long start = System.nanoTime();
			int lead = PandaOcclusionConfig.config.lineOfSightLeadTicks + viewer.connection.latency() / 50;
			Vec3 eye = viewer.getEyePosition();
			Vec3 leadEye = eye.add(state.velocity.scale(lead));
			AABB box = targetBox.expandTowards(targetVelocity.scale(lead));
			Vec3[] origins = origins(viewer, state);
			long key = (long) state.originsVersion << 32 ^ (long) Mth.floor(box.minX * 8) * 73856093L ^ (long) Mth.floor(box.minY * 8) * 19349663L ^ (long) Mth.floor(box.minZ * 8) * 83492791L
				^ (long) Mth.floor(box.maxX * 8) * 2654435761L;
			if (velocityIsZero(state) && state.losHidden.containsKey(id) && state.losHidden.get(id) == key) {
				STATS.raycastsSkipped++;
			} else {
				int seen = LineOfSight.visibleFrom(state.level, eye, leadEye, box, PandaOcclusionConfig.config.lineOfSightMargin,
					origins, state.losOrigin.getOrDefault(id, -1));
				if (seen != LineOfSight.NOT_SEEN) {
					state.losSeen.put(id, now);
					state.losHidden.remove(id);
					if (seen >= 0) state.losOrigin.put(id, seen);
				} else {
					state.losHidden.put(id, key);
				}
			}
			STATS.raycasts++;
			STATS.raycastNanos += System.nanoTime() - start;
		}
		return now - state.losSeen.get(id) <= PandaOcclusionConfig.config.lineOfSightHoldTicks;
	}

	static Vec3[] cameraPoints(ServerPlayer player, PlayerOcclusion state) {
		if (!PandaOcclusionConfig.config.thirdPersonCamera) return new Vec3[0];
		long now = server == null ? 0 : server.getTickCount();
		if (state.cameraTick != now || state.cameraPoints == null) {
			state.cameraPoints = LineOfSight.lookCameraPoints(player);
			state.cameraTick = now;
		}
		return state.cameraPoints;
	}

	static Vec3[] origins(ServerPlayer player, PlayerOcclusion state) {
		Vec3 eye = player.getEyePosition();
		long now = server == null ? 0 : server.getTickCount();
		if (state.origins != null && now - state.originsTick < 10 && state.originsEye.distanceToSqr(eye) < 0.0625) return state.origins;
		state.originsEye = eye;
		state.originsTick = now;
		java.util.List<Vec3> spots = new java.util.ArrayList<>();
		double reach = PandaOcclusionConfig.config.lineOfSightReach;
		if (reach > 0) {
			for (int i = 0; i < 9; i++) {
				Vec3 spot = i == 8 ? eye.add(0, 1.0, 0)
					: eye.add(Math.cos(i * Math.PI / 4) * reach, 0, Math.sin(i * Math.PI / 4) * reach);
				if (LineOfSight.clear(state.level, eye, spot.x, spot.y, spot.z)) spots.add(spot);
			}
		}
		java.util.List<Vec3> cameras = PandaOcclusionConfig.config.thirdPersonCamera ? LineOfSight.cameraPoints(player, eye) : java.util.List.of();
		java.util.List<Vec3> kept = new java.util.ArrayList<>();
		for (int i = 0; i < spots.size() + cameras.size(); i++) {
			Vec3 spot = i >= spots.size() ? cameras.get(i - spots.size()) : spots.get(i);
			boolean near = spot.distanceToSqr(eye) < 0.5625;
			for (int k = 0; k < kept.size() && !near; k++) near = kept.get(k).distanceToSqr(spot) < 0.5625;
			if (near) continue;
			kept.add(spot);
		}
		Vec3[] origins = kept.toArray(new Vec3[0]);
		if (!java.util.Arrays.equals(origins, state.origins)) state.originsVersion++;
		return state.origins = origins;
	}

	public static Packet<?> filter(ServerPlayer player, Packet<?> packet) {
		if (!PandaOcclusionConfig.config.enabled || server == null || !server.isSameThread()) return packet;
		PlayerOcclusion state = ((OcclusionHolder) player).panda$getOcclusion();
		if (state.level != player.level() || !state.enabled || state.bypass) return packet;
		Packet<?> result = filterPacket(player, state, packet);
		if (result != packet) {
			STATS.packetsDropped++;
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Packet<?> filterPacket(ServerPlayer player, PlayerOcclusion state, Packet<?> packet) {
		return switch (packet) {
			case ClientboundBlockUpdatePacket p -> hidden(state, p.getPos()) ? null : p;
			case ClientboundSectionBlocksUpdatePacket p -> {
				SectionPos pos = ((ClientboundSectionBlocksUpdatePacketAccessor) p).panda$getSectionPos();
				yield state.isSectionHidden(pos) ? null : withoutPockets(state, p, pos);
			}
			case ClientboundBlockEntityDataPacket p -> hidden(state, p.getPos()) ? null : p;
			case ClientboundBlockEventPacket p -> hidden(state, p.getPos()) ? null : p;
			case ClientboundBlockDestructionPacket p -> hidden(state, p.getPos()) ? null : p;
			case ClientboundLevelEventPacket p -> !p.isGlobalEvent() && hidden(state, p.getPos()) ? HiddenSounds.levelEvent(player, p) : p;
			case ClientboundLevelParticlesPacket p -> PandaOcclusionConfig.config.hideParticles
				&& state.isBlockHidden(Mth.floor(p.x()), Mth.floor(p.y()), Mth.floor(p.z())) ? null : p;
			case ClientboundSoundPacket p -> PandaOcclusionConfig.config.hideSounds
				&& state.isBlockHidden(Mth.floor(p.getX()), Mth.floor(p.getY()), Mth.floor(p.getZ())) ? null : p;
			case ClientboundSoundEntityPacket p -> {
				Entity source = state.level.getEntity(p.getId());
				yield source == null || canSee(player, source) ? p
					: new ClientboundSoundPacket(p.getSound(), p.getSource(), source.getX(), source.getY(), source.getZ(), p.getVolume(), p.getPitch(), p.getSeed());
			}
			case ClientboundLightUpdatePacket p -> filterLight(state, p);
			case ClientboundBundlePacket p -> {
				List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
				boolean changed = false;
				for (Packet<? super ClientGamePacketListener> sub : p.subPackets()) {
					Packet<?> filtered = filterPacket(player, state, sub);
					if (filtered != sub) changed = true;
					if (filtered != null) kept.add((Packet<? super ClientGamePacketListener>) filtered);
				}
				yield changed ? new ClientboundBundlePacket(kept) : p;
			}
			default -> packet;
		};
	}

	private static Packet<?> withoutPockets(PlayerOcclusion state, ClientboundSectionBlocksUpdatePacket packet, SectionPos pos) {
		long[] pocket = state.pocketAt(ChunkPos.pack(pos.x(), pos.z()), pos.y() - state.minSectionY);
		if (pocket == null) return packet;
		short[] positions = ((ClientboundSectionBlocksUpdatePacketAccessor) packet).panda$getPositions();
		it.unimi.dsi.fastutil.shorts.ShortArraySet kept = new it.unimi.dsi.fastutil.shorts.ShortArraySet();
		for (short position : positions) {
			// packed x << 8 | z << 4 | y
			int bit = (position >> 8 & 15) | (position >> 4 & 15) << 4 | (position & 15) << 8;
			if ((pocket[bit >> 6] >>> (bit & 63) & 1L) == 0) kept.add(position);
		}
		if (kept.size() == positions.length) return packet;
		if (kept.isEmpty()) return null;
		LevelChunk chunk = state.level.getChunkSource().getChunkNow(pos.x(), pos.z());
		if (chunk == null) return null;
		return new ClientboundSectionBlocksUpdatePacket(pos, kept, chunk.getSection(pos.y() - state.minSectionY));
	}

	private static boolean hidden(PlayerOcclusion state, BlockPos pos) {
		return state.isBlockHidden(pos.getX(), pos.getY(), pos.getZ());
	}

	private static Packet<?> zeroedLight;

	private static Packet<?> filterLight(PlayerOcclusion state, ClientboundLightUpdatePacket packet) {
		if (packet == zeroedLight) {
			zeroedLight = null;
			return packet;
		}
		Packet<?> result = filterHiddenLight(state, packet);
		long[][] fills = state.filled.isEmpty() ? null : state.filled.get(ChunkPos.pack(packet.x(), packet.z()));
		if (!(result instanceof ClientboundLightUpdatePacket light) || fills == null) return result;
		ClientboundLightUpdatePacketData data = light.lightData();
		BitSet pockets = new BitSet();
		for (int i = 0; i < fills.length; i++) if (fills[i] != null) pockets.set(i + 1);
		if (!pockets.intersects(data.skyYMask()) && !pockets.intersects(data.blockYMask())) return result;
		if (result == packet) {
			BitSet sky = (BitSet) data.skyYMask().clone();
			sky.or(data.emptySkyYMask());
			BitSet block = (BitSet) data.blockYMask().clone();
			block.or(data.emptyBlockYMask());
			light = new ClientboundLightUpdatePacket(new ChunkPos(packet.x(), packet.z()), state.level.getLightEngine(), sky, block);
		}
		zeroPocketLight(light.lightData(), fills);
		zeroedLight = light;
		return light;
	}

	public static void zeroPocketLight(ClientboundLightUpdatePacketData data, long[][] fills) {
		zeroPocketLight(data.skyYMask(), data.skyUpdates(), fills);
		zeroPocketLight(data.blockYMask(), data.blockUpdates(), fills);
	}

	private static void zeroPocketLight(BitSet mask, List<byte[]> updates, long[][] fills) {
		int index = 0;
		for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1), index++) {
			int section = i - 1;
			if (section < 0 || section >= fills.length || fills[section] == null) continue;
			byte[] nibbles = updates.get(index).clone();
			long[] blocks = fills[section];
			for (int word = 0; word < 64; word++) {
				for (long left = blocks[word]; left != 0; left &= left - 1) {
					int bit = word << 6 | Long.numberOfTrailingZeros(left);
					nibbles[bit >> 1] &= (byte) ((bit & 1) == 0 ? 0xF0 : 0x0F);
				}
			}
			updates.set(index, nibbles);
		}
	}

	private static Packet<?> filterHiddenLight(PlayerOcclusion state, ClientboundLightUpdatePacket packet) {
		long mask = state.revealed.get(ChunkPos.pack(packet.x(), packet.z()));
		long hidden = state.fullMask & ~mask;
		if (hidden == 0) return packet;
		BitSet hiddenLight = lightSections(hidden);
		ClientboundLightUpdatePacketData data = packet.lightData();
		BitSet sky = (BitSet) data.skyYMask().clone();
		sky.or(data.emptySkyYMask());
		BitSet block = (BitSet) data.blockYMask().clone();
		block.or(data.emptyBlockYMask());
		if (!sky.intersects(hiddenLight) && !block.intersects(hiddenLight)) return packet;
		sky.andNot(hiddenLight);
		block.andNot(hiddenLight);
		if (sky.isEmpty() && block.isEmpty()) return null;
		return new ClientboundLightUpdatePacket(new ChunkPos(packet.x(), packet.z()), state.level.getLightEngine(), sky, block);
	}

	// light sections are offset by one (there's an extra one below the world)
	static BitSet lightSections(long hiddenSections) {
		BitSet bits = new BitSet();
		for (int i = 0; i < 64; i++) {
			if ((hiddenSections >> i & 1L) != 0) bits.set(i + 1);
		}
		return bits;
	}

	public static void hideLight(ClientboundLightUpdatePacketData data, long hidden, BitSet skipped) {
		BitSet hiddenLight = lightSections(hidden);
		hiddenLight.andNot(skipped);
		for (BitSet mask : new BitSet[] {data.skyYMask(), data.blockYMask()}) {
			BitSet cut = (BitSet) mask.clone();
			cut.and(skipped);
			STATS.lightBytesSkipped += 2048L * cut.cardinality();
		}
		strip(data.skyYMask(), data.emptySkyYMask(), data.skyUpdates(), hiddenLight, skipped);
		strip(data.blockYMask(), data.emptyBlockYMask(), data.blockUpdates(), hiddenLight, skipped);
	}

	private static void strip(BitSet mask, BitSet empty, List<byte[]> updates, BitSet hiddenLight, BitSet skipped) {
		List<byte[]> kept = new ArrayList<>(updates.size());
		int index = 0;
		for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
			if (!hiddenLight.get(i) && !skipped.get(i)) kept.add(updates.get(index));
			index++;
		}
		mask.andNot(hiddenLight);
		mask.andNot(skipped);
		empty.or(hiddenLight);
		empty.andNot(skipped);
		updates.clear();
		updates.addAll(kept);
	}
}
