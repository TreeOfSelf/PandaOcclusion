package me.TreeOfSelf.PandaOcclusion;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class HiddenSounds {
	// server thread only, so the unsynchronized kind
	private static final RandomSource RANDOM = new net.minecraft.world.level.levelgen.SingleThreadedRandomSource(net.minecraft.world.level.levelgen.RandomSupport.generateUniqueSeed());
	private static ServerPlayer blockListener;
	private static Entity tickingEntity;
	private static ItemStack brokenItem = ItemStack.EMPTY;
	private static final Set<Block> BROKEN = new ReferenceOpenHashSet<>();
	private static final Int2ObjectOpenHashMap<EntitySounds> ENTITIES = new Int2ObjectOpenHashMap<>();
	private static final List<Delayed> DELAYED = new ArrayList<>();
	private static MinecraftServer server;
	private static final java.lang.management.ThreadMXBean THREAD_CPU = java.lang.management.ManagementFactory.getThreadMXBean();

	private static final double[] MINECART_LOOP = {1.935488};
	private static final double[] GUARDIAN_LOOP = {2.004188};
	private static final double[] BEE_LOOP = {5.939833, 4.208833, 5.110250, 5.339500, 4.211750, 4.211750};
	private static final double[] BEE_AGGRESSIVE_LOOP = {3.846667, 4.873771, 3.846667};

	private record Delayed(ServerPlayer player, Packet<?> packet, long tick) {}

	private static final class EntitySounds {
		long lastSeenTick;
		boolean touchedGround;
		int attackTicks;
		int idleStartTick;
		boolean creepy;
		long lastStareTick = Long.MIN_VALUE / 2;
		float minecartPitch;
		final Int2LongOpenHashMap loopEnds = new Int2LongOpenHashMap();
	}

	static boolean active() {
		PandaOcclusionConfig.ConfigData config = PandaOcclusionConfig.config;
		return config.enabled && config.hiddenSounds && !config.hideSounds && server != null && server.isSameThread();
	}

	static void tick(MinecraftServer minecraftServer) {
		server = minecraftServer;
		long now = minecraftServer.getTickCount();
		if (!DELAYED.isEmpty()) {
			DELAYED.removeIf(d -> {
				if (d.tick > now) return false;
				if (!d.player.hasDisconnected()) d.player.connection.send(d.packet);
				return true;
			});
		}
		if (now % 1200 == 0) ENTITIES.int2ObjectEntrySet().removeIf(e -> now - e.getValue().lastSeenTick > 200);
	}

	private static void send(ServerPlayer player, SoundEvent sound, SoundSource source, double x, double y, double z, float volume, float pitch, long seed, boolean distanceDelay) {
		Packet<?> packet = new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, x, y, z, volume, pitch, seed);
		Occlusion.STATS.hiddenSounds++;
		// the client delays these by distance (sound speed), from 10 blocks on
		double distanceSqr = distanceDelay ? player.getEyePosition().distanceToSqr(x, y, z) : 0;
		if (distanceSqr > 100.0) {
			DELAYED.add(new Delayed(player, packet, server.getTickCount() + (int) (Math.sqrt(distanceSqr) / 40.0 * 20.0)));
		} else {
			player.connection.send(packet);
		}
	}

	private static void send(ServerPlayer player, SoundEvent sound, SoundSource source, double x, double y, double z, float volume, float pitch) {
		send(player, sound, source, x, y, z, volume, pitch, RANDOM.nextLong(), false);
	}

	private static void sendAtBlock(ServerPlayer player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay) {
		send(player, sound, source, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, volume, pitch, RANDOM.nextLong(), distanceDelay);
	}

	static List<ServerPlayer> hiddenListeners(Entity entity, double range) {
		if (!(entity.level() instanceof ServerLevel level)) return List.of();
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) return List.of();
		ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(entity.getId());
		if (tracked == null) return List.of();
		List<ServerPlayer> listeners = null;
		for (ServerPlayer player : players) {
			if (player == entity || player.distanceToSqr(entity) > range * range) continue;
			if (!((HiddenTracking) tracked).panda$hiddenByOcclusion(player)) continue;
			if (listeners == null) listeners = new ArrayList<>(2);
			listeners.add(player);
		}
		return listeners == null ? List.of() : listeners;
	}

	private static List<ServerPlayer> hiddenListeners(Entity entity, SoundEvent sound, float volume) {
		return hiddenListeners(entity, sound.getRange(volume));
	}

	public static void localSound(Level level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay) {
		if (!(level instanceof ServerLevel) || !active()) return;
		if (blockListener != null) {
			if (blockListener.level() == level && blockListener.distanceToSqr(x, y, z) <= Mth.square(sound.getRange(volume))) {
				send(blockListener, sound, source, x, y, z, volume, pitch, RANDOM.nextLong(), distanceDelay);
			}
			return;
		}
		Entity entity = tickingEntity;
		if (entity == null || entity.level() != level) return;
		for (ServerPlayer player : hiddenListeners(entity, sound, volume)) send(player, sound, source, x, y, z, volume, pitch, RANDOM.nextLong(), distanceDelay);
	}

	public static void localEntitySound(Level level, Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {
		if (!(level instanceof ServerLevel) || entity == null || blockListener != null || !active() || entity.isSilent()) return;
		for (ServerPlayer player : hiddenListeners(entity, sound, volume)) send(player, sound, source, entity.getX(), entity.getY(), entity.getZ(), volume, pitch);
	}

	public static void playerSound(Level level, SoundEvent sound, SoundSource source, float volume, float pitch) {
		if (blockListener == null || blockListener.level() != level || !active()) return;
		Occlusion.STATS.hiddenSounds++;
		blockListener.connection.send(new ClientboundSoundEntityPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, blockListener, volume, pitch, RANDOM.nextLong()));
	}

	public static void startTicking(Entity entity) {
		tickingEntity = entity;
	}

	public static void brokenItem(ItemStack stack) {
		brokenItem = stack;
	}

	static void tickPlayer(ServerPlayer player, PlayerOcclusion state) {
		if (!active() || !state.enabled || state.bypass || state.view == null) return;
		// CPU time, not wall time
		long start = THREAD_CPU.getCurrentThreadCpuTime();
		ServerLevel level = state.level;
		ServerChunkCache chunks = level.getChunkSource();
		int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
		if (hasHiddenNear(state, px, py, pz)) {
			if (PandaOcclusionConfig.config.hiddenBlockSounds && soundSections(state, chunks, px, py, pz) && nearLoaded(state, chunks, px, pz)) {
				blockListener = player;
				try {
					sampleSections(state, level, px, py, pz);
				} finally {
					blockListener = null;
				}
			}
			blockEntities(player, state, level, chunks);
		}
		Occlusion.STATS.hiddenSoundNanos += THREAD_CPU.getCurrentThreadCpuTime() - start;
	}

	private static boolean hasHiddenNear(PlayerOcclusion state, int px, int py, int pz) {
		int sy = SectionPos.blockToSectionCoord(py) - state.minSectionY;
		long band = 0L;
		for (int y = sy - 2; y <= sy + 2; y++) {
			if (y >= 0 && y < state.height) band |= 1L << y;
		}
		if (band == 0) return false;
		int sx = SectionPos.blockToSectionCoord(px), sz = SectionPos.blockToSectionCoord(pz);
		for (int x = sx - 2; x <= sx + 2; x++) {
			for (int z = sz - 2; z <= sz + 2; z++) {
				long key = net.minecraft.world.level.ChunkPos.pack(x, z);
				long revealed = state.revealed.get(key);
				if ((~revealed & band) != 0) return true;
				for (long left = band; left != 0 && revealed != -1L; left &= left - 1) {
					if (state.pocketAt(key, Long.numberOfTrailingZeros(left)) != null) return true;
				}
			}
		}
		return false;
	}

	private static boolean nearLoaded(PlayerOcclusion state, ServerChunkCache chunks, int px, int pz) {
		long chunk = net.minecraft.world.level.ChunkPos.pack(px >> 4, pz >> 4);
		long now = server.getTickCount();
		if (chunk == state.soundLoadedChunk && now - state.soundLoadedTick < 20) return true;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (chunks.getChunkNow((px >> 4) + dx, (pz >> 4) + dz) == null) {
					state.soundLoadedChunk = Long.MIN_VALUE;
					return false;
				}
			}
		}
		state.soundLoadedChunk = chunk;
		state.soundLoadedTick = now;
		return true;
	}

	private static boolean soundSections(PlayerOcclusion state, ServerChunkCache chunks, int px, int py, int pz) {
		long center = SectionPos.asLong(px >> 4, py >> 4, pz >> 4);
		long now = server.getTickCount();
		if (center == state.soundSectionsCenter && now - state.soundSectionsTick < 20) return state.soundSectionsAny;
		state.soundSectionsCenter = center;
		state.soundSectionsTick = now;
		java.util.Arrays.fill(state.soundMass, -1L);
		return state.soundSectionsAny = findSoundSections(state, chunks, px, py, pz);
	}

	private static boolean findSoundSections(PlayerOcclusion state, ServerChunkCache chunks, int px, int py, int pz) {
		int ox = SectionPos.blockToSectionCoord(px) - 2, oy = SectionPos.blockToSectionCoord(py) - 2, oz = SectionPos.blockToSectionCoord(pz) - 2;
		LevelChunkSection[] sections = state.soundSections;
		boolean any = false;
		for (int rx = 0; rx < 5; rx++) {
			for (int rz = 0; rz < 5; rz++) {
				int column = rx * 25 + rz;
				long key = net.minecraft.world.level.ChunkPos.pack(ox + rx, oz + rz);
				long revealed = state.revealed.get(key);
				LevelChunk chunk = revealed == -1L ? null : chunks.getChunkNow(ox + rx, oz + rz);
				for (int ry = 0; ry < 5; ry++) {
					int index = oy + ry - state.minSectionY;
					LevelChunkSection section = null;
					if (chunk != null && index >= 0 && index < state.height && ((revealed >> index & 1L) == 0 || state.pocketAt(key, index) != null)) {
						section = chunk.getSection(index);
						if (!mayPlay(section)) section = null;
					}
					sections[column + ry * 5] = section;
					any |= section != null;
				}
			}
		}
		return any;
	}

	private static boolean mayPlay(LevelChunkSection section) {
		if (section.hasOnlyAir()) return false;
		SectionVisHolder holder = (SectionVisHolder) section;
		byte mayPlay = holder.panda$mayPlay();
		if (mayPlay == -1) {
			mayPlay = section.maybeHas(HiddenSounds::mayPlay) ? (byte) 1 : (byte) 0;
			holder.panda$setMayPlay(mayPlay);
		}
		return mayPlay == 1;
	}

	private static final Set<Class<?>> PARTICLES_ONLY = Set.of(
		net.minecraft.world.level.block.FallingBlock.class, BrushableBlock.class, net.minecraft.world.level.block.BeehiveBlock.class,
		net.minecraft.world.level.block.BrewingStandBlock.class, net.minecraft.world.level.block.CryingObsidianBlock.class,
		net.minecraft.world.level.block.EnchantingTableBlock.class, net.minecraft.world.level.block.EnderChestBlock.class,
		net.minecraft.world.level.block.EndGatewayBlock.class, net.minecraft.world.level.block.EndPortalBlock.class,
		net.minecraft.world.level.block.EndRodBlock.class, net.minecraft.world.level.block.LeverBlock.class,
		net.minecraft.world.level.block.LightningRodBlock.class, net.minecraft.world.level.block.MyceliumBlock.class,
		net.minecraft.world.level.block.PointedDripstoneBlock.class, net.minecraft.world.level.block.RedStoneOreBlock.class,
		net.minecraft.world.level.block.RedstoneTorchBlock.class, net.minecraft.world.level.block.RedstoneWallTorchBlock.class,
		net.minecraft.world.level.block.RedstoneWireBlock.class, net.minecraft.world.level.block.RepeaterBlock.class,
		net.minecraft.world.level.block.SculkSensorBlock.class, net.minecraft.world.level.block.SporeBlossomBlock.class,
		net.minecraft.world.level.block.TorchBlock.class, net.minecraft.world.level.block.WallTorchBlock.class,
		net.minecraft.world.level.block.WetSpongeBlock.class, net.minecraft.world.level.block.WitherRoseBlock.class);

	private static final ClassValue<Boolean> ANIMATES = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				Class<?> declaring = type.getMethod("animateTick", BlockState.class, Level.class, BlockPos.class, RandomSource.class).getDeclaringClass();
				return declaring != Block.class && !PARTICLES_ONLY.contains(declaring);
			} catch (NoSuchMethodException e) {
				return true;
			}
		}
	};

	public static boolean mayPlay(BlockState block) {
		byte mayPlay = ((MayPlayHolder) block).panda$mayPlay();
		if (mayPlay == -1) {
			mayPlay = workOutMayPlay(block) ? (byte) 1 : (byte) 0;
			((MayPlayHolder) block).panda$setMayPlay(mayPlay);
		}
		return mayPlay == 1;
	}

	private static boolean workOutMayPlay(BlockState block) {
		FluidState fluid = block.getFluidState();
		if (!fluid.isEmpty()) {
			if (!fluid.is(net.minecraft.tags.FluidTags.WATER)) return true;
			if (!fluid.isSource() && !fluid.getOptionalValue(net.minecraft.world.level.material.FlowingFluid.FALLING).orElse(false)) return true;
		}
		return ANIMATES.get(block.getBlock().getClass());
	}

	// the client's animateTick samples 667 blocks within 16 and 667 within 32 each tick
	private static final int[] RANGES = {16, 32};

	private static void sampleSections(PlayerOcclusion state, ServerLevel level, int px, int py, int pz) {
		Occlusion.STATS.hiddenSoundSamples += 1334;
		long here = BlockPos.asLong(px, py, pz);
		if (here != state.soundWeightsPos) {
			state.soundWeightsPos = here;
			java.util.Arrays.fill(state.soundMass, -1L);
			int[] player = {px, py, pz};
			for (int range = 0; range < RANGES.length; range++) {
				int r = RANGES[range];
				for (int axis = 0; axis < 3; axis++) {
					for (int i = 0; i < 5; i++) {
						int lo = ((player[axis] >> 4) - 2 + i << 4) - player[axis], sum = 0;
						int[] cumulative = state.soundWeights[range][axis][i];
						for (int k = 0; k < 16; k++) {
							int d = lo + k;
							if (d > -r && d < r) sum += r - Math.abs(d);
							cumulative[k] = sum;
						}
					}
				}
			}
		}
		int ox = (px >> 4) - 2, oy = (py >> 4) - 2, oz = (pz >> 4) - 2;
		BlockPos.MutableBlockPos pos = null;
		for (int index = 0; index < 125; index++) {
			LevelChunkSection section = state.soundSections[index];
			if (section == null) continue;
			int rx = index / 25, ry = index / 5 % 5, rz = index % 5;
			int bx = (ox + rx) << 4, by = (oy + ry) << 4, bz = (oz + rz) << 4;
			long[] bits = null;
			for (int range = 0; range < RANGES.length; range++) {
				int[] cx = state.soundWeights[range][0][rx], cy = state.soundWeights[range][1][ry], cz = state.soundWeights[range][2][rz];
				int wx = cx[15], wy = cy[15], wz = cz[15];
				if (wx == 0 || wy == 0 || wz == 0) continue;
				double total = (double) RANGES[range] * RANGES[range];
				int slot = range * 125 + index;
				long mass = state.soundMass[slot];
				if (mass == -1L) {
					if (bits == null) bits = soundBits(section);
					state.soundMass[slot] = mass = soundMass(bits, cx, cy, cz);
				}
				int count;
				if (mass != LAKE) {
					count = mass == 0 ? 0 : binomial(667, mass / (total * total * total));
				} else {
					count = binomial(667, wx / total * (wy / total) * (wz / total));
				}
				if (count == 0) continue;
				if (bits == null) bits = soundBits(section);
				if (pos == null) pos = new BlockPos.MutableBlockPos();
				if (mass != LAKE) {
					sampleSoundBlocks(state, level, section, bits, cx, cy, cz, mass, count, bx, by, bz, pos);
					continue;
				}
				for (int i = 0; i < count; i++) {
					int lx = offset(cx), ly = offset(cy), lz = offset(cz), bit = lx | lz << 4 | ly << 8;
					Occlusion.STATS.hiddenSoundHits++;
					if ((bits[bit >> 6] >>> bit & 1L) == 0) continue;
					sample(state, level, section, bx + lx, by + ly, bz + lz, pos);
				}
			}
		}
	}

	private static final long LAKE = -2L;

	private static long soundMass(long[] bits, int[] cx, int[] cy, int[] cz) {
		int soundBlocks = 0;
		for (long word : bits) soundBlocks += Long.bitCount(word);
		if (soundBlocks > MAX_LISTED) return LAKE;
		long mass = 0;
		for (int word = 0; word < 64; word++) {
			for (long rest = bits[word]; rest != 0; rest &= rest - 1) mass += blockWeight(word << 6 | Long.numberOfTrailingZeros(rest), cx, cy, cz);
		}
		return mass;
	}

	private static final int MAX_LISTED = 512;

	private static void sampleSoundBlocks(PlayerOcclusion state, ServerLevel level, LevelChunkSection section, long[] bits, int[] cx, int[] cy, int[] cz,
		long mass, int count, int bx, int by, int bz, BlockPos.MutableBlockPos pos) {
		for (int i = 0; i < count; i++) {
			Occlusion.STATS.hiddenSoundHits++;
			long pick = (long) ((nextBits() >>> 11) * 0x1.0p-53 * mass);
			search:
			for (int word = 0; word < 64; word++) {
				for (long rest = bits[word]; rest != 0; rest &= rest - 1) {
					int index = word << 6 | Long.numberOfTrailingZeros(rest);
					pick -= blockWeight(index, cx, cy, cz);
					if (pick < 0) {
						sample(state, level, section, bx + (index & 15), by + (index >> 8), bz + (index >> 4 & 15), pos);
						break search;
					}
				}
			}
		}
	}

	private static long blockWeight(int index, int[] cx, int[] cy, int[] cz) {
		int lx = index & 15, lz = index >> 4 & 15, ly = index >> 8;
		return (long) (cx[lx] - (lx == 0 ? 0 : cx[lx - 1])) * (cy[ly] - (ly == 0 ? 0 : cy[ly - 1])) * (cz[lz] - (lz == 0 ? 0 : cz[lz - 1]));
	}

	private static long[] soundBits(LevelChunkSection section) {
		SectionVisHolder holder = (SectionVisHolder) section;
		long[] bits = holder.panda$soundBits();
		if (bits == null) {
			bits = new long[64];
			for (int index = 0; index < 4096; index++) {
				if (mayPlay(section.getBlockState(index & 15, index >> 8, index >> 4 & 15))) bits[index >> 6] |= 1L << index;
			}
			holder.panda$setSoundBits(bits);
		}
		return bits;
	}

	private static int offset(int[] cumulative) {
		int pick = (int) (((nextBits() >>> 32) * cumulative[15]) >>> 32);
		int k = 0;
		while (cumulative[k] <= pick) k++;
		return k;
	}

	// binomial by inversion: expected steps n * p + 1
	private static int binomial(int n, double p) {
		if (p <= 0.0) return 0;
		if (p >= 1.0) return n;
		double q = 1.0 - p, ratio = p / q, a = (n + 1) * ratio;
		double prob = Math.pow(q, n), u = (nextBits() >>> 11) * 0x1.0p-53;
		int k = 0;
		while (u > prob && k < n) {
			u -= prob;
			k++;
			prob *= a / k - ratio;
		}
		return k;
	}

	private static long sampleSeed = net.minecraft.world.level.levelgen.RandomSupport.generateUniqueSeed();

	private static long nextBits() {
		long z = sampleSeed += 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static void sample(PlayerOcclusion state, ServerLevel level, LevelChunkSection section, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		BlockState block = section.getBlockState(x & 15, y & 15, z & 15);
		if (!state.isBlockHidden(x, y, z) || shellCopied(state, section, net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4), x, y, z)) return;
		if (BROKEN.contains(block.getBlock())) return;
		pos.set(x, y, z);
		try {
			block.getBlock().animateTick(block, level, pos, RANDOM);
			FluidState fluid = block.getFluidState();
			if (!fluid.isEmpty()) fluid.animateTick(level, pos, RANDOM);
		} catch (Throwable e) {
			BROKEN.add(block.getBlock());
			PandaOcclusion.LOGGER.warn("Hidden block sounds: skipping {} from now on, its client code fails on the server: {}", block.getBlock(), e.toString());
		}
	}

	private static boolean shellCopied(PlayerOcclusion state, LevelChunkSection section, long chunkKey, int x, int y, int z) {
		int lx = x & 15, ly = y & 15, lz = z & 15;
		if (lx != 0 && lx != 15 && ly != 0 && ly != 15 && lz != 0 && lz != 15) return false;
		int index = SectionPos.blockToSectionCoord(y) - state.minSectionY;
		if ((state.shellSent.get(chunkKey) >> index & 1L) == 0) return false;
		return ly == 0 && SectionVis.sealed(section, 0) || ly == 15 && SectionVis.sealed(section, 1)
			|| lz == 0 && SectionVis.sealed(section, 2) || lz == 15 && SectionVis.sealed(section, 3)
			|| lx == 0 && SectionVis.sealed(section, 4) || lx == 15 && SectionVis.sealed(section, 5);
	}

	private static void blockEntities(ServerPlayer player, PlayerOcclusion state, ServerLevel level, ServerChunkCache chunks) {
		findSoundEntities(player, state, chunks);
		if (state.soundEntities.isEmpty()) return;
		Vec3 eye = player.getEyePosition();
		for (BlockEntity blockEntity : state.soundEntities) {
		BlockPos pos = blockEntity.getBlockPos();
		if (blockEntity.isRemoved() || pos.distToCenterSqr(eye) > 256.0 || !state.isBlockHidden(pos.getX(), pos.getY(), pos.getZ())) continue;
		switch (blockEntity) {
			case VaultBlockEntity vault -> {
				if (vault.getSharedData().hasDisplayItem() && RANDOM.nextFloat() <= 0.02F) {
					sendAtBlock(player, pos, SoundEvents.VAULT_AMBIENT, SoundSource.BLOCKS, RANDOM.nextFloat() * 0.25F + 0.75F, RANDOM.nextFloat() + 0.5F, false);
				}
			}
			case TrialSpawnerBlockEntity spawner -> {
				TrialSpawner trialSpawner = spawner.getTrialSpawner();
				if (trialSpawner.getState().isCapableOfSpawning() && RANDOM.nextFloat() <= 0.02F) {
					SoundEvent sound = blockEntity.getBlockState().getOptionalValue(BlockStateProperties.OMINOUS).orElse(false)
						? SoundEvents.TRIAL_SPAWNER_AMBIENT_OMINOUS : SoundEvents.TRIAL_SPAWNER_AMBIENT;
					sendAtBlock(player, pos, sound, SoundSource.BLOCKS, RANDOM.nextFloat() * 0.25F + 0.75F, RANDOM.nextFloat() + 0.5F, false);
				}
			}
			case PotentSulfurBlockEntity sulfur -> {
				PotentSulfurState sulfurState = blockEntity.getBlockState().getOptionalValue(net.minecraft.world.level.block.PotentSulfurBlock.STATE).orElse(null);
				SoundEvent sound = sulfurState == PotentSulfurState.ERUPTING ? SoundEvents.GEYSER_ERUPTION_ACTIVE
					: sulfurState == PotentSulfurState.CONTINUOUS ? SoundEvents.GEYSER_CONTINUOUS_ACTIVE : null;
				if (sound != null && (level.getGameTime() - sulfur.eruptionTick) % 40L == 0L) {
					BlockPos source = PotentSulfurBlockEntity.findNoxiousGasSourceBlock(level, pos);
					if (source != null) sendAtBlock(player, source, sound, SoundSource.BLOCKS, 1.0F, 1.0F, false);
				}
			}
			default -> {}
		}
		}
	}

	private static void findSoundEntities(ServerPlayer player, PlayerOcclusion state, ServerChunkCache chunks) {
		int cx = player.getBlockX() >> 4, cz = player.getBlockZ() >> 4;
		long chunkKey = net.minecraft.world.level.ChunkPos.pack(cx, cz);
		long now = server.getTickCount();
		if (chunkKey == state.soundEntitiesChunk && now - state.soundEntitiesTick < 20) return;
		state.soundEntitiesChunk = chunkKey;
		state.soundEntitiesTick = now;
		state.soundEntities.clear();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				LevelChunk chunk = chunks.getChunkNow(cx + dx, cz + dz);
				if (chunk == null) continue;
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof VaultBlockEntity || blockEntity instanceof TrialSpawnerBlockEntity || blockEntity instanceof PotentSulfurBlockEntity) {
						state.soundEntities.add(blockEntity);
					}
				}
			}
		}
	}

	static Packet<?> levelEvent(ServerPlayer player, ClientboundLevelEventPacket packet) {
		if (!active()) return null;
		BlockPos pos = packet.getPos();
		int data = packet.getData();
		switch (packet.getType()) {
			case 1000, 1001, 1002, 1004, 1009, 1010, 1011, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1024, 1025, 1026, 1027,
				1029, 1030, 1031, 1032, 1033, 1034, 1035, 1039, 1040, 1041, 1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050,
				1051, 1052, 1053, 1054, 3001 -> {
				return packet;
			}
			case 1500 -> sendAtBlock(player, pos, data > 0 ? SoundEvents.COMPOSTER_FILL_SUCCESS : SoundEvents.COMPOSTER_FILL, SoundSource.BLOCKS, 1.0F, 1.0F, false);
			case 1501 -> sendAtBlock(player, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F + (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.8F, false);
			case 1502 -> sendAtBlock(player, pos, SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.BLOCKS, 0.5F, 2.6F + (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.8F, false);
			case 1503 -> sendAtBlock(player, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 1.0F, false);
			case 1505 -> sendAtBlock(player, pos, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0F, 1.0F, false);
			case 2001 -> {
				BlockState state = Block.stateById(data);
				if (!state.isAir()) {
					SoundType soundType = state.getSoundType();
					sendAtBlock(player, pos, soundType.getBreakSound(), SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F, false);
				}
			}
			case 2006 -> {
				if (data == 1) sendAtBlock(player, pos, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1.0F, RANDOM.nextFloat() * 0.1F + 0.9F, false);
			}
			case 3000 -> sendAtBlock(player, pos, SoundEvents.END_GATEWAY_SPAWN, SoundSource.BLOCKS, 10.0F, (1.0F + (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F) * 0.7F, false);
			case 3006 -> {
				int count = data >> 6;
				if (count == 0) {
					sendAtBlock(player, pos, SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.BLOCKS, 1.0F, 1.0F, false);
				} else if (RANDOM.nextFloat() < 0.3F + count * 0.1F) {
					float volume = 0.15F + 0.02F * count * count * RANDOM.nextFloat();
					float pitch = 0.4F + 0.3F * count * RANDOM.nextFloat();
					sendAtBlock(player, pos, SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.BLOCKS, volume, pitch, false);
				}
			}
			case 3007 -> {
				BlockState state = player.level().getBlockState(pos);
				if (!state.hasProperty(BlockStateProperties.WATERLOGGED) || !state.getValue(BlockStateProperties.WATERLOGGED)) {
					send(player, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.BLOCKS, pos.getX() + 0.5, pos.getY() + SculkShriekerBlock.TOP_Y, pos.getZ() + 0.5,
						2.0F, 0.6F + RANDOM.nextFloat() * 0.4F, RANDOM.nextLong(), false);
				}
			}
			case 3008 -> {
				if (Block.stateById(data).getBlock() instanceof BrushableBlock brushable) {
					sendAtBlock(player, pos, brushable.getBrushCompletedSound(), SoundSource.PLAYERS, 1.0F, 1.0F, false);
				}
			}
			case 3012 -> sendAtBlock(player, pos, SoundEvents.TRIAL_SPAWNER_SPAWN_MOB, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			case 3013, 3019 -> sendAtBlock(player, pos, SoundEvents.TRIAL_SPAWNER_DETECT_PLAYER, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			case 3014 -> sendAtBlock(player, pos, SoundEvents.TRIAL_SPAWNER_EJECT_ITEM, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			case 3015 -> {
				if (player.level().getBlockEntity(pos) instanceof VaultBlockEntity) {
					sendAtBlock(player, pos, SoundEvents.VAULT_ACTIVATE, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
				}
			}
			case 3016 -> sendAtBlock(player, pos, SoundEvents.VAULT_DEACTIVATE, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			case 3018 -> sendAtBlock(player, pos, SoundEvents.COBWEB_PLACE, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			case 3020 -> sendAtBlock(player, pos, SoundEvents.TRIAL_SPAWNER_OMINOUS_ACTIVATE, SoundSource.BLOCKS, data == 0 ? 0.3F : 1.0F,
				(RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			case 3021 -> sendAtBlock(player, pos, SoundEvents.TRIAL_SPAWNER_SPAWN_ITEM, SoundSource.BLOCKS, 1.0F, (RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.2F + 1.0F, true);
			default -> {}
		}
		return null;
	}

	public static void trackedPacket(Entity entity, Packet<?> packet) {
		if (!active()) return;
		switch (packet) {
			case ClientboundEntityEventPacket p -> entityEvent(entity, p.getEventId());
			case ClientboundTakeItemEntityPacket p -> {
				if (entity.isSilent()) return;
				if (entity instanceof ExperienceOrb) {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.1F)) {
						send(player, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, entity.getX(), entity.getY(), entity.getZ(), 0.1F,
							(RANDOM.nextFloat() - RANDOM.nextFloat()) * 0.35F + 0.9F);
					}
				} else {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.ITEM_PICKUP, 0.2F)) {
						send(player, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, entity.getX(), entity.getY(), entity.getZ(), 0.2F,
							(RANDOM.nextFloat() - RANDOM.nextFloat()) * 1.4F + 2.0F);
					}
				}
			}
			case net.minecraft.network.protocol.game.ClientboundBundlePacket p -> {
				for (Packet<?> sub : p.subPackets()) trackedPacket(entity, sub);
			}
			default -> {}
		}
	}

	private static void entityEvent(Entity entity, byte id) {
		switch (id) {
			case 35 -> {
				for (ServerPlayer player : hiddenListeners(entity, SoundEvents.TOTEM_USE, 1.0F)) {
					send(player, SoundEvents.TOTEM_USE, entity.getSoundSource(), entity.getX(), entity.getY(), entity.getZ(), 1.0F, 1.0F);
				}
			}
			case 47, 48, 49, 50, 51, 52, 65, 68 -> {
				ItemStack stack = brokenItem;
				brokenItem = ItemStack.EMPTY;
				if (!(entity instanceof LivingEntity) || stack.isEmpty() || entity.isSilent()) return;
				var breakSound = stack.get(DataComponents.BREAK_SOUND);
				if (breakSound == null) return;
				for (ServerPlayer player : hiddenListeners(entity, breakSound.value(), 0.8F)) {
					send(player, breakSound.value(), entity.getSoundSource(), entity.getX(), entity.getY(), entity.getZ(), 0.8F, 0.8F + RANDOM.nextFloat() * 0.4F);
				}
			}
			case 4 -> {
				if (entity instanceof EvokerFangs && !entity.isSilent()) {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.EVOKER_FANGS_ATTACK, 1.0F)) {
						send(player, SoundEvents.EVOKER_FANGS_ATTACK, entity.getSoundSource(), entity.getX(), entity.getY(), entity.getZ(), 1.0F, RANDOM.nextFloat() * 0.2F + 0.85F);
					}
				}
			}
			case 16 -> {
				if (entity instanceof ZombieVillager && !entity.isSilent()) {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.ZOMBIE_VILLAGER_CURE, 2.0F)) {
						send(player, SoundEvents.ZOMBIE_VILLAGER_CURE, entity.getSoundSource(), entity.getX(), entity.getEyeY(), entity.getZ(),
							1.0F + RANDOM.nextFloat(), RANDOM.nextFloat() * 0.7F + 0.3F);
					}
				}
			}
			case 32 -> {
				if (entity instanceof ArmorStand) {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.ARMOR_STAND_HIT, 0.3F)) {
						send(player, SoundEvents.ARMOR_STAND_HIT, entity.getSoundSource(), entity.getX(), entity.getY(), entity.getZ(), 0.3F, 1.0F);
					}
				}
			}
			case 63 -> {
				if (entity instanceof Sniffer && !entity.isSilent()) {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.SNIFFER_DIGGING, 1.0F)) {
						send(player, SoundEvents.SNIFFER_DIGGING, SoundSource.NEUTRAL, entity.getX(), entity.getY(), entity.getZ(), 1.0F, 1.0F);
					}
				}
			}
			case 64 -> {
				if (entity instanceof Armadillo) {
					for (ServerPlayer player : hiddenListeners(entity, SoundEvents.ARMADILLO_PEEK, 1.0F)) {
						send(player, SoundEvents.ARMADILLO_PEEK, entity.getSoundSource(), entity.getX(), entity.getY(), entity.getZ(), 1.0F, 1.0F);
					}
				}
			}
			default -> {}
		}
	}

	public static void afterTick(Entity entity) {
		tickingEntity = null;
		if (!active() || !(entity.level() instanceof ServerLevel level) || level.players().isEmpty()) return;
		switch (entity) {
			case Blaze blaze -> {
				if (blaze.isSilent()) return;
				for (ServerPlayer player : hiddenListeners(blaze, 32.0)) {
					if (RANDOM.nextInt(24) != 0) continue;
					float volume = 1.0F + RANDOM.nextFloat();
					if (player.distanceToSqr(blaze) <= Mth.square(SoundEvents.BLAZE_BURN.getRange(volume))) {
						send(player, SoundEvents.BLAZE_BURN, blaze.getSoundSource(), blaze.getX() + 0.5, blaze.getY() + 0.5, blaze.getZ() + 0.5, volume, RANDOM.nextFloat() * 0.7F + 0.3F);
					}
				}
			}
			case Phantom phantom -> {
				float anim = Mth.cos((phantom.getUniqueFlapTickOffset() + phantom.tickCount) * 7.448451F * (float) (Math.PI / 180.0) + (float) Math.PI);
				float next = Mth.cos((phantom.getUniqueFlapTickOffset() + phantom.tickCount + 1) * 7.448451F * (float) (Math.PI / 180.0) + (float) Math.PI);
				if (anim > 0.0F && next <= 0.0F) {
					localEntitySound(level, phantom, SoundEvents.PHANTOM_FLAP, phantom.getSoundSource(), 0.95F + RANDOM.nextFloat() * 0.05F, 0.95F + RANDOM.nextFloat() * 0.05F);
				}
			}
			case Warden warden -> {
				float anger = (float) warden.getClientAngerLevel() / AngerLevel.ANGRY.getMinimumAnger();
				int delay = 40 - Mth.floor(Mth.clamp(anger, 0.0F, 1.0F) * 30.0F);
				if (warden.tickCount % delay == 0 && !warden.isSilent()) {
					for (ServerPlayer player : hiddenListeners(warden, SoundEvents.WARDEN_HEARTBEAT, 5.0F)) {
						send(player, SoundEvents.WARDEN_HEARTBEAT, warden.getSoundSource(), warden.getX(), warden.getY(), warden.getZ(), 5.0F, warden.getVoicePitch());
					}
				}
			}
			case Guardian guardian -> guardian(level, guardian, state(guardian));
			case Illusioner illusioner -> {
				if (illusioner.isInvisible() && (illusioner.hurtTime == 1 || illusioner.tickCount % 1200 == 0)) {
					for (ServerPlayer player : hiddenListeners(illusioner, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F)) {
						send(player, SoundEvents.ILLUSIONER_MIRROR_MOVE, illusioner.getSoundSource(), illusioner.getX(), illusioner.getY(), illusioner.getZ(), 1.0F, 1.0F);
					}
				}
			}
			case Sniffer sniffer -> {
				if (sniffer.getState() == Sniffer.State.SEARCHING && sniffer.tickCount % 20 == 0) {
					localEntitySound(level, sniffer, SoundEvents.SNIFFER_SEARCHING, sniffer.getSoundSource(), 1.0F, 1.0F);
				}
			}
			case CopperGolem golem -> copperGolem(golem, state(golem));
			case Enderman enderman -> {
				EntitySounds sounds = state(enderman);
				boolean creepy = enderman.isCreepy();
				if (creepy && !sounds.creepy && enderman.hasBeenStaredAt() && enderman.tickCount >= sounds.lastStareTick + 400) {
					sounds.lastStareTick = enderman.tickCount;
					if (!enderman.isSilent()) {
						for (ServerPlayer player : hiddenListeners(enderman, SoundEvents.ENDERMAN_STARE, 2.5F)) {
							send(player, SoundEvents.ENDERMAN_STARE, enderman.getSoundSource(), enderman.getX(), enderman.getEyeY(), enderman.getZ(), 2.5F, 1.0F);
						}
					}
				}
				sounds.creepy = creepy;
			}
			case AbstractMinecart minecart -> minecart(level, minecart, state(minecart));
			case Bee bee -> bee(level, bee, state(bee));
			default -> {}
		}
	}

	private static EntitySounds state(Entity entity) {
		EntitySounds sounds = ENTITIES.computeIfAbsent(entity.getId(), id -> new EntitySounds());
		sounds.lastSeenTick = server.getTickCount();
		return sounds;
	}

	private static void guardian(ServerLevel level, Guardian guardian, EntitySounds sounds) {
		if (!guardian.isAlive()) return;
		if (!guardian.isInWater()) {
			Vec3 movement = guardian.getDeltaMovement();
			if (movement.y > 0.0 && sounds.touchedGround && !guardian.isSilent()) {
				SoundEvent flop = guardian.getFlopSound();
				for (ServerPlayer player : hiddenListeners(guardian, flop, 1.0F)) {
					send(player, flop, guardian.getSoundSource(), guardian.getX(), guardian.getY(), guardian.getZ(), 1.0F, 1.0F);
				}
			}
			sounds.touchedGround = movement.y < 0.0 && level.loadedAndEntityCanStandOn(guardian.blockPosition().below(), guardian);
		}
		sounds.attackTicks = guardian.hasActiveAttackTarget() ? Math.min(sounds.attackTicks + 1, guardian.getAttackDuration()) : 0;
		float scale = (float) sounds.attackTicks / guardian.getAttackDuration();
		float volume = guardian.isSilent() ? 0.0F : scale * scale;
		loop(guardian, sounds, SoundEvents.GUARDIAN_ATTACK, GUARDIAN_LOOP, SoundSource.HOSTILE, volume, 0.7F + 0.5F * scale, Double.MAX_VALUE, true);
	}

	private static void copperGolem(CopperGolem golem, EntitySounds sounds) {
		if (golem.isNoAi()) return;
		if (golem.getState() != CopperGolemState.IDLE) {
			sounds.idleStartTick = 0;
			return;
		}
		if (sounds.idleStartTick != golem.tickCount && sounds.idleStartTick == 0) {
			sounds.idleStartTick = golem.tickCount + RANDOM.nextInt(200, 240);
		}
		if (golem.tickCount == sounds.idleStartTick + 10) {
			sounds.idleStartTick = 0;
			if (!golem.isSilent()) {
				SoundEvent spin = golem.getSpinHeadSound();
				for (ServerPlayer player : hiddenListeners(golem, spin, 1.0F)) {
					send(player, spin, golem.getSoundSource(), golem.getX(), golem.getY(), golem.getZ(), 1.0F, 1.0F);
				}
			}
		}
	}

	private static void minecart(ServerLevel level, AbstractMinecart minecart, EntitySounds sounds) {
		float speed = (float) minecart.getDeltaMovement().horizontalDistance();
		boolean offRail = !minecart.isOnRails() && minecart.getBehavior() instanceof NewMinecartBehavior;
		float volume;
		if (speed >= 0.01F && level.tickRateManager().runsNormally() && !offRail) {
			sounds.minecartPitch = Mth.clamp(sounds.minecartPitch + 0.0025F, 0.0F, 1.0F);
			volume = Mth.lerp(Mth.clamp(speed, 0.0F, 0.5F), 0.0F, 0.7F);
		} else {
			sounds.minecartPitch = 0.0F;
			volume = 0.0F;
		}
		loop(minecart, sounds, SoundEvents.MINECART_RIDING, MINECART_LOOP, SoundSource.NEUTRAL, minecart.isSilent() ? 0.0F : volume, sounds.minecartPitch, 16.0, false);
	}

	private static void bee(ServerLevel level, Bee bee, EntitySounds sounds) {
		float speed = (float) bee.getDeltaMovement().horizontalDistance();
		float minPitch = bee.isBaby() ? 1.1F : 0.7F, maxPitch = bee.isBaby() ? 1.5F : 1.1F;
		float volume = 0.0F, pitch = 0.0F;
		if (speed >= 0.01F && !bee.isSilent()) {
			pitch = Mth.lerp(Mth.clamp(speed, minPitch, maxPitch), minPitch, maxPitch);
			volume = Mth.lerp(Mth.clamp(speed, 0.0F, 0.5F), 0.0F, 1.2F);
		}
		boolean angry = bee.isAngry();
		loop(bee, sounds, angry ? SoundEvents.BEE_LOOP_AGGRESSIVE : SoundEvents.BEE_LOOP, angry ? BEE_AGGRESSIVE_LOOP : BEE_LOOP, SoundSource.NEUTRAL,
			volume, pitch, angry ? 10.0 : 6.0, false);
	}

	private static void loop(Entity entity, EntitySounds sounds, SoundEvent sound, double[] lengths, SoundSource source, float volume, float pitch, double range, boolean noFalloff) {
		if (volume <= 0.0F) return;
		long now = server.getTickCount();
		for (ServerPlayer player : hiddenListeners(entity, range)) {
			if (sounds.loopEnds.get(player.getId()) > now) continue;
			long seed = RANDOM.nextLong();
			double seconds = lengths[RandomSource.create(seed).nextInt(lengths.length)] / Mth.clamp(pitch, 0.5F, 2.0F);
			sounds.loopEnds.put(player.getId(), now + Math.max(1, Math.round(seconds * 20.0)));
			Vec3 at = entity.position();
			if (noFalloff) {
				Vec3 eye = player.getEyePosition();
				Vec3 toward = at.subtract(eye);
				at = toward.lengthSqr() > 1.0 ? eye.add(toward.normalize()) : eye;
			}
			send(player, sound, source, at.x, at.y, at.z, volume, pitch, seed, false);
		}
	}
}
