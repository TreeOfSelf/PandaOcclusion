package me.TreeOfSelf.PandaOcclusion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

public final class ChunkPacketContext {
	private static final ThreadLocal<ChunkPacketContext> CURRENT = new ThreadLocal<>();

	private final LevelChunk chunk;
	private final long hidden;
	private final long shell;
	private final boolean surfaceHidden;
	private final boolean resend;
	private final long lightKnown;
	private final long[][] fills;
	private final long pocketReveals;
	private LevelChunkSection[] sections;

	private ChunkPacketContext(LevelChunk chunk, long hidden, long shell, boolean surfaceHidden, boolean resend, long lightKnown, long[][] fills, long pocketReveals) {
		this.chunk = chunk;
		this.hidden = hidden;
		this.shell = shell & hidden;
		this.surfaceHidden = surfaceHidden;
		this.resend = resend;
		this.lightKnown = lightKnown;
		this.fills = fills;
		this.pocketReveals = pocketReveals;
	}

	public static void begin(ServerPlayer player, LevelChunk chunk, boolean countStats) {
		CURRENT.remove();
		if (!PandaOcclusionConfig.config.enabled || chunk.getLevel() != player.level()) return;
		PlayerOcclusion state = Occlusion.state(player);
		if (!state.enabled) return;
		long before = state.revealed.get(chunk.getPos().pack());
		long hidden = state.beginSend(chunk);
		long shell = hidden == 0 ? 0L : state.shellMaskAt(chunk.getPos().x(), chunk.getPos().z()) & hidden;
		if (shell == 0) state.shellSent.remove(chunk.getPos().pack());
		else state.shellSent.put(chunk.getPos().pack(), shell);
		if (countStats) Occlusion.STATS.chunksSent++;
		boolean resend = before != -1L && PandaOcclusionConfig.config.skipKnownLight;
		long[][] fills = state.sendFills;
		long pocketReveals = state.sendPocketReveals;
		state.sendFills = null;
		if (PandaOcclusionConfig.config.debugReveals && fills != null || PandaOcclusionConfig.config.debugReveals && pocketReveals != 0) {
			StringBuilder out = new StringBuilder();
			for (int i = 0; fills != null && i < fills.length; i++) {
				if (fills[i] == null) continue;
				int n = 0;
				for (long w : fills[i]) n += Long.bitCount(w);
				out.append(' ').append(i).append(':').append(n);
			}
			PandaOcclusion.LOGGER.info("{} send chunk {} {}: before {} hidden {} pocketReveals {} fills{}", player.getName().getString(), chunk.getPos().x(), chunk.getPos().z(),
				Long.toBinaryString(before), Long.toBinaryString(hidden), Long.toBinaryString(pocketReveals), out);
		}
		if (hidden != 0 || resend || fills != null) {
			ChunkPacketContext context = new ChunkPacketContext(chunk, hidden, state.shellMaskAt(chunk.getPos().x(), chunk.getPos().z()), (hidden & ~state.hideableMask(chunk)) != 0,
				resend, before | hidden, fills, pocketReveals);
			CURRENT.set(context);
			if (!countStats) return;
			Occlusion.STATS.sectionsHidden += Long.bitCount(hidden);
			LevelChunkSection[] real = chunk.getSections();
			LevelChunkSection[] sent = context.sections();
			for (int i = 0; i < real.length; i++) {
				if ((hidden >> i & 1L) == 0 && (fills == null || fills[i] == null)) continue;
				Occlusion.STATS.bytesSaved += real[i].getSerializedSize() - sent[i].getSerializedSize();
				if (fills != null && fills[i] != null) {
					Occlusion.STATS.pocketSectionsSent++;
					for (long word : fills[i]) Occlusion.STATS.pocketBlocksSent += Long.bitCount(word);
				}
			}
		}
	}

	public static void end() {
		CURRENT.remove();
	}

	public static ChunkPacketContext get(LevelChunk chunk) {
		ChunkPacketContext context = CURRENT.get();
		return context != null && context.chunk == chunk ? context : null;
	}

	public long hidden() {
		return hidden;
	}

	public long[][] fills() {
		return fills;
	}

	public BitSet skippedLight(int lightSections) {
		BitSet skip = new BitSet();
		if (!resend) return skip;
		skip.set(0, lightSections);
		long revealedNow = ~lightKnown & ~hidden | pocketReveals;
		for (int i = 0; i < 64; i++) {
			if ((revealedNow >> i & 1L) != 0) skip.clear(i + 1);
		}
		return skip;
	}

	public LevelChunkSection[] sections() {
		if (hidden == 0 && fills == null) return chunk.getSections();
		if (sections == null) {
			LevelChunkSection[] real = chunk.getSections();
			sections = real.clone();
			Level level = chunk.getLevel();
			for (int i = 0; i < real.length; i++) {
				if ((hidden >> i & 1L) == 0) {
					if (fills != null && fills[i] != null) {
						long start = System.nanoTime();
						sections[i] = fillPockets(real[i], fills[i], filler(level, level.getMinSectionY() + i));
						Occlusion.STATS.pocketServerNanos += System.nanoTime() - start;
					}
					continue;
				}
				BlockState filler = filler(level, level.getMinSectionY() + i);
				PalettedContainer<BlockState> states = new PalettedContainer<>(filler, level.palettedContainerFactory().blockStatesStrategy());
				// one plain biome: the real ones would show where lush caves and the deep dark are
				PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(plainBiome(), level.palettedContainerFactory().biomeStrategy());
				sections[i] = new LevelChunkSection(states, biomes);
				if ((shell >> i & 1L) != 0) copySolidFaces(real[i], sections[i]);
			}
		}
		return sections;
	}

