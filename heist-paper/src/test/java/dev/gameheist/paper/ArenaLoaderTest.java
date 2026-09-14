package dev.gameheist.paper;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.paper.config.ArenaLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class ArenaLoaderTest {
    @TempDir Path directory;

    private String sample() throws IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox.yml"))) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void bundledArenaPassesValidation() throws IOException {
        Files.writeString(directory.resolve("arena.yml"), sample());
        var registry = ArenaLoader.load(directory);
        var arena = registry.require(new ArenaKey("graybox", 1));
        assertEquals(4, arena.objectives().size());
        assertEquals(4, arena.capacity());
        assertEquals(65, arena.spawn().y());
    }
    @Test void brokenReferencesFailWithFilenameContext() throws IOException {
        Files.writeString(directory.resolve("broken.yml"), sample().replace("[drill]", "[missing]"));
        var error = assertThrows(IOException.class, () -> ArenaLoader.load(directory));
        assertTrue(error.getMessage().contains("broken.yml"));
        assertTrue(error.getMessage().contains("Missing prerequisite"));
    }
    @Test void malformedYamlFailsInsteadOfCreatingPartialArena() throws IOException {
        Files.writeString(directory.resolve("broken.yml"), "id: [unterminated");
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void emptyDirectoryDoesNotEnableAnUnusablePlugin() {
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    private String physicalSample() throws IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox-v2.yml"))) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void newPhysicalArenaLoadsAlongsideLegacyVersion() throws IOException {
        Files.writeString(directory.resolve("v1.yml"), sample());
        Files.writeString(directory.resolve("v2.yml"), physicalSample());
        var registry = ArenaLoader.load(directory);
        assertEquals(2, registry.all().size());
        assertTrue(registry.require(new ArenaKey("graybox", 1)).heist().isEmpty());
        var heist = registry.require(new ArenaKey("graybox", 2)).heist().orElseThrow();
        assertEquals(5, heist.bags().size());
        assertEquals(3, heist.requiredBags());
        assertEquals(90, heist.drillDuration().toSeconds());
        assertEquals(2, heist.jams());
    }
    @Test void overlappingMarkersAreRejected() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), physicalSample().replace("[-20, 65, 9]", "[0, 65, 0]"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void unreachableLootRequirementIsRejected() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), physicalSample().replace("required-bags: 3", "required-bags: 6"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void fractionalTimerIsNotSilentlyRounded() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), physicalSample().replace("repair-seconds: 6", "repair-seconds: 6.5"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void markersOutsideBoundsAreRejected() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), physicalSample().replace("[-20, 65, 9]", "[100, 65, 9]"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    private String guardedSample() throws IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox-v3.yml"))) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void guardedArenaHasTwoValidatedRoutesAndCover() throws IOException {
        Files.writeString(directory.resolve("v3.yml"), guardedSample());
        var arena = ArenaLoader.load(directory).require(new ArenaKey("graybox", 3));
        assertEquals(2, arena.guards().size());
        assertEquals(18, arena.cover().size());
        assertEquals(4, arena.guards().getFirst().patrol().size());
        assertTrue(arena.heist().isPresent());
    }
    @Test void combatArenaLoadsWithoutChangingExistingVersion() throws IOException {
        Files.writeString(directory.resolve("v3.yml"), guardedSample());
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox-v4.yml"))) {
            Files.copy(stream, directory.resolve("v4.yml"));
        }
        var registry = ArenaLoader.load(directory);
        assertFalse(registry.require(new ArenaKey("graybox", 3)).combat());
        var arena = registry.require(new ArenaKey("graybox", 4));
        assertTrue(arena.combat());
        assertEquals(2, arena.guards().size());
        assertEquals(3, arena.heist().orElseThrow().requiredBags());
    }
    @Test void combatFlagMustBeBooleanAndRequiresGuardsAndPhysicalObjectives() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), guardedSample() + "\ncombat: yesplease\n");
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
        Files.writeString(directory.resolve("bad.yml"), physicalSample() + "\ncombat: true\n");
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void guardRouteOutsideBoundsIsRejected() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), guardedSample().replace("[4.5, 65.0, -10.5]", "[100.5, 65.0, -10.5]"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void duplicateGuardIdsAreRejected() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), guardedSample().replace("id: hall_guard", "id: vault_guard"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void coverCannotObstructGuardSpawn() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), guardedSample().replace("[-6, 65, -1]", "[4, 65, -11]"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void invalidGuardTimingOrRangeIsRejected() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), guardedSample().replace("detection-seconds: 3", "detection-seconds: 0"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
        Files.writeString(directory.resolve("bad.yml"), guardedSample().replace("sight-range: 14", "sight-range: 1000"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
    @Test void objectiveCannotObstructGuardSpawn() throws IOException {
        Files.writeString(directory.resolve("bad.yml"), guardedSample().replace("[4.5, 65.0, -10.5]", "[0.5, 65.0, 0.5]"));
        assertThrows(IOException.class, () -> ArenaLoader.load(directory));
    }
}
