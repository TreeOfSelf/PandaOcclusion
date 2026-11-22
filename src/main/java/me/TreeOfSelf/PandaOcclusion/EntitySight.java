package me.TreeOfSelf.PandaOcclusion;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mob line of sight off the server thread.
 *
 * The server thread snapshots, per player, the mobs in range whose sections are visible (id + box), nearest first and
 * capped. A worker raycasts the batch (mobs whose boxes round to the same half block share one answer) and hands back
 * who was seen. The server thread then records the verdicts and calls vanilla's updatePlayer only for mobs whose verdict
 * no longer matches what vanilla is sending. canSee for mobs is a lookup of the last verdict: no rays on the tick.
 *
 * Players are not in here: there are few of them, and their sight check stays immediate.
 */
public final class EntitySight {
	private EntitySight() {
	}

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static ExecutorService threads;

	static ExecutorService threads() {
		if (threads == null) {
			threads = Executors.newFixedThreadPool(Math.max(1, PandaOcclusionConfig.config.sightThreads), runnable -> {
				Thread thread = new Thread(runnable, "PandaOcclusion sight #" + THREAD_ID.incrementAndGet());
				thread.setDaemon(true);
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
		}
		return threads;
	}

	public static final class Result {
		final int generation;
		final long tick;
		final int[] ids;
		final int[] seen;
		Result(int generation, long tick, int[] ids, int[] seen) {
			this.generation = generation;
			this.tick = tick;
			this.ids = ids;
			this.seen = seen;
		}
	}

	public static final class Stats {
		public final LongAdder batches = new LongAdder();
		public final LongAdder entities = new LongAdder();
		public final LongAdder clustered = new LongAdder();
		public final LongAdder boxes = new LongAdder();
		public final LongAdder capped = new LongAdder();
		public final LongAdder workerNanos = new LongAdder();
		public long applyNanos;
		public long submitNanos;
		public long flips;
		public long syncChecks;
		public long syncNanos;
		public long syncOverBudget;
		public long maxTickNanos;

		void reset() {
			batches.reset();
			entities.reset();
			clustered.reset();
			boxes.reset();
			capped.reset();
			workerNanos.reset();
			applyNanos = submitNanos = flips = syncChecks = syncNanos = syncOverBudget = maxTickNanos = 0;
		}
	}

	public static final Stats STATS = new Stats();

	private static final int SKIPPED = Integer.MIN_VALUE;

	private static long tickSpent;
	private static long tickSpentTick = Long.MIN_VALUE;
	private static long syncSpent;
	private static long syncSpentTick = Long.MIN_VALUE;

	static boolean enabled() {
		PandaOcclusionConfig.ConfigData config = PandaOcclusionConfig.config;
		return config.asyncEntitySight && config.hideEntities && config.entityLineOfSight;
	}

	/** Mob (not player, not projectile) that goes through the async path. */
	static boolean handles(Entity entity) {
		return !(entity instanceof Player) && !(entity instanceof Projectile);
	}

	private static void countTick(long now, long nanos) {
		if (tickSpentTick != now) {
			STATS.maxTickNanos = Math.max(STATS.maxTickNanos, tickSpent);
			tickSpent = 0;
			tickSpentTick = now;
		}
		tickSpent += nanos;
	}

	/** Server thread. Verdict for a mob vanilla is asking about; rays only for a never-checked mob, within the budget. */
	static boolean verdict(ServerPlayer viewer, PlayerOcclusion state, Entity entity, long now) {
		int id = entity.getId();
		long seen = state.losSeen.get(id);
		long checked = state.losChecked.get(id);
		if (checked > Long.MIN_VALUE / 4) {
			// sticky: the last batch saw it, or it was seen recently enough to hold
			return seen >= checked || now - seen <= PandaOcclusionConfig.config.lineOfSightHoldTicks;
		}
		if (syncSpentTick != now) {
			syncSpentTick = now;
			syncSpent = 0;
		}
		if (syncSpent >= (long) (PandaOcclusionConfig.config.entitySightSyncBudgetMs * 1_000_000)) {
			// next batch picks it up (it is in range and visible sections, or vanilla would not ask)
			STATS.syncOverBudget++;
			return false;
		}
		long start = System.nanoTime();
		boolean visible = Occlusion.lineOfSight(viewer, state, entity, 1);
		long spent = System.nanoTime() - start;
		syncSpent += spent;
		STATS.syncChecks++;
		STATS.syncNanos += spent;
		countTick(now, spent);
		return visible;
	}

	/** Server thread, every tick per player: apply a finished batch, then send the next one when due. */
	static void tick(ServerPlayer viewer, PlayerOcclusion state, long now, int interval) {
		Result done = state.sightDone;
		if (done != null) {
			state.sightDone = null;
			state.sightBusy = false;
			if (done.generation == state.generation) apply(viewer, state, done, now);
		}
		if (!state.sightBusy && now - state.sightSubmitTick >= interval) {
			submit(viewer, state, now);
		}
	}

	private static void submit(ServerPlayer viewer, PlayerOcclusion state, long now) {
		long start = System.nanoTime();
		state.sightSubmitTick = now;
		PandaOcclusionConfig.ConfigData config = PandaOcclusionConfig.config;
		ServerLevel level = state.level;
		double range = config.entityLineOfSightRange;
		Vec3 eye = viewer.getEyePosition();
		List<Entity> candidates = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, viewer.getBoundingBox().inflate(range), EntitySight::handles)) {
			if (entity.getRootVehicle() == viewer.getRootVehicle()) continue;
			if (!state.isBoxVisible(entity.getBoundingBox())) continue;
			candidates.add(entity);
		}
		int n = candidates.size();
		if (n == 0) {
			long spent = System.nanoTime() - start;
			countTick(now, spent);
			STATS.submitNanos += spent;
			return;
		}
		int lead = config.lineOfSightLeadTicks + viewer.connection.latency() / 50;
		Vec3 leadEye = eye.add(state.velocity.scale(lead));
		Vec3[] origins = Occlusion.origins(viewer, state);
		boolean cluster = config.entitySightCluster;
		// one box per distinct farm cell: the cap (and the rays) are spent on boxes, not on how many mobs share one
		int[] ids = new int[n];
		int[] boxOf = new int[n];
		List<AABB> boxes = new ArrayList<>();
		it.unimi.dsi.fastutil.ints.IntArrayList hints = new it.unimi.dsi.fastutil.ints.IntArrayList();
		Long2IntOpenHashMap byKey = new Long2IntOpenHashMap();
		byKey.defaultReturnValue(-1);
		for (int i = 0; i < n; i++) {
			Entity entity = candidates.get(i);
			ids[i] = entity.getId();
			Vec3 motion = entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb
				? Vec3.ZERO : entity.getDeltaMovement();
			AABB box = entity.getBoundingBox().expandTowards(motion.scale(lead));
			int index = -1;
			if (cluster) {
				long key = clusterKey(box);
				index = byKey.get(key);
				if (index < 0) byKey.put(key, boxes.size());
				else STATS.clustered.increment();
			}
			if (index < 0) {
				index = boxes.size();
				boxes.add(box);
				hints.add(state.losOrigin.getOrDefault(ids[i], -1));
			}
			boxOf[i] = index;
		}
		int unique = boxes.size();
		int cap = Math.max(1, config.entitySightMaxPerBatch);
		// greedy: nearest boxes first; past the cap a mob keeps its last verdict (never checked = hidden)
		boolean[] skip = new boolean[unique];
		if (unique > cap) {
			Integer[] order = new Integer[unique];
			double[] dist = new double[unique];
			for (int i = 0; i < unique; i++) {
				order[i] = i;
				dist[i] = boxes.get(i).getCenter().distanceToSqr(eye);
			}
			java.util.Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
			for (int i = cap; i < unique; i++) skip[order[i]] = true;
			STATS.capped.add(unique - cap);
		}
		AABB[] boxArray = boxes.toArray(new AABB[0]);
		int[] hintArray = hints.toIntArray();
		double margin = config.lineOfSightMargin;
		int generation = state.generation;
		state.sightBusy = true;
		long spent = System.nanoTime() - start;
		STATS.submitNanos += spent;
		countTick(now, spent);
		try {
			threads().execute(() -> {
				long workStart = System.nanoTime();
				int[] boxSeen = new int[unique];
				try {
					for (int b = 0; b < unique; b++) {
						boxSeen[b] = skip[b] ? SKIPPED : LineOfSight.visibleFrom(level, eye, leadEye, boxArray[b], margin, origins, hintArray[b]);
					}
				} catch (RuntimeException e) {
					// a chunk changing under us: drop the batch, the next one retries
					PandaOcclusion.LOGGER.debug("sight batch failed", e);
					state.sightDone = new Result(-1, now, new int[0], new int[0]);
					return;
				}
				int[] seen = new int[n];
				for (int i = 0; i < n; i++) seen[i] = boxSeen[boxOf[i]];
				STATS.batches.increment();
				STATS.entities.add(n);
				STATS.boxes.add(unique);
				STATS.workerNanos.add(System.nanoTime() - workStart);
				state.sightDone = new Result(generation, now, ids, seen);
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			state.sightBusy = false;
		}
	}

	private static long clusterKey(AABB box) {
		long h = Mth.floor(box.minX * 2);
		h = h * 0x9E3779B97F4A7C15L + Mth.floor(box.minY * 2);
		h = h * 0x9E3779B97F4A7C15L + Mth.floor(box.minZ * 2);
		h = h * 0x9E3779B97F4A7C15L + Mth.floor(box.maxX * 2);
		h = h * 0x9E3779B97F4A7C15L + Mth.floor(box.maxY * 2);
		h = h * 0x9E3779B97F4A7C15L + Mth.floor(box.maxZ * 2);
		return h;
	}

	private static void apply(ServerPlayer viewer, PlayerOcclusion state, Result result, long now) {
		long start = System.nanoTime();
		ServerLevel level = state.level;
		ChunkMap chunkMap = level.getChunkSource().chunkMap;
		for (int i = 0; i < result.ids.length; i++) {
			int id = result.ids[i];
			int seen = result.seen[i];
			if (seen == SKIPPED) continue;
			// a sync check newer than this batch wins
			if (state.losChecked.get(id) > result.tick) continue;
			state.losChecked.put(id, result.tick);
			if (seen != LineOfSight.NOT_SEEN) {
				state.losSeen.put(id, result.tick);
				state.losHidden.remove(id);
				if (seen >= 0) state.losOrigin.put(id, seen);
			}
		}
		for (int i = 0; i < result.ids.length; i++) {
			ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(result.ids[i]);
			if (tracked == null) continue;
			Entity entity = level.getEntity(result.ids[i]);
			if (entity == null) continue;
			boolean want = Occlusion.canSee(viewer, entity);
			if (want != ((HiddenTracking) tracked).panda$seenBy(viewer)) {
				STATS.flips++;
				tracked.updatePlayer(viewer);
			}
		}
		long spent = System.nanoTime() - start;
		STATS.applyNanos += spent;
		countTick(now, spent);
	}

	static String statsLine() {
		Stats s = STATS;
		long batches = s.batches.sum();
		long entities = s.entities.sum();
		return String.format("async mob sight: %d batches, %d mobs in %d boxes (%d shared a farm cell, %d boxes past the cap), worker %.1f ms (%.1f us/box) | server thread: submit %.1f ms, apply %.1f ms, %d flips, never-seen sync %d checks %.1f ms (%d over budget), worst tick %.2f ms | sparse ray hops %d",
			batches, entities, s.boxes.sum(), s.clustered.sum(), s.capped.sum(), s.workerNanos.sum() / 1e6, s.boxes.sum() == 0 ? 0 : s.workerNanos.sum() / 1e3 / s.boxes.sum(),
			s.submitNanos / 1e6, s.applyNanos / 1e6, s.flips, s.syncChecks, s.syncNanos / 1e6, s.syncOverBudget, Math.max(s.maxTickNanos, tickSpent) / 1e6,
			LineOfSight.HOPS.sum());
	}
}
