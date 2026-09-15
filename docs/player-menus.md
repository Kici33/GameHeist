# Player menus and preferences

Open `/heist` or `/heist menu`. Sections are `home`, `crew`, `queue`, `loadout`,
`settings`, and `results`. All require `heist.play`; starting a practice crew also
requires `heist.admin`. Console users retain `/heist help` and other commands.

The queue view lists existing briefing sessions and joins through the same pack,
capacity, admission and profile checks as `/heist join`. It does not allocate a
network backend: that is tracked in issue #10. Results show only the viewer's
acknowledged runs from this server lifetime; MongoDB remains the durable history.
Practice sessions award no progression.

The three preset slots support role selection and 1–24 character names. Rename
uses a clickable chat command suggestion (`/heist presetname <1-3> <name>`), so
players can enter their own text without a client mod. Create/select the slot
before renaming it. The current content catalog unlocks only the carbine weapon,
medkit gadget and default cosmetic. Unknown cosmetics and equipment in the wrong
slot are rejected. Additional unlocks depend on progression in issue #7. Preset
names and cosmetic references are retained in admission/reservation snapshots.

Settings are available in the menu and through commands:

- `/heist settings language en` or `pl`: translated menu navigation and labels.
- `/heist settings sound on|off`: existing crew-scoped plugin sounds.
- `/heist settings particles off`: suppresses the viewer's victory particles.
- `/heist settings motion off`: suppresses the large result title animation;
  the chat result remains available.
- `/heist settings notifications off`: suppresses optional phase chat notices;
  combat warnings, objectives and results remain visible.

Administrative diagnostics, gameplay HUD text and some runtime errors still use
English. This is menu localization, not a completed translation of all game text.
No setting changes Minecraft client controls or effects from other plugins.

Profile edits and reloads are lobby-only. Menus show loading/saving or unavailable
states until the owner-thread cache receives a storage acknowledgement. Failed
operations require reload; no menu reports an unacknowledged write as saved.
Clicks carry the displayed revision, and stale edits are rejected. Inventory
actions are server-owned, all item movement/dragging is cancelled, and deferred
actions check the same online player and open inventory before executing.

## Verification still required on Paper 26.1.2

Automated coverage checks stale revisions, disconnected asynchronous operations,
failure/reload states, immutable presets, slot/cosmetic validation, preference
preservation and legacy/current MongoDB document round trips. It does not verify
Minecraft rendering or native inventory events.

- [ ] Open every section with a regular player and admin. Check empty, populated
  and paginated session/results views; another player's runs must not appear.
- [ ] Verify PL/EN at small and large GUI scales. Read names and lore without
  depending on color alone; test maximum-length names and keyboard navigation.
- [ ] Shift-click, number-key swap, double-click, drag and drop in both inventory
  halves; no menu items may be taken or player items lost. Rapidly click settings.
- [ ] While a save/load is pending, close the menu, disconnect, reconnect and open
  a different view. Delayed work must not reopen an old menu or edit a new session.
- [ ] Change a profile through a command while its menu is open; clicking an old
  role/settings choice must reject the stale revision. Test missing/revoked
  permissions and edits after joining a match or during server drain.
- [ ] Test pack-not-ready, full/started/deleted sessions and join teleport failure.
  The command admission checks must still apply to the menu.
- [ ] With MongoDB enabled, restart and confirm names and every preference survive.
  Simulate a failed save, reload and confirm the acknowledged state.
- [ ] Compare two players' sound, phase notifications, victory particles and result
  titles with opposite preferences. Essential results must reach both players.

Issue #8 remains open pending real-client acceptance and any broader localization
or progression-driven cosmetic UI required for release.