	static LevelChunkSection fillPockets(LevelChunkSection real, long[] blocks, BlockState filler) {
		PalettedContainer<BlockState> states = real.getStates().copy();
		for (int word = 0; word < 64; word++) {
			for (long left = blocks[word]; left != 0; left &= left - 1) {
				int index = word << 6 | Long.numberOfTrailingZeros(left);
				BlockState was = states.getAndSetUnchecked(index & 15, index >> 8 & 15, index >> 4 & 15, filler);
				if (OreTrace.isTrace(was)) states.getAndSetUnchecked(index & 15, index >> 8 & 15, index >> 4 & 15, OreTrace.filler(was));
			}
		}
		LevelChunkSection copy = new LevelChunkSection(states, real.getBiomes());
		copy.recalcBlockCounts();
		return copy;
	}

	private static void copySolidFaces(LevelChunkSection real, LevelChunkSection sent) {
		for (int face = 0; face < 6; face++) {
			if (!SectionVis.sealed(real, face)) continue;
			int axis = face >> 1;
			int at = (face & 1) == 0 ? 0 : 15;
			for (int u = 0; u < 16; u++) {
				for (int v = 0; v < 16; v++) {
					// faces in Direction order: down/up (y), north/south (z), west/east (x)
					int x = axis == 2 ? at : u;
					int y = axis == 0 ? at : axis == 1 ? v : u;
					int z = axis == 1 ? at : v;
					sent.setBlockState(x, y, z, real.getBlockState(x, y, z), false);
				}
			}
		}
	}

	private Holder<Biome> plainBiome() {
		if (plainBiome == null) {
			plainBiome = chunk.getNoiseBiome(QuartPos.fromBlock(chunk.getPos().getMiddleBlockX()), QuartPos.fromBlock(chunk.getLevel().getSeaLevel()),
				QuartPos.fromBlock(chunk.getPos().getMiddleBlockZ()));
		}
		return plainBiome;
	}

	private Holder<Biome> plainBiome;

	public Map<Heightmap.Types, long[]> heightmaps(Map<Heightmap.Types, long[]> real) {
		if (!surfaceHidden) return real;
		LevelChunkSection[] sent = sections();
		Level level = chunk.getLevel();
		int minY = level.getMinY();
		int bits = Mth.ceillog2(level.getHeight() + 1);
		Map<Heightmap.Types, long[]> out = new java.util.EnumMap<>(Heightmap.Types.class);
		for (Heightmap.Types type : real.keySet()) {
			SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
			java.util.function.Predicate<BlockState> counts = type.isOpaque();
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int height = minY;
					search:
					for (int i = sent.length - 1; i >= 0; i--) {
						LevelChunkSection section = sent[i];
						if (section.hasOnlyAir()) continue;
						for (int y = 15; y >= 0; y--) {
							if (counts.test(section.getBlockState(x, y, z))) {
								height = SectionPos.sectionToBlockCoord(level.getMinSectionY() + i) + y + 1;
								break search;
							}
						}
					}
					storage.set(x + z * 16, height - minY);
				}
			}
			out.put(type, storage.getRaw());
		}
		return out;
	}

	// the client only really uses MOTION_BLOCKING; the other two are sent as copies of it
	public static Map<Heightmap.Types, long[]> shareHeightmaps(Map<Heightmap.Types, long[]> heightmaps) {
		long[] motionBlocking = heightmaps.get(Heightmap.Types.MOTION_BLOCKING);
		if (motionBlocking == null) return heightmaps;
		Map<Heightmap.Types, long[]> out = new java.util.EnumMap<>(Heightmap.Types.class);
		for (Heightmap.Types type : heightmaps.keySet()) out.put(type, motionBlocking);
		return out;
	}

	public Map<BlockPos, BlockEntity> filterBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {
		int minSectionY = chunk.getLevel().getMinSectionY();
		Map<BlockPos, BlockEntity> kept = new HashMap<>();
		for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
			int index = SectionPos.blockToSectionCoord(entry.getKey().getY()) - minSectionY;
			if (index < 0 || index >= 64 || (hidden >> index & 1L) == 0) {
				if (fills != null && index >= 0 && index < fills.length && fills[index] != null) {
					BlockPos pos = entry.getKey();
					int bit = (pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8;
					if ((fills[index][bit >> 6] >>> (bit & 63) & 1L) != 0) continue;
				}
				kept.put(entry.getKey(), entry.getValue());
			}
		}
		return kept;
	}

	public static BlockState filler(Level level, int sectionY) {
		if (PandaOcclusionConfig.config.hiddenAsAir) return Blocks.AIR.defaultBlockState();
		if (level.dimension() == Level.NETHER) return Blocks.NETHERRACK.defaultBlockState();
		if (!PandaOcclusionConfig.config.hiddenSkyAsStone && SectionPos.sectionToBlockCoord(sectionY) >= level.getSeaLevel()) return Blocks.AIR.defaultBlockState();
		if (level.dimension() == Level.END) return Blocks.END_STONE.defaultBlockState();
		return sectionY < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
	}
}
