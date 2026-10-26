package me.TreeOfSelf.PandaOcclusion;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;

public class PandaOcclusion implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("PandaOcclusion");

	@Override
	public void onInitialize() {
		PandaOcclusionConfig.load();
		ServerTickEvents.END_SERVER_TICK.register(Occlusion::tick);
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> LoadedChunks.load(level, chunk));
		ServerChunkEvents.CHUNK_UNLOAD.register(LoadedChunks::unload);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LoadedChunks.clear());
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Occlusion.HIDDEN_PLAYERS.remove(handler.getPlayer().getUUID()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("occlusion")
				.requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
				.then(Commands.literal("stats").executes(PandaOcclusion::stats))
				.then(Commands.literal("resetstats").executes(context -> {
					Occlusion.STATS.reset();
					context.getSource().sendSuccess(() -> Component.literal("Occlusion stats reset"), false);
					return 1;
				}))
				.then(Commands.literal("reload").executes(context -> {
					PandaOcclusionConfig.load();
					context.getSource().sendSuccess(() -> Component.literal("Occlusion config reloaded"), false);
					return 1;
				}))
				.then(Commands.literal("trace").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
					.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(context -> {
						ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
						net.minecraft.core.BlockPos pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(context, "pos");
						String text = Occlusion.trace(target, pos);
						context.getSource().sendSuccess(() -> Component.literal(text), false);
						return 1;
					}))))
				.then(Commands.literal("bench").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
					.executes(context -> {
						ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
						double ms = Occlusion.bench(target);
						String text = String.format("warm walk: %.3f ms", ms);
						context.getSource().sendSuccess(() -> Component.literal(text), false);
						return 1;
					})))
				.then(Commands.literal("analyze").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 16))
					.executes(context -> {
						net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayerOrException();
						String text = PacketAnalysis.analyze((net.minecraft.server.level.ServerLevel) player.level(), player.chunkPosition().x(), player.chunkPosition().z(),
							com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"));
						context.getSource().sendSuccess(() -> Component.literal(text), false);
						return 1;
					})))
				.then(Commands.literal("bypass").executes(context -> {
					net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayerOrException();
					boolean on = Occlusion.TOGGLED_BYPASS.add(player.getUUID()) || !Occlusion.TOGGLED_BYPASS.remove(player.getUUID());
					context.getSource().sendSuccess(() -> Component.literal(on ? "Occlusion off for you (you see everything)" : "Occlusion on for you again"), false);
					return 1;
				}))
				.then(Commands.literal("why").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
					.then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
					.executes(context -> {
						net.minecraft.server.level.ServerPlayer viewer = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
						net.minecraft.world.entity.Entity target = viewer.level().getEntity(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id"));
						String text = target == null ? "no such entity" : Occlusion.why(viewer, target);
						context.getSource().sendSuccess(() -> Component.literal(text), false);
						return 1;
					}))))
				.then(Commands.literal("section").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
					.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(context -> {
						ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
						net.minecraft.core.BlockPos pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(context, "pos");
						String text = Occlusion.sectionState(target, pos);
						context.getSource().sendSuccess(() -> Component.literal(text), false);
						return 1;
					}))))
				.then(Commands.literal("inspect").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
					.executes(context -> inspect(context, net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player")))))
		));
	}

	private static int stats(CommandContext<CommandSourceStack> context) {
		Occlusion.Stats s = Occlusion.STATS;
		long walks = s.walks.sum();
		long scans = s.visComputes.sum();
		String text = String.format(
			"walks %d (%d on server thread, avg %d sections, %.3f ms + scans) | section scans %d (avg %.1f us) | entity refresh %.1f ms total | cave ray checks %d (%d rays, %d steps; %d hidden; surface band %d checks, %d rays)%n"
				+ "chunks sent %d (held for a walk %d), sections hidden %d, block data saved %.1f MB, sections revealed %d (~%.1f MB), late reveals %d%n"
				+ "column resends %d (repeat %d, prefetch %d with %d sections, auto picked column %d, Bedrock block changes %d (%d reveals waited for the budget, longest %d ticks behind, %d held by the total), light skipped %.1f MB, digs at hidden air/ore %d, walks from a block being mined %d) | packets dropped/trimmed %d | player raycasts %d (avg %.1f us, %d skipped: nothing moved since they saw nothing)%n"
				+ "hidden sounds sent %d (block sampling %.1f ms server thread CPU total, %d of %d samples in hidden sections that can play)%n"
				+ "pockets: %d sections checked (%.1f ms total: grouping %.1f, rays %.1f; rays %d to the eye, %d to sight points, %d to cameras), %d real sections sent with %d blocks as filler, %d sections got blocks back, server thread %.1f ms total%n"
				+ "unseen ores: %d exposed ores checked (%.1f ms total, rays %.1f ms, %d rays), %d left hidden, %d sections past unseenOreDistance all stone, %d sections got ores back as block changes%n"
				+ "reveals by chunk distance: %s%nBedrock block changes by section (from the bottom): %s",
			walks, s.syncWalks.sum(), walks == 0 ? 0 : s.walkedSections.sum() / walks, walks == 0 ? 0 : s.walkNanos.sum() / 1e6 / walks,
			scans, scans == 0 ? 0 : s.visNanos.sum() / 1e3 / scans, s.entityRefreshNanos / 1e6, s.caveRayChecks.sum(), s.caveRays.sum(), s.caveRaySteps.sum(), s.hiddenChecks.sum(), s.surfaceRayChecks.sum(), s.surfaceRays.sum(),
			s.chunksSent, s.chunksHeld, s.sectionsHidden, s.bytesSaved / 1048576.0, s.reveals, s.revealBytes / 1048576.0, s.lateReveals,
			s.columnResends, s.repeatResends, s.prefetchResends, s.prefetchSections, s.autoColumns, s.bedrockBlockChanges, s.bedrockBlocksDeferred, s.bedrockMaxBehindTicks, s.bedrockTotalLimited, s.lightBytesSkipped / 1048576.0, s.digReveals, s.digWalks, s.packetsDropped, s.raycasts, s.raycasts == 0 ? 0 : s.raycastNanos / 1e3 / s.raycasts, s.raycastsSkipped,
			s.hiddenSounds, s.hiddenSoundNanos / 1e6, s.hiddenSoundHits, s.hiddenSoundSamples,
			s.pocketSections.sum(), s.pocketNanos.sum() / 1e6, s.pocketLabelNanos.sum() / 1e6, s.pocketRayNanos.sum() / 1e6, s.pocketRays.sum(), s.pocketSightRays.sum(), s.pocketCameraRays.sum(), s.pocketSectionsSent, s.pocketBlocksSent, s.pocketReveals, s.pocketServerNanos / 1e6,
			s.oreChecks.sum(), s.oreNanos.sum() / 1e6, s.oreRayNanos.sum() / 1e6, s.oreRays.sum(), s.oresHidden.sum(), s.oreFarSections.sum(), s.oreReveals,
			java.util.Arrays.toString(s.revealDistance), java.util.Arrays.toString(s.bedrockChangesBySection));
		context.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int inspect(CommandContext<CommandSourceStack> context, ServerPlayer player) {
		PlayerOcclusion state = Occlusion.state(player);
		int hidden = 0;
		for (long mask : state.revealed.values()) {
			hidden += Long.bitCount(state.fullMask & ~mask);
		}
		int visible = 0;
		if (state.view != null) {
			for (long mask : state.view.columnMask) {
				visible += Long.bitCount(mask);
			}
		}
		int seenEntities = 0;
		int total = 0;
		for (Entity entity : state.level.getAllEntities()) {
			if (entity == player || entity.distanceToSqr(player) > 128 * 128) continue;
			total++;
			if (Occlusion.canSee(player, entity)) seenEntities++;
		}
		String text = String.format("%s: enabled %s bypass %s | client has %d chunks, %d sections hidden | %d sections visible | entities within 128: %d shown of %d | surface: %s",
			player.getName().getString(), state.enabled, state.bypass, state.revealed.size(), hidden, visible, seenEntities, total, state.view == null ? "no walk yet" : state.view.deepReason);
		context.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}
}
