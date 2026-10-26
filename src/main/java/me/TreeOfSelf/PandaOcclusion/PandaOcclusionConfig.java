package me.TreeOfSelf.PandaOcclusion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

public class PandaOcclusionConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("PandaOcclusion.json").toFile();

	public static ConfigData config = new ConfigData();

	public static class ConfigData {
		public boolean enabled = true;
		// dimensions where underground sections are hidden until seen
		public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		public boolean hideEntities = true;
		public boolean playerLineOfSight = true;
		// players closer than this are always shown, like vanilla (0 = off)
		public double playerVanillaDistance = 32;
		public double entityVanillaDistance = 0;
		public int lineOfSightInterval = 2;
		public int lineOfSightHoldTicks = 10;
		public double lineOfSightMargin = 1.0;
		public int lineOfSightLeadTicks = 4;
		public double lineOfSightReach = 1.5;
		public boolean entityLineOfSight = true;
		public int entityLineOfSightInterval = 4;
		public double entityLineOfSightRange = 128;
		public boolean hideParticles = true;
		public boolean hideSounds = false;
		public boolean hiddenSounds = true;
		public boolean hiddenBlockSounds = true;
		public boolean revealAroundAim = true;
		// chunk columns revealed per player per tick, nearest first
		public int maxRevealsPerTick = 16;
		public String revealMode = "column";
		// a column resend reloads the whole chunk on a Bedrock client
		public String bedrockRevealMode = "blocks";
		// Geyser sends two entries per block; 0 = no limit
		public int bedrockBlocksPerTick = 16384;
		public int bedrockBlocksPerTickTotal = 65536;
		// auto mode: above this many changed blocks the whole column is resent
		public int autoColumnBlocks = 400;
		public List<String> keepHiddenUntilExposed = new ArrayList<>(List.of(
			"copper_ore", "deepslate_copper_ore", "raw_copper_block", "diamond_ore", "deepslate_diamond_ore", "gold_ore",
			"deepslate_gold_ore", "iron_ore", "deepslate_iron_ore", "raw_iron_block", "lapis_ore", "deepslate_lapis_ore",
			"redstone_ore", "deepslate_redstone_ore", "emerald_ore", "deepslate_emerald_ore", "coal_ore", "deepslate_coal_ore",
			"nether_quartz_ore", "nether_gold_ore", "ancient_debris"));
		public boolean farCulling = true;
		public int refreshWalkTicks = 20;
		public double lookaheadMinSpeed = 0.5;
		public List<Integer> lookaheadBlocks = new ArrayList<>();
		public boolean generousReveal = false;
		public boolean skipKnownLight = true;
		public boolean shareHeightmaps = true;
		public boolean caveLineOfSight = true;
		public boolean hideSealedPockets = true;
		public boolean revealBehindDig = true;
		public boolean hideUnseenOres = true;
		public double unseenOreDistance = 64;
		public List<String> unseenOreBlocks = new ArrayList<>(List.of(
			"coal_ore", "deepslate_coal_ore", "copper_ore", "deepslate_copper_ore", "iron_ore", "deepslate_iron_ore",
			"gold_ore", "deepslate_gold_ore", "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore",
			"diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore", "nether_gold_ore",
			"nether_quartz_ore", "ancient_debris"));
		public double fineSightDistance = 48;
		public boolean hideSurfaceWhenDeep = true;
		public boolean bedrockHideSurfaceWhenDeep = false;
		public boolean alwaysSendSurface = false;
		public boolean hiddenSkyAsStone = false;
		public boolean hiddenAsAir = false;
		public double deepSurfaceDistance = 12;
		public int deepPathSections = 4;
		public boolean debugDeep = false;
		public boolean debugReveals = false;
		public double sightMargin = 2;
		public double sightMarginVertical = 1.5;
		public boolean thirdPersonCamera = true;
		public int sightAheadBlocks = 4;
		public double prefetchDistance = 6;
		public double prefetchVertical = 4;
		public int prefetchLeadTicks = 40;
		public double prefetchMaxDistance = 48;
		public int prefetchWalkTicks = 10;
		public int prefetchColumnsPerTick = 2;
		public boolean rayPruning = false;
		public double rewalkDistance = 2.0;
		public double fastSpeed = 0.5;
		public double fastRewalkDistance = 8.0;
		public double fastEntityNearDistance = 32;
		public int fastEntityFarInterval = 3;
		// read at startup
		public int walkThreads = 4;
		public boolean spectatorsSeeAll = true;
		public List<String> bypassPlayers = new ArrayList<>();
	}

	private static Set<Block> keepHidden = Set.of();

	public static boolean pocketsOn() {
		return config.hideSealedPockets || config.hideUnseenOres;
	}

	public static Set<Block> keepHidden() {
		return keepHidden;
	}

	public static void load() {
		if (CONFIG_FILE.exists()) {
			try (FileReader reader = new FileReader(CONFIG_FILE)) {
				ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		Set<Block> blocks = new HashSet<>();
		for (String id : config.keepHiddenUntilExposed) {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier != null) {
				BuiltInRegistries.BLOCK.getOptional(identifier).ifPresent(blocks::add);
			}
		}
		keepHidden = blocks;
		Set<Block> ores = new HashSet<>();
		for (String id : config.unseenOreBlocks) {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier != null) {
				BuiltInRegistries.BLOCK.getOptional(identifier).ifPresent(ores::add);
			}
		}
		OreTrace.set(config.hideUnseenOres ? ores : Set.of());
		save();
	}

	public static void save() {
		try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
