# Occlusion (Fabric, 26.3)

Server-side cave culling and anti-xray. The server works out what each player can actually see and only sends that. Everything out of sight goes out as plain filler (stone, deepslate, netherrack, or air) and turns into the real blocks as it comes into view. Entities out of sight are not sent at all.

This stops x-ray packs, ore ESP, freecam peeking into caves and bases, and player/mob ESP through walls. As a side effect it cuts chunk bandwidth to roughly a third of vanilla.

## Features

- **Cave culling**: per 16x16x16 section, using the client's own section walk plus voxel line-of-sight rays underground. Sections in sight are revealed within a tick; sections the player is moving toward are sent ahead of time (`prefetchDistance`).
- **Sealed pockets** (`hideSealedPockets`): air pockets inside a visible section that no line of sight reaches are filled too.
- **Unseen ores** (`hideUnseenOres`): ores touching air but not reachable by any line from the eye are sent as stone.
- **Dig reveal** (`revealBehindDig`, `revealAroundAim`): what is behind the block you mine shows up before it breaks, so instant mining never shows filler.
- **Entities** (`entityLineOfSight`): every entity except projectiles gets a block-level line-of-sight check and is removed from the client while hidden. Players within `playerVanillaDistance` (32) are always shown. Wither boss bars stay.
- **Third person** (`thirdPersonCamera`): the F5 camera spots count as eyes.
- **Hidden sounds** (`hiddenSounds`): sounds the client would make itself from hidden blocks (lava, portals, spawners, ...) are worked out on the server and sent, so hiding a block doesn't make it silent.
- **Bedrock players** (Geyser): reveals go out as block changes with a per-tick budget (`bedrockBlocksPerTick`, `bedrockBlocksPerTickTotal`), because a column resend makes Bedrock clients drop the whole chunk for a frame.
- **Simple Voice Chat**: hidden players' voices still play at their real position.
- **API**: `me.TreeOfSelf.PandaOcclusion.api.PandaOcclusionApi.canSee(ServerPlayer, AABB)` for holograms and other fake entities.

Works well with an AntiXray mod: list the same natural blocks in AntiXray's hidden blocks and in `keepHiddenUntilExposed`.

## Commands

All need operator level 3.

- `/occlusion reload`: reload `config/PandaOcclusion.json`
- `/occlusion stats`, `/occlusion resetstats`
- `/occlusion bypass`: see everything yourself (toggle)
- `/occlusion section <player> <pos>`, `/occlusion trace <player> <pos>`, `/occlusion why <player> <entity id>`, `/occlusion inspect <player>`: why something is hidden or shown for a player
- `/occlusion analyze <radius>`, `/occlusion bench <player>`: block census and walk timing

Kill switch: `"enabled": false` + `/occlusion reload`.

## Building

`./gradlew build` (Java 25). The jar is in `build/libs/`.

## License

[CC0](https://creativecommons.org/public-domain/cc0/)
