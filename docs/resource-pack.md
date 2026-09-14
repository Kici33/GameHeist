# Brasslock resource pack 1.2

Version 1.2 adds the field medkit model in hotbar slot 2, with hand and inventory transforms. Install the new ZIP and update the hosted URL/SHA-1 together. Check that the case appears before use, disappears after healing, and the player's original inventory returns on exit. Without the pack, the named paper item remains the fallback.

The pack supplies original cuboid models and generated 16-pixel textures for the carbine, loot bag, security terminal (active/disabled), extraction beacon, and drill (idle/running/jammed/complete). Dark metal, canvas, brass, and colored status panels share one palette. The running drill's lamp has a two-frame pulse. Labels and the existing HUD still communicate gameplay state alongside color.

## Build and outputs

Run `.\gradlew.bat resourcePack` on Windows or `bash ./gradlew resourcePack` on Linux/macOS. The ordinary `build` also generates the pack and runs its regression tests. Java 25 is sufficient; no image editor, Python, external textures, or asset download is required. The new `heist-pack` module is a build tool and is not bundled into the server plugin.

Outputs in `build/resource-pack/`:

- `gameheist-brasslock-1.2.zip`: client-installable pack with `pack.mcmeta` and `pack.png` at the archive root.
- `.zip.sha1` and `.zip.sha256`: hashes of the exact archive bytes.
- `server-config.yml`: configuration excerpt containing the generated SHA-1; supply the real HTTPS URL after upload.
- `models-preview.png`: an isometric overview rendered from the source cuboids. It is not a Minecraft screenshot and does not validate in-game transforms or lighting.

The ZIP uses sorted entries and fixed timestamps; generated pixels are deterministic. Tests verify archive reproducibility, complete model/texture references, metadata, geometry bounds, absence of vanilla overrides, and the item-model IDs used by the plugin. CI uploads the pack as its own build artifact.

## Local preview

1. Copy the ZIP into the Minecraft 26.1.2 client's `resourcepacks` folder and enable it in Options > Resource Packs.
2. In the plugin configuration, leave `resource-pack.development-bypass: true` and set `resource-pack.preview-models: true`.
3. Restart the test server and create `graybox:4`. Each test client must install the pack manually in this mode; the bypass does not verify successful application.
4. Check the custom carbine in first/third person and in the inventory. Fire/reload retain their existing controls.
5. Check the models above the existing objective bases. Right click the base block to interact; displays never grant progress or alter line-of-sight validation.
6. Install the drill, trigger a jam, repair, and complete it. Verify the appropriate model changes and pulsing work light. Disabling security changes the terminal to a check mark.
7. Pick up a bag: its world model disappears and a cosmetic bag occupies hotbar slot 9 in combat sessions. Deposit it or become downed: the slot clears. A recovered bag reappears at its original marker. The inventory icon cannot create, transfer, or deposit loot independently of server state.
8. Abort, complete, disconnect, and gracefully shut down separate sessions. All displays must be removed and the original inventory restored. Versions 2 and 3 show world props when models are enabled but do not replace inventory for a bag icon.

For testing without the pack, leave `preview-models: false` with the development bypass enabled. The plugin then uses the existing vanilla carbine and objective markers.

## Required server pack

Upload the built ZIP to an immutable HTTPS download URL that returns the archive directly. Copy its URL and generated SHA-1 into the existing `resource-pack` configuration and set `development-bypass: false`. The existing required-pack gate waits for successful application before admission. Custom models are enabled automatically in this mode; `preview-models` is only for manual local preview. Rebuild and update the URL/hash together when content changes. The pack UUID remains the stable GameHeist pack identity.

No hosting URL is fabricated, no server is started, and pack enforcement is not enabled automatically by the build. Live client resource reload, download/caching, first/third-person orientation, prop placement, and inventory recovery remain manual acceptance checks.

## Editable sources and format

`resource-pack/assets/gameheist/models/item/` contains the editable cuboids, UVs and display transforms. `assets/gameheist/items/` maps the stable `gameheist:*` item-model keys to those files. The server applies keys through `ItemMeta.setItemModel`; it does not overwrite every vanilla iron hoe or use OptiFine/CIT. See the [Paper ItemMeta API](https://jd.papermc.io/paper/1.21.5/org/bukkit/inventory/meta/ItemMeta.html).

`palette.json` controls the original texture colors. The build tool generates PNG textures under `textures/item/palette/`, including all textures used by each model in the item atlas. Minecraft split item textures into a separate atlas in [1.21.11](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11). The metadata targets resource-pack format 84.0, introduced by [Minecraft 26.1](https://feedback.minecraft.net/hc/en-us/articles/44551668333837-Minecraft-Java-Edition-26-1), with `min_format`/`max_format` as specified in the [pack metadata update](https://feedback.minecraft.net/hc/en-us/articles/38407004270605-Minecraft-Java-Edition-Snapshot-25w31a).

The pack includes its own `LICENSE.txt`. Models, palette pixels, icon, and synthesized effects are original GameHeist assets. There are no copied Minecraft textures, third-party recordings, global mob skins, shaders, or fonts. Guard models and a custom HUD remain future work.

## Sound effects

Version 1.1 adds six mono OGG Vorbis effects: carbine fire/reload, alarm, drill motor, jam, and vault opening. English and Polish subtitles are included; enable Minecraft subtitles to display them. Sounds follow the Players volume slider. Local effects are sent only to the current crew in the instance world within 24 blocks; alarm plays once for each crew member. Drill audio repeats at most once every 20 ticks while running and stops being emitted on jam, completion, or match end. Existing short samples may finish their tail (at most 1.9 seconds); no client loops or scheduled audio tasks remain.

`/heist settings sound off` in the lobby disables these plugin effects, including their vanilla fallbacks. Pack preview/required-pack configuration enables the custom sounds together with custom models. With the development bypass and preview disabled, vanilla sound events provide feedback.

The checked-in `assets/gameheist/sounds/*.ogg` files are included directly in normal Java builds. To re-author them, create a separate Python environment, install `soundfile==0.13.1`, and run `python tools/audio/generate.py`. The script generates seeded PCM, encodes Vorbis, and decodes every result to check duration, mono channels, clipping, and non-silence. Encoder versions may change encoded bytes; always rebuild the ZIP and refresh the hosted hash after regeneration.

Manual acceptance: use two crew members and a player outside the match. Check local fire/reload range, one alarm from either gunfire or guard detection, motor/jam/repair/completion transitions, and silence after match cleanup. Repeat with one muted crew member, with the pack disabled, and with English/Polish subtitles. In-client loudness and spatial rendering still need this live check.
