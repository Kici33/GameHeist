package dev.gameheist.paper.world;

import dev.gameheist.domain.arena.BlockPosition;
import dev.gameheist.domain.objective.HeistDefinition;
import dev.gameheist.domain.objective.HeistSnapshot;
import dev.gameheist.paper.pack.PackModel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import java.util.*;

/** Match-owned visual props. Existing marker blocks remain the authoritative interaction targets. */
public final class HeistProps implements AutoCloseable {
    private final World world;
    private final HeistDefinition definition;
    private final Map<BlockPosition, Prop> props = new HashMap<>();
    public HeistProps(World world, HeistDefinition definition) { this.world = world; this.definition = definition; }

    public static Map<BlockPosition, PackModel> models(HeistDefinition definition, HeistSnapshot state) {
        Map<BlockPosition, PackModel> wanted = new HashMap<>();
        wanted.put(definition.security(), state.securityDisabled() ? PackModel.SECURITY_DISABLED : PackModel.SECURITY_TERMINAL);
        wanted.put(definition.drill(), state.drillComplete() ? PackModel.DRILL_COMPLETE : state.jammed() ? PackModel.DRILL_JAMMED
                : state.drillStarted() ? PackModel.DRILL_RUNNING : PackModel.DRILL_IDLE);
        wanted.put(definition.extraction(), PackModel.EXTRACTION_BEACON);
        for (var bag : definition.bags()) if (!state.unavailableBags().contains(bag)) wanted.put(bag, PackModel.LOOT_BAG);
        return Map.copyOf(wanted);
    }
    public void update(HeistSnapshot state) {
        Map<BlockPosition, PackModel> wanted = models(definition, state);
        for (var position : Set.copyOf(props.keySet())) {
            if (!wanted.containsKey(position)) props.remove(position).entity().remove();
        }
        for (var entry : wanted.entrySet()) {
            var existing = props.get(entry.getKey());
            if (existing != null && !existing.entity().isValid()) {
                existing.entity().remove();
                props.remove(entry.getKey());
                existing = null;
            }
            if (existing == null) {
                var position = entry.getKey();
                var partial = new java.util.concurrent.atomic.AtomicReference<ItemDisplay>();
                try {
                    var entity = world.spawn(new Location(world, position.x() + .5, position.y() + 1.5, position.z() + .5),
                            ItemDisplay.class, display -> {
                                partial.set(display);
                                display.setPersistent(false);
                                display.setInvulnerable(true);
                                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                                display.setItemStack(entry.getValue().item(entry.getValue().id()));
                            });
                    if (!entity.isValid()) throw new IllegalStateException("Model display spawn rejected");
                    props.put(position, new Prop(entity, entry.getValue()));
                } catch (RuntimeException failure) {
                    if (partial.get() != null) partial.get().remove();
                    throw failure;
                }
            } else if (existing.model() != entry.getValue()) {
                existing.entity().setItemStack(entry.getValue().item(entry.getValue().id()));
                props.put(entry.getKey(), new Prop(existing.entity(), entry.getValue()));
            }
        }
    }
    @Override public void close() {
        props.values().forEach(prop -> prop.entity().remove());
        props.clear();
    }
    private record Prop(ItemDisplay entity, PackModel model) { }
}
