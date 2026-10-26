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

	static double blockedAt(ServerLevel level, Vec3 origin, double tx, double ty, double tz) {
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
