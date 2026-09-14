package dev.gameheist.paper;

import com.google.gson.JsonParser;
import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.objective.*;
import dev.gameheist.paper.config.ArenaLoader;
import dev.gameheist.paper.pack.PackModel;
import dev.gameheist.paper.world.HeistProps;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackContractTest {
    @Test void pluginSoundEventsMatchPack() throws IOException {
        var source = Path.of(System.getProperty("pack.source"));
        var events = JsonParser.parseString(Files.readString(source.resolve("assets/gameheist/sounds.json"))).getAsJsonObject();
        assertEquals(Arrays.stream(dev.gameheist.paper.pack.HeistAudio.Cue.values())
                .map(dev.gameheist.paper.pack.HeistAudio.Cue::id).collect(Collectors.toSet()), events.keySet());
    }
    @TempDir Path directory;
    private HeistDefinition definition() throws IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox-v4.yml"))) {
            Files.copy(stream, directory.resolve("v4.yml"));
        }
        return ArenaLoader.load(directory).require(new ArenaKey("graybox", 4)).heist().orElseThrow();
    }
    private HeistSnapshot state(boolean security, boolean started, boolean complete, boolean jammed, Set<BlockPosition> unavailable) {
        return new HeistSnapshot(security, started, complete, jammed, jammed ? 1 : 0, 0, Optional.empty(), 0,
                Map.of(), unavailable, 0, 3, 0, false, 0);
    }
    @Test void everyPluginModelHasOneMatchingPackItemDefinition() throws IOException {
        Path source = Path.of(System.getProperty("pack.source"));
        Set<String> expected = Arrays.stream(PackModel.values()).map(PackModel::id).collect(Collectors.toSet());
        try (var items = Files.list(source.resolve("assets/gameheist/items"))) {
            assertEquals(expected, items.map(p -> p.getFileName().toString().replace(".json", "")).collect(Collectors.toSet()));
        }
        for (var model : PackModel.values()) {
            assertEquals("gameheist:" + model.id(), model.key().toString());
            var json = JsonParser.parseString(Files.readString(source.resolve("assets/gameheist/items/" + model.id() + ".json"))).getAsJsonObject();
            String reference = json.getAsJsonObject("model").get("model").getAsString();
            assertEquals("gameheist:item/" + model.id(), reference);
            assertTrue(Files.exists(source.resolve("assets/gameheist/models/item/" + model.id() + ".json")));
        }
    }
    @Test void objectiveStatesSelectIdleRunningJammedCompleteAndDisabledModels() throws IOException {
        var definition = definition();
        assertEquals(PackModel.DRILL_IDLE, HeistProps.models(definition, state(false, false, false, false, Set.of())).get(definition.drill()));
        assertEquals(PackModel.DRILL_RUNNING, HeistProps.models(definition, state(true, true, false, false, Set.of())).get(definition.drill()));
        assertEquals(PackModel.DRILL_JAMMED, HeistProps.models(definition, state(true, true, false, true, Set.of())).get(definition.drill()));
        var complete = HeistProps.models(definition, state(true, true, true, false, Set.of()));
        assertEquals(PackModel.DRILL_COMPLETE, complete.get(definition.drill()));
        assertEquals(PackModel.SECURITY_DISABLED, complete.get(definition.security()));
        assertEquals(PackModel.EXTRACTION_BEACON, complete.get(definition.extraction()));
    }
    @Test void pickedUpBagsDisappearAndReturnedBagsBecomeVisibleAgain() throws IOException {
        var definition = definition();
        var bag = definition.bags().getFirst();
        var available = HeistProps.models(definition, state(true, true, true, false, Set.of()));
        var taken = HeistProps.models(definition, state(true, true, true, false, Set.of(bag)));
        var returned = HeistProps.models(definition, state(true, true, true, false, Set.of()));
        assertEquals(PackModel.LOOT_BAG, available.get(bag));
        assertFalse(taken.containsKey(bag));
        assertEquals(available, returned);
        assertThrows(UnsupportedOperationException.class, returned::clear);
    }
}
