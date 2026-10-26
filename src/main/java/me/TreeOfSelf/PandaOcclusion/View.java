package me.TreeOfSelf.PandaOcclusion;

public final class View {
	final int originX;
	final int originZ;
	final int width;
	final int radius;
	final int eyeSectionX;
	final int eyeSectionY;
	final int eyeSectionZ;
	final long[] columnMask;
	final boolean[] covered;
	final int walked;
	long serial;
	long[] shellMask;
	net.minecraft.world.phys.Vec3 eye;
	boolean deep;
	String deepReason;
	long[] surfaceMask;
	long[] pocketChecked;
	it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<long[]> pockets;
	View soon;

	View(int originX, int originZ, int width, int radius, int eyeSectionX, int eyeSectionY, int eyeSectionZ, long[] columnMask, boolean[] covered, int walked) {
		this.originX = originX;
		this.originZ = originZ;
		this.width = width;
		this.radius = radius;
		this.eyeSectionX = eyeSectionX;
		this.eyeSectionY = eyeSectionY;
		this.eyeSectionZ = eyeSectionZ;
		this.columnMask = columnMask;
		this.covered = covered;
		this.walked = walked;
	}

	void merge(View other) {
		for (int dz = 0; dz < width; dz++) {
			for (int dx = 0; dx < width; dx++) {
				columnMask[dz * width + dx] |= other.columnMaskAt(originX + dx, originZ + dz);
				if (shellMask != null) shellMask[dz * width + dx] |= other.shellMaskAt(originX + dx, originZ + dz);
			}
		}
		if (shellMask != null) {
			for (int i = 0; i < shellMask.length; i++) shellMask[i] &= ~columnMask[i];
		}
		if (pocketChecked != null || other.pocketChecked != null) {
			if (pocketChecked == null) pocketChecked = new long[width * width];
			if (pockets == null) pockets = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
			int layer = width * width;
			for (int column = 0; column < layer; column++) {
				int chunkX = originX + column % width, chunkZ = originZ + column / width;
				long theirs = other.pocketCheckedAt(chunkX, chunkZ);
				long both = pocketChecked[column] & theirs;
				long onlyTheirs = theirs & ~pocketChecked[column];
				for (long left = both | onlyTheirs; left != 0; left &= left - 1) {
					int y = Long.numberOfTrailingZeros(left);
					long[] other64 = other.pocketsAt(chunkX, chunkZ, y);
					int key = y * layer + column;
					if ((onlyTheirs >> y & 1L) != 0) {
						if (other64 != null) pockets.put(key, other64);
						continue;
					}
					long[] mine = pockets.get(key);
					if (mine == null) continue;
					if (other64 == null) {
						pockets.remove(key);
						continue;
					}
					long[] and = new long[64];
					boolean any = false;
					for (int k = 0; k < 64; k++) any |= (and[k] = mine[k] & other64[k]) != 0;
					if (any) pockets.put(key, and);
					else pockets.remove(key);
				}
				pocketChecked[column] |= theirs;
			}
		}
		if (!other.deep && deep) {
			deep = false;
			deepReason = "point ahead: " + other.deepReason;
		}
	}

	long sendMaskAt(int column) {
		return deep || surfaceMask == null || !PandaOcclusionConfig.config.alwaysSendSurface ? columnMask[column] : columnMask[column] | surfaceMask[column];
	}

	long columnMaskAt(int chunkX, int chunkZ) {
		int dx = chunkX - originX;
		int dz = chunkZ - originZ;
		if (dx < 0 || dz < 0 || dx >= width || dz >= width) return 0L;
		return columnMask[dz * width + dx];
	}

	long shellMaskAt(int chunkX, int chunkZ) {
		int dx = chunkX - originX;
		int dz = chunkZ - originZ;
		if (shellMask == null || dx < 0 || dz < 0 || dx >= width || dz >= width) return 0L;
		return shellMask[dz * width + dx];
	}

	long pocketCheckedAt(int chunkX, int chunkZ) {
		int dx = chunkX - originX;
		int dz = chunkZ - originZ;
		if (pocketChecked == null || dx < 0 || dz < 0 || dx >= width || dz >= width) return 0L;
		return pocketChecked[dz * width + dx];
	}

	long[] pocketsAt(int chunkX, int chunkZ, int y) {
		int dx = chunkX - originX;
		int dz = chunkZ - originZ;
		if (pockets == null || dx < 0 || dz < 0 || dx >= width || dz >= width) return null;
		return pockets.get(y * width * width + dz * width + dx);
	}

	boolean covers(int chunkX, int chunkZ) {
		int dx = chunkX - originX;
		int dz = chunkZ - originZ;
		return dx >= 0 && dz >= 0 && dx < width && dz < width && covered[dz * width + dx];
	}

	boolean inGrid(int chunkX, int chunkZ) {
		int dx = chunkX - originX;
		int dz = chunkZ - originZ;
		return dx >= 0 && dz >= 0 && dx < width && dz < width;
	}
}
