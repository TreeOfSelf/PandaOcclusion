package me.TreeOfSelf.PandaOcclusion;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.zip.Deflater;

final class PacketAnalysis {
	private PacketAnalysis() {
	}

	static String analyze(ServerLevel level, int centerX, int centerZ, int radius) {
		census(level, centerX, centerZ, radius);
		try {
			return run(level, centerX, centerZ, radius);
		} catch (RuntimeException e) {
			PandaOcclusion.LOGGER.error("analyze failed", e);
			return "analyze failed: " + e;
		}
	}

	private static String run(ServerLevel level, int centerX, int centerZ, int radius) {
		long chunks = 0;
		long full = 0, fullRaw = 0, sections = 0, sectionsRaw = 0, light = 0, lightRaw = 0;
		long height = 0, heightRaw = 0, enclosed = 0, enclosedRaw = 0, enclosedAll = 0, lightArrays = 0, lightZero = 0, lightFull = 0;
		long[] bitsHistogram = new long[16];
		for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
			for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
				LevelChunk chunk = LoadedChunks.get(level, cx, cz);
				if (chunk == null) continue;
				chunks++;
				ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(chunk.getPos(), level.getLightEngine(), null, null);
				java.io.ByteArrayOutputStream lightOut = new java.io.ByteArrayOutputStream();
				for (byte[] data : lightData.skyUpdates()) { lightOut.writeBytes(data); lightArrays++; if (allSame(data, (byte) 0)) lightZero++; else if (allSame(data, (byte) 0xFF)) lightFull++; }
				for (byte[] data : lightData.blockUpdates()) { lightOut.writeBytes(data); lightArrays++; if (allSame(data, (byte) 0)) lightZero++; }
				byte[] lightBytes = lightOut.toByteArray();
				lightRaw += lightBytes.length;
				light += deflate(lightBytes);
				java.io.ByteArrayOutputStream heightOut = new java.io.ByteArrayOutputStream();
				for (var entry : chunk.getHeightmaps()) {
					if (!entry.getKey().sendToClient()) continue;
					for (long value : entry.getValue().getRawData()) for (int b = 0; b < 8; b++) heightOut.write((int) (value >>> (b * 8)));
				}
				byte[] heightBytes = heightOut.toByteArray();
				heightRaw += heightBytes.length;
				height += deflate(heightBytes);
				LevelChunkSection[] real = chunk.getSections();
				byte[] realBytes = writeSections(real);
				sectionsRaw += realBytes.length;
				sections += deflate(realBytes);
				byte[] all = new byte[realBytes.length + lightBytes.length + heightBytes.length];
				System.arraycopy(realBytes, 0, all, 0, realBytes.length);
				System.arraycopy(lightBytes, 0, all, realBytes.length, lightBytes.length);
				System.arraycopy(heightBytes, 0, all, realBytes.length + lightBytes.length, heightBytes.length);
				fullRaw += all.length;
				full += deflate(all);
				for (LevelChunkSection section : real) {
					bitsHistogram[Math.min(15, bitsOf(section))]++;
				}
				LevelChunkSection[] normal = normalize(level, chunk, false);
				byte[] normalBytes = writeSections(normal);
				enclosedRaw += normalBytes.length;
				enclosed += deflate(normalBytes);
				enclosedAll += deflate(writeSections(normalize(level, chunk, true)));
			}
		}
		StringBuilder bits = new StringBuilder();
		for (int i = 0; i < 16; i++) if (bitsHistogram[i] > 0) bits.append(i).append("b:").append(bitsHistogram[i]).append(' ');
		return String.format("%d chunks, per chunk compressed (raw): sections+light+heightmaps together %d (%d) | block sections %d (%d) | light %d (%d) | heightmaps %d (%d)%n"
				+ "light arrays %d: all-zero %d, all-15 %d%n"
				+ "sections with enclosed opaque blocks made stone: %d (%d) = %.1f%% of sections; enclosed air too: %d%n"
				+ "bits per block entry: %s",
			chunks, full / chunks, fullRaw / chunks, sections / chunks, sectionsRaw / chunks, light / chunks, lightRaw / chunks, height / chunks, heightRaw / chunks,
			lightArrays, lightZero, lightFull,
			enclosed / chunks, enclosedRaw / chunks, 100.0 * enclosed / sections, enclosedAll / chunks, bits);
	}

	private static void census(ServerLevel level, int centerX, int centerZ, int radius) {
		it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<net.minecraft.world.level.block.Block> enclosedTypes = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
		long enclosedTotal = 0, enclosedHigh = 0;
		for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
			for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
				LevelChunk chunk = LoadedChunks.get(level, cx, cz);
				if (chunk == null) continue;
				LevelChunkSection[] real = chunk.getSections();
				for (int i = 0; i < real.length; i++) {
					if (real[i].hasOnlyAir()) continue;
					int baseY = SectionPos.sectionToBlockCoord(level.getMinSectionY() + i);
					for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
						BlockState state = real[i].getBlockState(x, y, z);
						if (!state.isSolidRender()) continue;
						int bx = chunk.getPos().getMinBlockX() + x, by = baseY + y, bz = chunk.getPos().getMinBlockZ() + z;
						if (opaque(level, bx + 1, by, bz) && opaque(level, bx - 1, by, bz) && opaque(level, bx, by + 1, bz)
							&& opaque(level, bx, by - 1, bz) && opaque(level, bx, by, bz + 1) && opaque(level, bx, by, bz - 1)) {
							enclosedTypes.addTo(state.getBlock(), 1);
							enclosedTotal++;
							if (by >= 256) enclosedHigh++;
						}
					}
				}
			}
		}
		StringBuilder types = new StringBuilder();
		final long total = Math.max(1, enclosedTotal);
		enclosedTypes.object2LongEntrySet().stream().sorted((a, b) -> Long.compare(b.getLongValue(), a.getLongValue())).limit(40)
			.forEach(e -> types.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath())
				.append(String.format(" %.2f%%, ", 100.0 * e.getLongValue() / total)));
		PandaOcclusion.LOGGER.info("enclosed types ({} blocks, {} at y>=256): {}", enclosedTotal, enclosedHigh, types);
	}

	private static boolean allSame(byte[] data, byte value) {
		for (byte b : data) if (b != value) return false;
		return true;
	}

	private static int bitsOf(LevelChunkSection section) {
		int size = section.getStates().getSerializedSize();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		section.getStates().write(buf);
		return buf.getUnsignedByte(0);
	}

	private static LevelChunkSection[] normalize(ServerLevel level, LevelChunk chunk, boolean airToo) {
		LevelChunkSection[] real = chunk.getSections();
		LevelChunkSection[] out = new LevelChunkSection[real.length];
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < real.length; i++) {
			LevelChunkSection section = real[i];
			if (section.hasOnlyAir()) { out[i] = section; continue; }
			int sectionY = level.getMinSectionY() + i;
			BlockState filler = ChunkPacketContext.filler(level, sectionY);
			if (filler.isAir()) filler = sectionY < 0 ? net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState() : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			PalettedContainer<BlockState> states = section.getStates().copy();
			int baseY = SectionPos.sectionToBlockCoord(sectionY);
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state == filler) continue;
						if (!airToo && !state.isSolidRender()) continue;
						if (!state.isSolidRender() && !state.isAir()) continue;
						int bx = baseX + x, by = baseY + y, bz = baseZ + z;
						if (opaque(level, bx + 1, by, bz) && opaque(level, bx - 1, by, bz) && opaque(level, bx, by + 1, bz)
							&& opaque(level, bx, by - 1, bz) && opaque(level, bx, by, bz + 1) && opaque(level, bx, by, bz - 1)) {
							states.getAndSetUnchecked(x, y, z, filler);
						}
					}
				}
			}
			out[i] = new LevelChunkSection(states, section.getBiomes());
		}
		return out;
	}

	private static boolean opaque(ServerLevel level, int x, int y, int z) {
		LevelChunk chunk = LoadedChunks.get(level, x >> 4, z >> 4);
		if (chunk == null || level.isOutsideBuildHeight(y)) return false;
		return chunk.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isSolidRender();
	}

	private static byte[] writeSections(LevelChunkSection[] sections) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		for (LevelChunkSection section : sections) section.write(buf);
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		return bytes;
	}

	private static byte[] encode(ServerLevel level, java.util.function.Consumer<RegistryFriendlyByteBuf> writer) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		writer.accept(buf);
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		return bytes;
	}

	private static int deflate(byte[] input) {
		Deflater deflater = new Deflater();
		deflater.setInput(input);
		deflater.finish();
		byte[] out = new byte[input.length + 64];
		int total = 0;
		while (!deflater.finished()) total += deflater.deflate(out);
		deflater.end();
		return total;
	}
}
