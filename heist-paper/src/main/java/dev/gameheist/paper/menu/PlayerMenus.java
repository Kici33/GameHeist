package dev.gameheist.paper.menu;

import dev.gameheist.domain.player.*;
import dev.gameheist.domain.match.MatchPhase;
import dev.gameheist.paper.command.ResultHistory;
import dev.gameheist.runtime.instance.InstanceManager;
import dev.gameheist.runtime.persistence.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.function.Consumer;

/** Server-owned inventories: never infer actions from client item names or lore. */
public final class PlayerMenus implements Listener {
    private final Plugin plugin;
    private final ProfileSessions profiles;
    private final InstanceManager instances;
    private final InMemoryResultRepository results;
    private int ticks;

    public PlayerMenus(Plugin plugin, ProfileSessions profiles, InstanceManager instances, InMemoryResultRepository results) {
        this.plugin = plugin; this.profiles = profiles; this.instances = instances; this.results = results;
    }

    public void open(Player player, String section) { open(player, section, 0); }

    private String text(Player player, String en, String pl) {
        return profiles.settings(player.getUniqueId()).language().equals("pl") ? pl : en;
    }

    private void open(Player player, String section, int page) {
        if (!player.hasPermission("heist.play")) throw new IllegalStateException("Missing heist.play permission");
        if (!Set.of("home", "crew", "queue", "loadout", "settings", "results").contains(section))
            throw new IllegalArgumentException("Unknown menu");
        var status = profiles.status(player.getUniqueId());
        var menu = new Menu(player.getUniqueId(), section, page, status);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("GameHeist · " + title(player, section)));
        button(menu, 45, Material.ARROW, text(player, "Home", "Menu główne"), "", p -> open(p, "home"));
        button(menu, 49, Material.SUNFLOWER, text(player, "Refresh", "Odśwież"), "", p -> open(p, section, page));
        button(menu, 53, Material.BARRIER, text(player, "Close", "Zamknij"), "", Player::closeInventory);
        if (status != ProfileSessions.Status.READY) {
            button(menu, 22, Material.CLOCK,
                    status == ProfileSessions.Status.LOADING ? text(player, "Loading / saving…", "Wczytywanie / zapis…") : text(player, "Profile unavailable", "Profil niedostępny"),
                    text(player, "Changes require confirmed storage", "Zmiany wymagają potwierdzenia zapisu"), null);
            if (status == ProfileSessions.Status.FAILED) button(menu, 31, Material.RECOVERY_COMPASS,
                    text(player, "Reload profile", "Wczytaj profil ponownie"), "", p -> {
                        lobby(p); profiles.reload(p.getUniqueId()); open(p, section, page);
                    });
        } else switch (section) {
            case "home" -> {
                String[] names = {"crew", "queue", "loadout", "settings", "results"};
                Material[] icons = {Material.PLAYER_HEAD, Material.COMPASS, Material.CROSSBOW, Material.COMPARATOR, Material.BOOK};
                for (int i = 0; i < names.length; i++) {
                    String target = names[i];
                    button(menu, 20 + i, icons[i], title(player, target), "", p -> open(p, target));
                }
            }
            case "settings" -> settings(player, menu);
            case "loadout" -> loadouts(player, menu);
            case "queue" -> queue(player, menu);
            case "crew" -> crew(player, menu);
            case "results" -> history(player, menu);
            default -> throw new IllegalStateException("Unknown menu");
        }
        player.openInventory(menu.inventory);
    }

    private String title(Player p, String section) {
        return switch (section) {
            case "crew" -> text(p, "Crew", "Ekipa");
            case "queue" -> text(p, "Practice sessions", "Sesje treningowe");
            case "loadout" -> text(p, "Loadout", "Wyposażenie");
            case "settings" -> text(p, "Settings", "Ustawienia");
            case "results" -> text(p, "Your results", "Twoje wyniki");
            default -> text(p, "Main menu", "Menu główne");
        };
    }

    private String role(Player p, Role role) {
        return switch (role) {
            case TECHNICIAN -> text(p, "Technician", "Technik");
            case SCOUT -> text(p, "Scout", "Zwiadowca");
            case ENFORCER -> text(p, "Enforcer", "Szturmowiec");
            case SUPPORT -> text(p, "Support", "Wsparcie");
        };
    }

    private void settings(Player player, Menu menu) {
        var profile = profiles.require(player.getUniqueId());
        var s = profile.settings();
        String[] keys = {"language", "sound", "particles", "motion", "notifications"};
        String[] labels = {text(player, "Language", "Język"), text(player, "Sound", "Dźwięk"), text(player, "Particles", "Cząsteczki"),
                text(player, "Title animation", "Animacja tytułów"), text(player, "Phase notifications", "Powiadomienia o fazach")};
        boolean[] values = {s.language().equals("en"), s.soundEnabled(), !s.reducedParticles(), !s.reducedMotion(), s.notificationsEnabled()};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            String value = i == 0 ? (values[i] ? "pl" : "en") : (values[i] ? "off" : "on");
            String state = i == 0 ? s.language() : values[i] ? text(player, "ON", "WŁ.") : text(player, "OFF", "WYŁ.");
            button(menu, 20 + i, Material.COMPARATOR, labels[i] + ": " + state,
                    text(player, "Click to change • lobby only", "Kliknij, aby zmienić • tylko w lobby"), p -> {
                        lobby(p);
                        profiles.edit(p.getUniqueId(), profile.revision(), current -> new PlayerProfile(current.playerId(),
                                Math.incrementExact(current.revision()), current.presets(), current.selectedPreset(), current.settings().change(key, value)));
                        open(p, "settings");
                    });
        }
    }

    private void loadouts(Player player, Menu menu) {
        var profile = profiles.require(player.getUniqueId());
        for (int i = 0; i < 3; i++) {
            int slot = i;
            var loadout = i < profile.presets().size() ? profile.presets().get(i) : Loadout.starter(Role.TECHNICIAN);
            button(menu, 10 + i * 3, Material.CHEST, (i + 1) + ": " + loadout.name() + (profile.selectedPreset() == i ? " ✓" : ""),
                    role(player, loadout.role()) + "\n" + text(player, "Carbine · Medkit", "Karabinek · Apteczka") + "\n" + text(player, "Cosmetic: default (unlocked)", "Wygląd: podstawowy (odblokowany)"),
                    p -> savePreset(p, profile, slot, loadout));
            button(menu, 19 + i * 3, Material.IRON_CHESTPLATE, text(player, "Change role", "Zmień rolę"), role(player, loadout.role()), p -> {
                Role next = Role.values()[(loadout.role().ordinal() + 1) % Role.values().length];
                savePreset(p, profile, slot, new Loadout(next, loadout.weaponId(), loadout.gadgetId(), loadout.name(), loadout.cosmeticId()));
            });
            button(menu, 28 + i * 3, Material.NAME_TAG, text(player, "Rename", "Zmień nazwę"), text(player, "1–24 characters; enter in chat", "1–24 znaki; wpisz w czacie"), p -> {
                lobby(p); p.closeInventory();
                p.sendMessage(Component.text(text(p, "Click to enter a preset name", "Kliknij, aby wpisać nazwę zestawu"), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand("/heist presetname " + (slot + 1) + " ")));
            });
        }
    }

    private void savePreset(Player p, PlayerProfile seen, int slot, Loadout loadout) {
        lobby(p);
        profiles.edit(p.getUniqueId(), seen.revision(), current -> {
            var presets = new ArrayList<>(current.presets());
            while (presets.size() <= slot) presets.add(Loadout.starter(Role.TECHNICIAN));
            presets.set(slot, loadout);
            return new PlayerProfile(current.playerId(), Math.incrementExact(current.revision()), presets, slot, current.settings());
        });
        open(p, "loadout");
    }

    private void queue(Player player, Menu menu) {
        var available = instances.all().stream().filter(i -> i.match().phase() == MatchPhase.BRIEFING)
                .sorted(Comparator.comparing(i -> i.match().id())).toList();
        button(menu, 4, Material.PAPER, text(player, "Practice only • no rewards", "Tylko trening • bez nagród"),
                text(player, "Network matchmaking is not available yet", "Matchmaking sieciowy nie jest jeszcze dostępny"), null);
        int pages = Math.max(1, (available.size() + 26) / 27);
        int page = Math.min(menu.page, pages - 1);
        for (int i = page * 27; i < Math.min(available.size(), (page + 1) * 27); i++) {
            var instance = available.get(i);
            button(menu, 9 + i % 27, Material.COMPASS, instance.match().arena().toString(),
                    text(player, "Crew: ", "Ekipa: ") + instance.match().participants().size() + "\n" + instance.match().id(), p -> {
                        p.closeInventory(); p.performCommand("heist join " + instance.match().id());
                    });
        }
        if (available.isEmpty()) button(menu, 22, Material.CLOCK, text(player, "No sessions available", "Brak dostępnych sesji"),
                text(player, "An administrator must prepare a practice session", "Administrator musi przygotować sesję treningową"), null);
        pagination(player, menu, page, pages);
    }

    private void crew(Player p, Menu menu) {
        var id = instances.instanceOf(p.getUniqueId());
        if (id.isEmpty()) {
            button(menu, 22, Material.COMPASS, text(p, "Join a practice crew", "Dołącz do ekipy treningowej"), "", player -> open(player, "queue"));
            return;
        }
        var match = instances.snapshot(id.orElseThrow()).match();
        int slot = 19;
        for (var member : match.participants().entrySet()) {
            var online = Bukkit.getPlayer(member.getKey());
            button(menu, slot++, Material.PLAYER_HEAD, online == null ? member.getKey().toString() : online.getName(),
                    role(p, member.getValue().role()) + "\n" + member.getValue().name(), null);
        }
        if (p.hasPermission("heist.admin") && match.phase() == MatchPhase.BRIEFING)
            button(menu, 31, Material.LIME_CONCRETE, text(p, "Start practice", "Rozpocznij trening"), "", player -> {
                player.closeInventory(); player.performCommand("heist start " + match.id());
            });
    }

    private void history(Player p, Menu menu) {
        var own = results.all().stream().filter(r -> r.participants().containsKey(p.getUniqueId())).toList();
        int pages = Math.max(1, (own.size() + 9) / 10);
        var page = ResultHistory.page(own, Math.min(menu.page + 1, pages));
        button(menu, 4, Material.PAPER, text(p, "Acknowledged results • this server run", "Zapisane wyniki • bieżące uruchomienie"),
                text(p, "Practice runs grant no rewards", "Treningi nie przyznają nagród"), null);
        int slot = 9;
        for (var result : page.entries()) {
            String outcome = switch (result.outcome()) {
                case WON -> text(p, "Won", "Wygrana");
                case LOST -> text(p, "Lost", "Przegrana");
                default -> text(p, "Aborted", "Przerwano");
            };
            String time = result.gameplayMillis().isPresent()
                    ? dev.gameheist.paper.gameplay.RunTimeFormat.format(result.gameplayMillis().getAsLong()) : "—";
            button(menu, slot++, Material.BOOK, result.arena() + " · " + outcome,
                    result.finishedAt() + "\n" + text(p, "Secured bags: ", "Zabezpieczone torby: ") + result.securedBags()
                            + "\n" + text(p, "Time: ", "Czas: ") + time, null);
        }
        if (own.isEmpty()) button(menu, 22, Material.BOOK, text(p, "No results yet", "Brak wyników"), "", null);
        pagination(p, menu, page.number() - 1, pages);
    }

    private void pagination(Player p, Menu menu, int page, int pages) {
        if (page > 0) button(menu, 47, Material.ARROW, text(p, "Previous", "Poprzednia"), "", player -> open(player, menu.section, page - 1));
        if (page + 1 < pages) button(menu, 51, Material.ARROW, text(p, "Next", "Następna"), "", player -> open(player, menu.section, page + 1));
    }

    private void lobby(Player p) {
        if (instances.instanceOf(p.getUniqueId()).isPresent()) throw new IllegalStateException(text(p, "Edit your profile in the lobby", "Edytuj profil w lobby"));
    }

    private static void button(Menu menu, int slot, Material material, String title, String description, Consumer<Player> action) {
        var item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(title, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.lore(Arrays.stream(description.split("\n")).map(line -> Component.text(line, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)).toList());
        });
        menu.inventory.setItem(slot, item);
        if (action != null) menu.actions.put(slot, action);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())
                || menu.busy || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) return;
        var action = menu.actions.get(event.getRawSlot());
        if (action == null) return;
        menu.busy = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory) return;
                if (!player.hasPermission("heist.play")) { player.closeInventory(); return; }
                action.accept(player);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                player.sendMessage(Component.text(text(player, "Action unavailable: ", "Akcja niedostępna: ") + failure.getMessage(), NamedTextColor.RED));
                open(player, menu.section, menu.page);
            } finally { menu.busy = false; }
        });
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler public void disable(org.bukkit.event.server.PluginDisableEvent event) {
        if (event.getPlugin() != plugin) return;
        for (var player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
    }

    public void tick() {
        if (++ticks % 10 != 0) return;
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu && !menu.busy) {
                if (!player.hasPermission("heist.play")) player.closeInventory();
                else if (profiles.status(player.getUniqueId()) != menu.status) open(player, menu.section, menu.page);
            }
        }
    }

    private static final class Menu implements InventoryHolder {
        private final UUID owner;
        private final String section;
        private final int page;
        private final ProfileSessions.Status status;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        private Inventory inventory;
        private boolean busy;
        private Menu(UUID owner, String section, int page, ProfileSessions.Status status) {
            this.owner = owner; this.section = section; this.page = page; this.status = status;
        }
        @Override public Inventory getInventory() { return Objects.requireNonNull(inventory); }
    }
}
