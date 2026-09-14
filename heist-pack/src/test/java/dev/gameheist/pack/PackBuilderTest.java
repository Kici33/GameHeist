package dev.gameheist.pack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackBuilderTest {
    @Test void missingAudioSubtitlesAndInvalidCodecFailPackaging() throws IOException {
        var files = pack();
        files.remove("assets/gameheist/sounds/alarm.ogg");
        assertThrows(IOException.class, () -> PackBuilder.validate(files));
        var subtitles = pack();
        subtitles.put("assets/gameheist/lang/pl_pl.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PackBuilder.validate(subtitles));
        var codec = pack();
        codec.put("assets/gameheist/sounds/alarm.ogg", new byte[100]);
        assertThrows(IOException.class, () -> PackBuilder.validate(codec));
    }
    private SortedMap<String, byte[]> pack() throws IOException { return PackBuilder.assemble(Path.of(System.getProperty("pack.source"))); }
    @Test void buildsClosedAssetGraphWithAnimatedWorkLightAndNoVanillaOverrides() throws IOException {
        var files = pack();
        assertEquals(9, files.keySet().stream().filter(p -> p.startsWith("assets/gameheist/items/")).count());
        assertTrue(files.keySet().stream().noneMatch(p -> p.startsWith("assets/minecraft/")));
        assertTrue(files.containsKey("LICENSE.txt"));
        var light = ImageIO.read(new ByteArrayInputStream(files.get("assets/gameheist/textures/item/palette/pulse.png")));
        assertEquals(16, light.getWidth());
        assertEquals(32, light.getHeight());
        assertNotEquals(light.getRGB(8, 8), light.getRGB(8, 24));
        assertTrue(files.containsKey("assets/gameheist/textures/item/palette/pulse.png.mcmeta"));
    }
    @Test void archiveIsReproducibleAndHasMetadataAtRoot() throws IOException {
        byte[] first = PackBuilder.zip(pack()), second = PackBuilder.zip(pack());
        assertArrayEquals(first, second);
        Set<String> paths = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(first))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertTrue(paths.add(entry.getName()));
                assertFalse(entry.getName().contains(".."));
            }
        }
        assertTrue(paths.contains("pack.mcmeta"));
        assertTrue(paths.contains("pack.png"));
        assertEquals(pack().keySet(), paths);
    }
    @Test void brokenTextureAndModelReferencesFailBeforePackaging() throws IOException {
        var files = pack();
        files.remove("assets/gameheist/textures/item/palette/metal.png");
        assertThrows(IOException.class, () -> PackBuilder.validate(files));
        var missingModel = pack();
        missingModel.remove("assets/gameheist/models/item/carbine.json");
        assertThrows(IOException.class, () -> PackBuilder.validate(missingModel));
    }
    @Test void incompatibleMetadataAndInvalidGeometryAreRejected() throws IOException {
        var files = pack();
        files.put("pack.mcmeta", "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0]}}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PackBuilder.validate(files));
        var invalid = pack();
        String path = "assets/gameheist/models/item/carbine.json";
        String model = new String(invalid.get(path), StandardCharsets.UTF_8);
        invalid.put(path, model.replace("20,", "200,").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PackBuilder.validate(invalid));
    }
    @Test void malformedJsonAndUnsafePathsAreRejected() throws IOException {
        var files = pack();
        files.put("assets/gameheist/items/carbine.json", "{broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PackBuilder.validate(files));
        var unsafe = pack();
        unsafe.put("../escape", new byte[0]);
        assertThrows(IOException.class, () -> PackBuilder.validate(unsafe));
    }
}
