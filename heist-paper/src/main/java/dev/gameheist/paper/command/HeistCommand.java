package dev.gameheist.paper.command;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.player.*;
import dev.gameheist.paper.pack.ResourcePackGate;
import dev.gameheist.paper.npc.GuardController;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.InstanceManager;
import dev.gameheist.runtime.persistence.InMemoryResultRepository;
import dev.gameheist.runtime.persistence.ProfileSessions;
import dev.gameheist.runtime.health.DrainController;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public final class HeistCommand implements TabExecutor {
    private final InstanceManager instances;
    private final ArenaRegistry arenas;
    private final ResourcePackGate packs;
    private final PlayerSessions players;
    private final InMemoryResultRepository results;
    private final Logger logger;
    private final GuardController guards;
    private final ProfileSessions profiles;
    private final boolean durable;
    private final StatisticsView statistics;
    private final DrainController drain;
    private final ProgressionView progression;
    private final dev.gameheist.paper.menu.PlayerMenus menus;

    public HeistCommand(InstanceManager instances, ArenaRegistry arenas, ResourcePackGate packs,
                        PlayerSessions players, InMemoryResultRepository results, Logger logger, GuardController guards, ProfileSessions profiles, boolean durable, StatisticsView statistics, DrainController drain, dev.gameheist.paper.menu.PlayerMenus menus, ProgressionView progression) {
        this.instances = instances;
        this.arenas = arenas;
        this.packs = packs;
        this.players = players;
        this.results = results;
        this.logger = logger;
        this.guards = guards;
        this.profiles = profiles;
        this.durable = durable;
        this.statistics = statistics;
        this.drain = drain;
        this.menus = menus;
        this.progression = progression;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("heist.play") || (!Set.of("help", "controls", "arenas", "list", "join", "profile", "preset", "presetname", "menu", "progression", "settings", "stats").contains(action)
                && !sender.hasPermission("heist.admin"))) {
            say(sender, "You do not have permission for this action.");
            return true;
        }
        try {
            switch (action) {
                case "help" -> say(sender, """
                        GameHeist practice commands (no rewards):
                        /heist arenas | list | controls
                        /heist results [page]
                        /heist stats <arena-id> <version> <crew-size>
                        /heist profile [reload] | /heist progression
                        /heist preset <1-3> [TECHNICIAN|SCOUT|ENFORCER|SUPPORT]
                        /heist menu [home|crew|queue|loadout|settings|results]
                        /heist presetname <1-3> <name>
                        /heist settings <sound|particles|motion|notifications> <on|off>
                        /heist settings language <en|pl>
                        /heist create <arena-id> <version>
                        /heist join <instance-uuid> [TECHNICIAN|SCOUT|ENFORCER|SUPPORT]
                        /heist start <instance-uuid>
                        /heist complete <instance-uuid> <objective-id>
                        /heist alarm <instance-uuid>
                        /heist guards <instance-uuid>
                        /heist stop <instance-uuid>
                        /heist drain
                        """);
                case "controls" -> say(sender, """
                        GameHeist controls:
                        Slot 1 carbine: left click to fire; F to reload. Clicking empty starts reload.
                        Slot 2 medkit: right click air/block to heal yourself, or click a living teammate within 3 blocks.
                        Medkit: one use per run, up to 40 HP; cannot revive downed players.
                        Revive: sneak + right click a downed teammate; keep sneaking within 3 blocks with clear sight.
                        Revive takes 4 seconds (Support: 3); damage interrupts.
                        Objectives: right click the marker base with a free hand or carbine selected.
                        Loot: carry to GREEN and right click to secure; Shift+F returns it to its original marker.
                        Shift+F is reserved for loot return; release sneak to reload with F.
                        Extraction: after minimum loot, right click GREEN to vote; gather there for departure.
                        Combat equipment is available in graybox:4. /heist settings sound off mutes plugin effects.
                        """);
                case "progression" -> {
                    if (!(sender instanceof Player player)) throw new IllegalStateException("Progression requires a player");
                    progression.request(player);
                }
                case "menu" -> {
                    if (!(sender instanceof Player player)) throw new IllegalStateException("Menus require a player");
                    menus.open(player, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "home");
                }
                case "profile", "preset", "presetname", "settings" -> profileAction(sender, args);
                case "stats" -> {
                    requireArgs(args, 4);
                    if (!(sender instanceof Player player)) throw new IllegalStateException("Statistics require a player");
                    var arena = new ArenaKey(args[1], Integer.parseInt(args[2]));
                    statistics.request(player, new StatisticsScope(arena, Difficulty.NORMAL, Integer.parseInt(args[3]), true));
                }
                case "arenas" -> arenas.all().forEach(a -> say(sender, a.key() + " — " + a.displayName()));
                case "list" -> {
                    say(sender, "Practice instances: " + instances.all().size() + "; draining=" + instances.draining());
                    instances.all().forEach(i -> say(sender, i.match().id() + " " + i.state() + " "
                            + i.match().phase() + " crew=" + i.match().participants().size()
                            + " objectives=" + i.match().completedObjectives() + i.lastError().map(e -> " error=" + e).orElse("")));
                }
                case "create" -> {
                    requireArgs(args, 3);
                    UUID id = instances.createPractice(new ArenaKey(args[1], Integer.parseInt(args[2])),
                            Difficulty.NORMAL, new Random().nextLong());
                    say(sender, "Created practice instance " + id + ". Join it before starting.");
                    logger.info("Admin " + sender.getName() + " created practice instance " + id);
                }
                case "join" -> {
                    requireArgs(args, 2);
                    if (!(sender instanceof Player player)) throw new IllegalStateException("Join must be run by a player");
                    if (!packs.ready(player)) throw new IllegalStateException("Wait for the required resource pack to load");
                    UUID id = UUID.fromString(args[1]);
                    var profile = profiles.require(player.getUniqueId());
                    Loadout loadout = args.length > 2 ? Loadout.starter(Role.valueOf(args[2].toUpperCase(Locale.ROOT))) : profile.selectedLoadout();
                    instances.join(id, player.getUniqueId(), loadout);
                    try {
                        var snapshot = instances.snapshot(id);
                        World world = Objects.requireNonNull(Bukkit.getWorld(snapshot.worldName()), "Instance world is missing");
                        players.enter(player, world.getSpawnLocation());
                    } catch (RuntimeException failure) {
                        instances.leaveBriefing(player.getUniqueId());
                        throw failure;
                    }
                    say(sender, "Joined practice crew as " + loadout.role() + ".");
                }
                case "start" -> {
                    requireArgs(args, 2);
                    instances.start(UUID.fromString(args[1]));
                    say(sender, "Practice heist started.");
                }
                case "complete" -> {
                    requireArgs(args, 3);
                    boolean changed = instances.completeObjective(UUID.fromString(args[1]), args[2]);
                    say(sender, changed ? "Practice objective completed." : "Objective was already complete.");
                }
                case "alarm" -> {
                    requireArgs(args, 2);
                    instances.raiseAlarm(UUID.fromString(args[1]));
                    say(sender, "Alarm raised.");
                }
                case "guards" -> {
                    requireArgs(args, 2);
                    guards.inspect(UUID.fromString(args[1])).forEach(line -> say(sender, line));
                }
                case "stop" -> {
                    requireArgs(args, 2);
                    instances.stop(UUID.fromString(args[1]), "admin_stop");
                    logger.info("Admin " + sender.getName() + " stopped practice instance " + args[1]);
                    say(sender, "Practice instance finalizing; cleanup status is visible in /heist list.");
                }
                case "drain" -> {
                    drain.drain();
                    logger.info("Admin " + sender.getName() + " drained practice admission");
                    var status = drain.status();
                    say(sender, "Admission and profile edits closed. Active instances=" + status.activeInstances()
                            + "; pending profile operations=" + status.pendingProfileOperations()
                            + "; unacknowledged writes=" + status.unacknowledgedProfileWrites()
                            + "; world cleanup healthy=" + status.worldCleanupHealthy()
                            + "; safe to stop=" + status.safeToStop() + ". Repeat /heist drain to check. Restart to reopen.");
                }
                case "results" -> {
                    if (args.length > 2) throw new IllegalArgumentException("Use /heist results [page]");
                    var page = ResultHistory.page(results.all(), args.length == 2 ? Integer.parseInt(args[1]) : 1);
                    say(sender, durable ? "Acknowledged results from this server run; full history is in MongoDB. No rewards granted." : "Practice history is in memory only; no rewards were granted.");
                    say(sender, "Results page " + page.number() + "/" + page.pages() + " (" + page.total() + " runs, newest first)");
                    if (page.entries().isEmpty()) say(sender, "No completed results available yet.");
                    page.entries().forEach(result -> say(sender, ResultHistory.describe(result)));
                    if (page.number() < page.pages()) say(sender, "Next: /heist results " + (page.number() + 1));
                }
                default -> say(sender, "Unknown command. Use /heist help.");
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException failure) {
            say(sender, "Cannot perform action: " + failure.getMessage());
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("heist.play") || args.length == 0) return List.of();
        List<String> choices = List.of();
        if (args.length == 1) choices = sender.hasPermission("heist.admin")
                ? List.of("help", "controls", "arenas", "list", "create", "join", "start", "complete", "alarm", "guards", "stop", "drain", "results", "profile", "preset", "presetname", "menu", "progression", "settings", "stats")
                : List.of("help", "controls", "arenas", "list", "join", "profile", "preset", "presetname", "menu", "progression", "settings", "stats");
        else if (args.length == 2 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("stats"))) {
            choices = arenas.all().stream().map(a -> a.key().id()).distinct().toList();
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("stats"))) {
            choices = arenas.all().stream().filter(a -> a.key().id().equals(args[1])).map(a -> Integer.toString(a.key().version())).toList();
        } else if (args.length == 2 && Set.of("join", "start", "complete", "alarm", "guards", "stop").contains(args[0].toLowerCase(Locale.ROOT))) {
            choices = instances.all().stream().map(i -> i.match().id().toString()).toList();
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("preset"))) {
            choices = Arrays.stream(Role.values()).map(Enum::name).toList();
        } else if (args.length == 3 && args[0].equalsIgnoreCase("complete")) {
            try {
                choices = arenas.require(instances.snapshot(UUID.fromString(args[1])).match().arena())
                        .objectives().stream().map(o -> o.id()).toList();
            } catch (IllegalArgumentException ignored) { return List.of(); }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("menu")) choices = List.of("home", "crew", "queue", "loadout", "settings", "results");
        if (args.length == 2 && args[0].equalsIgnoreCase("settings")) choices = List.of("language", "sound", "particles", "motion", "notifications");
        if (args.length == 3 && args[0].equalsIgnoreCase("settings")) choices = args[1].equalsIgnoreCase("language") ? List.of("en", "pl") : List.of("on", "off");
        if (args.length == 4 && args[0].equalsIgnoreCase("stats")) choices = List.of("1", "2", "3", "4");
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    private void profileAction(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) throw new IllegalStateException("Profiles require a player");
        UUID id = player.getUniqueId();
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("profile") && args.length == 1) {
            var profile = profiles.require(id);
            say(sender, "Profile revision " + profile.revision() + "; selected preset=" + (profile.selectedPreset() + 1)
                    + "; settings=" + profile.settings());
            for (int slot = 0; slot < profile.presets().size(); slot++) say(sender, (slot + 1) + ": " + profile.presets().get(slot));
            return;
        }
        if (instances.instanceOf(id).isPresent()) throw new IllegalStateException("Edit or reload your profile in the lobby");
        if (action.equals("profile")) {
            if (args.length != 2 || !args[1].equalsIgnoreCase("reload")) throw new IllegalArgumentException("Use /heist profile [reload]");
            profiles.reload(id);
            say(sender, "Profile reload requested; use /heist profile to check completion.");
            return;
        }
        if (action.equals("presetname")) {
            requireArgs(args, 3);
            int slot = Integer.parseInt(args[1]) - 1;
            String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            profiles.edit(id, current -> {
                if (slot < 0 || slot >= current.presets().size()) throw new IllegalArgumentException("Create the preset first");
                var presets = new ArrayList<>(current.presets());
                var old = presets.get(slot);
                presets.set(slot, new Loadout(old.role(), old.weaponId(), old.gadgetId(), name, old.cosmeticId()));
                return new PlayerProfile(id, Math.incrementExact(current.revision()), presets, current.selectedPreset(), current.settings());
            });
        } else if (action.equals("preset")) {
            requireArgs(args, 2);
            int slot = Integer.parseInt(args[1]) - 1;
            if (slot < 0 || slot > 2) throw new IllegalArgumentException("Preset slot must be 1-3");
            Role role = args.length > 2 ? Role.valueOf(args[2].toUpperCase(Locale.ROOT)) : null;
            profiles.edit(id, current -> {
                var presets = new ArrayList<>(current.presets());
                if (role != null) {
                    while (presets.size() <= slot) presets.add(Loadout.starter(Role.TECHNICIAN));
                    var old = presets.get(slot);
                    presets.set(slot, new Loadout(role, old.weaponId(), old.gadgetId(), old.name(), old.cosmeticId()));
                } else if (slot >= presets.size()) throw new IllegalArgumentException("Create that preset by specifying a role");
                return new PlayerProfile(id, Math.incrementExact(current.revision()), presets, slot, current.settings());
            });
        } else {
            requireArgs(args, 3);

            profiles.edit(id, current -> {
                var settings = current.settings().change(args[1].toLowerCase(Locale.ROOT), args[2].toLowerCase(Locale.ROOT));
                return new PlayerProfile(id, Math.incrementExact(current.revision()), current.presets(), current.selectedPreset(), settings);
            });
        }
        say(sender, "Profile save requested; use /heist profile to confirm the saved revision.");
    }
    private static void requireArgs(String[] args, int count) {
        if (args.length < count) throw new IllegalArgumentException("Missing arguments; use /heist help");
    }
    private static void say(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }
}
