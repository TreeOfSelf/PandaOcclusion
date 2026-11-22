package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class LineOfSight {
	private LineOfSight() {
	}

	public static boolean visible(ServerLevel level, Vec3 eye, Vec3 leadEye, AABB box, double margin) {
		return visibleFrom(level, eye, leadEye, box, margin, NO_ORIGINS, -1) != NOT_SEEN;
	}

	public static final Vec3[] NO_ORIGINS = new Vec3[0];

	public static final int NOT_SEEN = -2, EYE = -1;

	public static int visibleFrom(ServerLevel level, Vec3 eye, Vec3 leadEye, AABB box, double margin, Vec3[] origins, int hint) {
		AABB body = box.deflate(Math.min(0.05, box.getXsize() / 4));
		Vec3 center = body.getCenter();
		double[][] points = new double[19][];
		int n = 0;
		points[n++] = new double[]{center.x, body.maxY, center.z};
		points[n++] = new double[]{center.x, center.y, center.z};
		points[n++] = new double[]{center.x, body.minY, center.z};
		for (int i = 0; i < 8; i++) {
			points[n++] = new double[]{(i & 1) == 0 ? body.minX : body.maxX, (i & 2) == 0 ? body.minY : body.maxY, (i & 4) == 0 ? body.minZ : body.maxZ};
		}
		if (margin > 0) {
			AABB grown = box.inflate(margin);
			for (int i = 0; i < 8; i++) {
				points[n++] = new double[]{(i & 1) == 0 ? grown.minX : grown.maxX, (i & 2) == 0 ? grown.minY : grown.maxY, (i & 4) == 0 ? grown.minZ : grown.maxZ};
			}
		}
		for (int i = 0; i < n; i++) {
			if (clear(level, eye, points[i][0], points[i][1], points[i][2])) return EYE;
		}
		if (eye.distanceToSqr(leadEye) >= 0.01) {
			for (int i = 0; i < n; i++) {
				if (clear(level, leadEye, points[i][0], points[i][1], points[i][2])) return EYE;
			}
		}
		if (hint >= 0 && hint < origins.length && seesCore(level, origins[hint], points)) return hint;
		for (int o = 0; o < origins.length; o++) {
			if (o != hint && seesCore(level, origins[o], points)) return o;
		}
		return NOT_SEEN;
	}

	private static boolean seesCore(ServerLevel level, Vec3 origin, double[][] points) {
		for (int i = 0; i < 3; i++) {
			if (clear(level, origin, points[i][0], points[i][1], points[i][2])) return true;
		}
		return false;
	}

	private static final int CAMERA_SPOTS = 48;
	private static final Vec3[] CAMERA_DIRECTIONS = cameraDirections();

	private static Vec3[] cameraDirections() {
		Vec3[] directions = new Vec3[CAMERA_SPOTS];
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < CAMERA_SPOTS; i++) {
			double y = 1 - (i + 0.5) * 2.0 / CAMERA_SPOTS;
			double radius = Math.sqrt(1 - y * y);
			directions[i] = new Vec3(Math.cos(golden * i) * radius, y, Math.sin(golden * i) * radius);
		}
		return directions;
	}

	public static java.util.List<Vec3> cameraPoints(ServerPlayer player, Vec3 eye) {
		double distance = player.getScale() * player.getAttributeValue(Attributes.CAMERA_DISTANCE);
		if (player.getVehicle() instanceof LivingEntity vehicle) {
			distance = Math.max(distance, vehicle.getScale() * vehicle.getAttributeValue(Attributes.CAMERA_DISTANCE));
		}
		java.util.List<Vec3> points = new java.util.ArrayList<>(CAMERA_DIRECTIONS.length);
		for (Vec3 direction : CAMERA_DIRECTIONS) {
			double zoom = distance;
			Vec3 end = eye.add(direction.scale(distance));
			// clip would load the chunk here, in the middle of vanilla's entity tracking loop
			if (!loaded(player.level(), eye, end)) continue;
			BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) zoom = hit.getLocation().distanceTo(eye);
			zoom -= 0.2;
			if (zoom >= 1.0) points.add(eye.add(direction.scale(zoom)));
		}
		return points;
	}

	public static Vec3[] lookCameraPoints(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double distance = player.getScale() * player.getAttributeValue(Attributes.CAMERA_DISTANCE);
		if (player.getVehicle() instanceof LivingEntity vehicle) {
			distance = Math.max(distance, vehicle.getScale() * vehicle.getAttributeValue(Attributes.CAMERA_DISTANCE));
		}
		return new Vec3[]{lookCamera(player, eye, look, distance), lookCamera(player, eye, look.reverse(), distance)};
	}

	private static Vec3 lookCamera(ServerPlayer player, Vec3 eye, Vec3 forwards, double maxZoom) {
		for (int i = 0; i < 8; i++) {
			double cornerX = ((i & 1) * 2 - 1) * 0.1;
			double cornerY = ((i >> 1 & 1) * 2 - 1) * 0.1;
			double cornerZ = ((i >> 2 & 1) * 2 - 1) * 0.1;
			Vec3 from = eye.add(cornerX, cornerY, cornerZ);
			Vec3 to = from.subtract(forwards.scale(maxZoom));
			if (!loaded(player.level(), from, to)) return eye;
			BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) {
				maxZoom = Math.min(maxZoom, hit.getLocation().distanceTo(eye));
			}
		}
		return eye.subtract(forwards.scale(Math.max(0, maxZoom - 0.05)));
	}

	private static boolean loaded(net.minecraft.world.level.Level level, Vec3 a, Vec3 b) {
		int minX = SectionPos.blockToSectionCoord(Mth.floor(Math.min(a.x, b.x) - 0.2));
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(Math.max(a.x, b.x) + 0.2));
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(Math.min(a.z, b.z) - 0.2));
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(Math.max(a.z, b.z) + 0.2));
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (level.getChunkSource().getChunkNow(x, z) == null) return false;
			}
		}
		return true;
	}

	static boolean clear(ServerLevel level, Vec3 origin, double tx, double ty, double tz) {
		return blockedAt(level, origin, tx, ty, tz) < 0;
	}

	// Same answer as blockedAtReference (first opaque cell on the 3D DDA, origin cell excluded, end cell included;
	// Double.MAX_VALUE when the ray crosses an unloaded column, -1 when clear), but sparse: a section with no opaque
	// blocks is crossed in one hop instead of up to 48 block steps, and above/below the world the ray hops a whole
	// chunk column at a time (still checking every column it crosses is loaded, like the reference).
	static double blockedAt(ServerLevel level, Vec3 origin, double tx, double ty, double tz) {
		final double ox = origin.x, oy = origin.y, oz = origin.z;
		final double dx = tx - ox, dy = ty - oy, dz = tz - oz;
		final int endX = Mth.floor(tx), endY = Mth.floor(ty), endZ = Mth.floor(tz);
		final int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		final double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
		final double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
		final double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
		final int minSectionY = level.getMinSectionY();
		final int sectionCount = level.getSectionsCount();
		final int worldMinY = minSectionY << 4, worldMaxY = (minSectionY + sectionCount) << 4;

		int x = Mth.floor(ox), y = Mth.floor(oy), z = Mth.floor(oz);
		double maxX = dx == 0 ? Double.MAX_VALUE : deltaX * (dx > 0 ? x + 1 - ox : ox - x);
		double maxY = dy == 0 ? Double.MAX_VALUE : deltaY * (dy > 0 ? y + 1 - oy : oy - y);
		double maxZ = dz == 0 ? Double.MAX_VALUE : deltaZ * (dz > 0 ? z + 1 - oz : oz - z);
		int steps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z);

		final boolean main = level.getServer().isSameThread();
		LevelChunk chunk = null;
		LevelChunk lastChunk = null;
		int lastSection = Integer.MIN_VALUE;
		long[] opaque = SectionVis.NO_OPAQUE;
		boolean step = true;
		while (true) {
			if (step) {
				if (steps-- <= 0) return -1;
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
			}
			step = true;
			int chunkX = x >> 4, chunkZ = z >> 4;
			if (chunk == null || chunk.getPos().x() != chunkX || chunk.getPos().z() != chunkZ) {
				// getChunkNow is null off the server thread; sight workers read the same chunks walkers do
				chunk = main ? level.getChunkSource().getChunkNow(chunkX, chunkZ) : LoadedChunks.get(level, chunkX, chunkZ);
				if (chunk == null) return Double.MAX_VALUE;
			}
			int sectionIndex = (y >> 4) - minSectionY;
			double exit;
			if (sectionIndex < 0 || sectionIndex >= sectionCount) {
				// outside the world: nothing to hit until the ray re-enters, but every column it crosses must be loaded
				boolean above = sectionIndex >= sectionCount;
				exit = columnExit(ox, oz, dx, dz, chunkX, chunkZ);
				if (above ? dy < 0 : dy > 0) exit = Math.min(exit, ((above ? worldMaxY : worldMinY) - oy) / dy);
			} else {
				if (sectionIndex != lastSection || chunk != lastChunk) {
					lastSection = sectionIndex;
					lastChunk = chunk;
					LevelChunkSection section = chunk.getSection(sectionIndex);
					opaque = section.hasOnlyAir() ? SectionVis.NO_OPAQUE : SectionVis.opaqueBits(section);
					if (opaque == null) opaque = SectionVis.NO_OPAQUE;
				}
				if (opaque != SectionVis.NO_OPAQUE) {
					int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
					if ((opaque[bit >> 6] >>> bit & 1L) != 0) {
						return origin.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
					}
					continue;
				}
				exit = Math.min(columnExit(ox, oz, dx, dz, chunkX, chunkZ), axisExit(oy, dy, (y >> 4) << 4));
			}
			// hop to just past where the ray leaves this empty box: every cell skipped is empty
			double t = exit + HOP_EPSILON;
			if (t >= 1) return -1;
			int nx = Mth.floor(ox + dx * t), ny = Mth.floor(oy + dy * t), nz = Mth.floor(oz + dz * t);
			// never move backwards on float noise at a boundary
			if ((nx - x) * stepX < 0) nx = x;
			if ((ny - y) * stepY < 0) ny = y;
			if ((nz - z) * stepZ < 0) nz = z;
			if (nx == x && ny == y && nz == z) continue;
			x = nx;
			y = ny;
			z = nz;
			maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - ox : x - ox) / dx;
			maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - oy : y - oy) / dy;
			maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - oz : z - oz) / dz;
			steps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z);
			HOPS.increment();
			step = false;
		}
	}

	// Sparse vs reference marcher on random rays from `from`: same blocked/clear answer, same hit cell, and timing.
	// Mixes short rays, long ones (up to 160 blocks), straight-down ones from far above the world, and axis-aligned ones.
	static String selfTest(ServerLevel level, Vec3 from, int count) {
		java.util.Random random = new java.util.Random(12345);
		Vec3[] origins = new Vec3[count];
		double[][] targets = new double[count][];
		for (int i = 0; i < count; i++) {
			Vec3 origin = from.add(random.nextGaussian() * 8, random.nextGaussian() * 4, random.nextGaussian() * 8);
			double length = switch (i % 4) { case 0 -> 1 + random.nextDouble() * 16; case 1 -> 16 + random.nextDouble() * 144; default -> 4 + random.nextDouble() * 64; };
			double ux = random.nextGaussian(), uy = random.nextGaussian(), uz = random.nextGaussian();
			if (i % 16 == 3) { ux = 0; uz = 0; }
			if (i % 16 == 5) { uy = 0; uz = 0; }
			if (i % 16 == 7) { origin = new Vec3(origin.x, level.getMaxY() + 200 + random.nextDouble() * 1500, origin.z); ux *= 0.01; uz *= 0.01; uy = -1; length = origin.y - from.y + random.nextGaussian() * 4; }
			double norm = Math.sqrt(ux * ux + uy * uy + uz * uz);
			origins[i] = origin;
			targets[i] = new double[]{origin.x + ux / norm * length, origin.y + uy / norm * length, origin.z + uz / norm * length};
		}
		double[] ref = new double[count];
		double[] sparse = new double[count];
		for (int i = 0; i < count; i++) {
			ref[i] = blockedAtReference(level, origins[i], targets[i][0], targets[i][1], targets[i][2]);
			sparse[i] = blockedAt(level, origins[i], targets[i][0], targets[i][1], targets[i][2]);
		}
		long t0 = System.nanoTime();
		for (int i = 0; i < count; i++) ref[i] = blockedAtReference(level, origins[i], targets[i][0], targets[i][1], targets[i][2]);
		long t1 = System.nanoTime();
		for (int i = 0; i < count; i++) sparse[i] = blockedAt(level, origins[i], targets[i][0], targets[i][1], targets[i][2]);
		long t2 = System.nanoTime();
		int verdictDiff = 0, cellDiff = 0, blocked = 0, unloaded = 0;
		StringBuilder first = new StringBuilder();
		for (int i = 0; i < count; i++) {
			if (ref[i] >= 0) blocked++;
			if (ref[i] == Double.MAX_VALUE) unloaded++;
			if ((ref[i] < 0) != (sparse[i] < 0)) {
				verdictDiff++;
				if (first.length() < 400) first.append(String.format(" [%s -> %.2f %.2f %.2f: ref %.2f sparse %.2f]", origins[i], targets[i][0], targets[i][1], targets[i][2], ref[i], sparse[i]));
			} else if (ref[i] != sparse[i]) {
				cellDiff++;
				if (first.length() < 400) first.append(String.format(" [cell %s -> %.2f %.2f %.2f: ref %.2f sparse %.2f]", origins[i], targets[i][0], targets[i][1], targets[i][2], ref[i], sparse[i]));
			}
		}
		return String.format("raytest %d rays (%d blocked, %d unloaded): verdict mismatches %d, hit-cell mismatches %d | reference %.2f ms (%.2f us/ray), sparse %.2f ms (%.2f us/ray), %.1fx%s",
			count, blocked, unloaded, verdictDiff, cellDiff, (t1 - t0) / 1e6, (t1 - t0) / 1e3 / count, (t2 - t1) / 1e6, (t2 - t1) / 1e3 / count,
			(double) (t1 - t0) / Math.max(1, t2 - t1), first);
	}

	static final java.util.concurrent.atomic.LongAdder HOPS = new java.util.concurrent.atomic.LongAdder();
	private static final double HOP_EPSILON = 1.0E-9;

	private static double axisExit(double o, double d, int boxMin) {
		if (d > 0) return (boxMin + 16 - o) / d;
		if (d < 0) return (boxMin - o) / d;
		return Double.MAX_VALUE;
	}

	private static double columnExit(double ox, double oz, double dx, double dz, int chunkX, int chunkZ) {
		return Math.min(axisExit(ox, dx, chunkX << 4), axisExit(oz, dz, chunkZ << 4));
	}

	static double blockedAtReference(ServerLevel level, Vec3 origin, double tx, double ty, double tz) {
		double dx = tx - origin.x;
		double dy = ty - origin.y;
		double dz = tz - origin.z;
		int x = Mth.floor(origin.x);
		int y = Mth.floor(origin.y);
		int z = Mth.floor(origin.z);
		int endX = Mth.floor(tx);
		int endY = Mth.floor(ty);
		int endZ = Mth.floor(tz);
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
		double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
		double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
		double maxX = dx == 0 ? Double.MAX_VALUE : deltaX * (dx > 0 ? x + 1 - origin.x : origin.x - x);
		double maxY = dy == 0 ? Double.MAX_VALUE : deltaY * (dy > 0 ? y + 1 - origin.y : origin.y - y);
		double maxZ = dz == 0 ? Double.MAX_VALUE : deltaZ * (dz > 0 ? z + 1 - origin.z : origin.z - z);

		LevelChunk chunk = null;
		int minSectionY = level.getMinSectionY();
		int steps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z);
		LevelChunk lastChunk = null;
		int lastSection = Integer.MIN_VALUE;
		long[] opaque = SectionVis.NO_OPAQUE;
		for (int i = 0; i < steps; i++) {
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
			int chunkX = SectionPos.blockToSectionCoord(x);
			int chunkZ = SectionPos.blockToSectionCoord(z);
			if (chunk == null || chunk.getPos().x() != chunkX || chunk.getPos().z() != chunkZ) {
				chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) return Double.MAX_VALUE;
			}
			int sectionIndex = SectionPos.blockToSectionCoord(y) - minSectionY;
			if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) continue;
			if (sectionIndex != lastSection || chunk != lastChunk) {
				lastSection = sectionIndex;
				lastChunk = chunk;
				LevelChunkSection section = chunk.getSection(sectionIndex);
				opaque = section.hasOnlyAir() ? SectionVis.NO_OPAQUE : SectionVis.opaqueBits(section);
				if (opaque == null) opaque = SectionVis.NO_OPAQUE;
			}
			int bit = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
			if ((opaque[bit >> 6] >>> bit & 1L) != 0) {
				return origin.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
			}
		}
		return -1;
	}
}
