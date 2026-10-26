package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LoadedChunks {
	private static final Map<ServerLevel, ConcurrentHashMap<Long, LevelChunk>> LEVELS = new ConcurrentHashMap<>();

	private LoadedChunks() {
	}

	public static void load(ServerLevel level, LevelChunk chunk) {
		LEVELS.computeIfAbsent(level, l -> new ConcurrentHashMap<>()).put(chunk.getPos().pack(), chunk);
	}

	public static void unload(ServerLevel level, LevelChunk chunk) {
		ConcurrentHashMap<Long, LevelChunk> chunks = LEVELS.get(level);
		if (chunks != null) {
			chunks.remove(chunk.getPos().pack(), chunk);
		}
	}

	public static void clear() {
		LEVELS.clear();
	}

	public static Map<Long, LevelChunk> of(ServerLevel level) {
		return LEVELS.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
	}

	public static LevelChunk get(ServerLevel level, int x, int z) {
		return of(level).get(ChunkPos.pack(x, z));
	}
}
