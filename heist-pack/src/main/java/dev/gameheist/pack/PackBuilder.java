package dev.gameheist.pack;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Offline pack compiler: original palette pixels + authored JSON, with no downloaded assets. */
public final class PackBuilder {
    public static final String ARCHIVE = "gameheist-brasslock-1.1.zip";
    private static final String ROOT = "assets/gameheist/";
    private PackBuilder() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected source and output directories");
        Path source = Path.of(args[0]), output = Path.of(args[1]);
        SortedMap<String, byte[]> files = assemble(source);
        Files.createDirectories(output);
        byte[] zip = zip(files);
        Files.write(output.resolve(ARCHIVE), zip);
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        Files.writeString(output.resolve(ARCHIVE + ".sha1"), sha1 + "\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve(ARCHIVE + ".sha256"), sha256 + "\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("server-config.yml"), """
                # Upload the ZIP to an immutable HTTPS download URL and fill url below.
                resource-pack:
                  development-bypass: false
                  preview-models: false
                  id: '873f1299-715e-430a-ae57-a43b96f20e92'
                  url: ''
                  sha1: '%s'
                  timeout-seconds: 60
                """.formatted(sha1), StandardCharsets.UTF_8);
        preview(files, output.resolve("models-preview.png"));
        System.out.println("Validated " + files.size() + " entries: " + output.resolve(ARCHIVE));
        System.out.println("SHA-1 " + sha1);
    }

    public static SortedMap<String, byte[]> assemble(Path source) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        for (String name : List.of("pack.mcmeta", "LICENSE.txt")) files.put(name, Files.readAllBytes(source.resolve(name)));
        try (var paths = Files.walk(source.resolve("assets"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink in pack source");
                String name = source.relativize(path).toString().replace('\\', '/');
                if (!name.matches("assets/gameheist/((models/item|items|lang)/[a-z0-9_]+\\.json|sounds\\.json|sounds/[a-z0-9_]+\\.ogg)")) {
                    throw new IOException("Unexpected pack source: " + name);
                }
                files.put(name, Files.readAllBytes(path));
            }
        }
        var palette = json(Files.readAllBytes(source.resolve("palette.json")));
        for (var entry : palette.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_]+") || !entry.getValue().getAsString().matches("[0-9A-F]{6}")) {
                throw new IOException("Invalid palette entry " + entry.getKey());
            }
            boolean pulse = entry.getKey().equals("pulse");
            Color base = new Color(Integer.parseInt(entry.getValue().getAsString(), 16));
            var texture = new BufferedImage(16, pulse ? 32 : 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < texture.getHeight(); y++) for (int x = 0; x < 16; x++) {
                int noise = ((x * 17 + (y % 16) * 29) % 11) - 5;
                double shade = pulse && y >= 16 ? 0.58 : 1;
                texture.setRGB(x, y, new Color(channel(base.getRed(), noise, shade), channel(base.getGreen(), noise, shade),
                        channel(base.getBlue(), noise, shade)).getRGB());
            }
            String path = ROOT + "textures/item/palette/" + entry.getKey() + ".png";
            files.put(path, png(texture));
            if (pulse) files.put(path + ".mcmeta", "{\"animation\":{\"frametime\":6,\"interpolate\":false,\"frames\":[0,1]}}".getBytes(StandardCharsets.UTF_8));
        }
        files.put("pack.png", png(icon()));
        validate(files);
        return files;
    }

    public static void validate(Map<String, byte[]> files) throws IOException {
        var pack = json(required(files, "pack.mcmeta")).getAsJsonObject("pack");
        for (String bound : List.of("min_format", "max_format")) {
            if (!JsonParser.parseString("[84,0]").equals(pack.get(bound))) throw new IOException("Pack must target format 84.0");
        }
        int items = 0;
        for (var entry : files.entrySet()) {
            String path = entry.getKey();
            if (path.contains("..") || path.startsWith("/") || path.contains("\\")) throw new IOException("Unsafe ZIP path");
            if (path.endsWith(".json") || path.endsWith(".mcmeta")) json(entry.getValue());
            if (path.startsWith(ROOT + "items/")) {
                items++;
                var model = json(entry.getValue()).getAsJsonObject("model");
                if (!model.get("type").getAsString().equals("minecraft:model")) throw new IOException("Unsupported item type");
                required(files, resource(model.get("model").getAsString(), "models", ".json"));
            }
            if (path.startsWith(ROOT + "models/")) validateModel(files, json(entry.getValue()));
            if (path.endsWith(".png")) {
                var image = ImageIO.read(new ByteArrayInputStream(entry.getValue()));
                if (image == null || (image.getWidth() != 16 && !path.equals("pack.png"))) throw new IOException("Invalid texture " + path);
                if (!path.equals("pack.png") && image.getHeight() != 16 && image.getHeight() != 32) throw new IOException("Invalid texture height");
            }
        }
        if (items == 0) throw new IOException("Pack has no item definitions");
        validateSounds(files);
    }

    private static void validateSounds(Map<String, byte[]> files) throws IOException {
        var sounds = json(required(files, ROOT + "sounds.json"));
        for (var event : sounds.entrySet()) {
            var value = event.getValue().getAsJsonObject();
            String subtitle = value.get("subtitle").getAsString();
            for (String language : List.of("en_us", "pl_pl")) {
                var translations = json(required(files, ROOT + "lang/" + language + ".json"));
                if (!translations.has(subtitle) || translations.get(subtitle).getAsString().isBlank())
                    throw new IOException("Missing sound subtitle " + subtitle);
            }
            for (var sound : value.getAsJsonArray("sounds")) {
                byte[] ogg = required(files, resource(sound.getAsJsonObject().get("name").getAsString(), "sounds", ".ogg"));
                if (ogg.length < 58 || !new String(ogg, 0, 4, StandardCharsets.US_ASCII).equals("OggS"))
                    throw new IOException("Invalid OGG header");
                int packet = 27 + Byte.toUnsignedInt(ogg[26]);
                if (packet + 30 > ogg.length || ogg[packet] != 1
                        || !new String(ogg, packet + 1, 6, StandardCharsets.US_ASCII).equals("vorbis")
                        || ogg[packet + 11] != 1)
                    throw new IOException("Sound must be mono OGG Vorbis");
            }
        }
    }

    private static void validateModel(Map<String, byte[]> files, JsonObject model) throws IOException {
        var textures = model.getAsJsonObject("textures");
        for (var texture : textures.entrySet()) {
            String id = texture.getValue().getAsString();
            if (!id.startsWith("gameheist:item/")) throw new IOException("All model textures must use the item atlas");
            required(files, resource(id, "textures", ".png"));
        }
        var elements = model.getAsJsonArray("elements");
        if (elements == null || elements.isEmpty() || elements.size() > 64) throw new IOException("Invalid element count");
        for (var value : elements) {
            var element = value.getAsJsonObject();
            var from = element.getAsJsonArray("from");
            var to = element.getAsJsonArray("to");
            if (from.size() != 3 || to.size() != 3) throw new IOException("Invalid cuboid");
            for (int axis = 0; axis < 3; axis++) {
                double low = from.get(axis).getAsDouble(), high = to.get(axis).getAsDouble();
                if (!Double.isFinite(low) || !Double.isFinite(high) || low < -16 || high > 32 || low >= high) throw new IOException("Invalid cuboid bounds");
            }
            var faces = element.getAsJsonObject("faces");
            for (String face : List.of("north", "south", "east", "west", "up", "down")) {
                String reference = faces.getAsJsonObject(face).get("texture").getAsString();
                if (!reference.startsWith("#") || !textures.has(reference.substring(1))) throw new IOException("Unresolved face texture");
            }
        }
    }
    private static String resource(String id, String directory, String extension) throws IOException {
        if (!id.matches("gameheist:[a-z0-9_/]+") || id.contains("..")) throw new IOException("External or invalid asset reference " + id);
        return ROOT + directory + "/" + id.substring("gameheist:".length()) + extension;
    }
    private static byte[] required(Map<String, byte[]> files, String path) throws IOException {
        byte[] bytes = files.get(path);
        if (bytes == null) throw new IOException("Missing asset " + path);
        return bytes;
    }
    private static JsonObject json(byte[] bytes) throws IOException {
        try (var reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            var parsed = JsonParser.parseReader(reader).getAsJsonObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON data");
            return parsed;
        } catch (RuntimeException invalid) { throw new IOException("Invalid pack JSON", invalid); }
    }
    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : new TreeMap<>(files).entrySet()) {
                var item = new ZipEntry(entry.getKey());
                item.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
                item.setMethod(ZipEntry.STORED);
                item.setSize(entry.getValue().length);
                var crc = new CRC32();
                crc.update(entry.getValue());
                item.setCrc(crc.getValue());
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static int channel(int value, int noise, double shade) { return Math.clamp((int) ((value + noise) * shade), 0, 255); }
    private static byte[] png(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder unavailable");
        return bytes.toByteArray();
    }
    private static BufferedImage icon() {
        var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new Color(0x141D23)); g.fillRect(0, 0, 128, 128);
        g.setColor(new Color(0xCA9A48)); g.fillRect(20, 20, 88, 88);
        g.setColor(new Color(0x27343B)); g.fillRect(28, 28, 72, 72);
        g.setColor(new Color(0x889DA4)); g.fillRect(40, 40, 48, 48);
        g.setColor(new Color(0x141D23)); g.fillRect(48, 48, 32, 32);
        g.setColor(new Color(0x55DAE0)); g.fillRect(58, 38, 12, 52); g.fillRect(38, 58, 52, 12);
        g.dispose();
        return image;
    }

    /** Isometric source-model overview; this is not a Minecraft client screenshot. */
    private static void preview(SortedMap<String, byte[]> files, Path output) throws IOException {
        var models = files.entrySet().stream().filter(e -> e.getKey().startsWith(ROOT + "models/item/")).toList();
        int rows = (models.size() + 2) / 3;
        var image = new BufferedImage(1200, 110 + rows * 290, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x101A20)); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(new Color(0xE8E0C8)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.drawString("GAMEHEIST / BRASSLOCK", 30, 45);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); g.setColor(new Color(0x889DA4));
        g.drawString("ORIGINAL CUBOID MODELS  /  16 PX PALETTE TEXTURES  /  SOURCE PREVIEW", 32, 76);
        for (int i = 0; i < models.size(); i++) {
            int left = 20 + (i % 3) * 395, top = 105 + (i / 3) * 290;
            g.setColor(new Color(0x1C2930)); g.fillRoundRect(left, top, 380, 274, 12, 12);
            var model = json(models.get(i).getValue());
            var elements = new ArrayList<JsonObject>();
            model.getAsJsonArray("elements").forEach(e -> elements.add(e.getAsJsonObject()));
            elements.sort(Comparator.comparingDouble(e -> e.getAsJsonArray("from").get(0).getAsDouble() - e.getAsJsonArray("from").get(2).getAsDouble()));
            for (var element : elements) drawBox(g, element, model, files, left + 190, top + 160);
            String name = models.get(i).getKey().substring((ROOT + "models/item/").length()).replace(".json", "").replace('_', ' ').toUpperCase(Locale.ROOT);
            g.setColor(new Color(0xE8E0C8)); g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
            g.drawString(name, left + 18, top + 251);
        }
        g.dispose();
        Files.write(output, png(image));
    }
    private static void drawBox(Graphics2D g, JsonObject element, JsonObject model, Map<String, byte[]> files, int x, int y) throws IOException {
        var from = element.getAsJsonArray("from"); var to = element.getAsJsonArray("to");
        double a = from.get(0).getAsDouble(), b = from.get(1).getAsDouble(), c = from.get(2).getAsDouble();
        double d = to.get(0).getAsDouble(), e = to.get(1).getAsDouble(), f = to.get(2).getAsDouble();
        String texture = element.getAsJsonObject("faces").getAsJsonObject("up").get("texture").getAsString().substring(1);
        String id = model.getAsJsonObject("textures").get(texture).getAsString();
        var png = ImageIO.read(new ByteArrayInputStream(required(files, resource(id, "textures", ".png"))));
        Color color = new Color(png.getRGB(8, 8));
        face(g, new double[][]{{a,b,c},{d,b,c},{d,e,c},{a,e,c}}, color, .72, x, y);
        face(g, new double[][]{{d,b,c},{d,b,f},{d,e,f},{d,e,c}}, color, .9, x, y);
        face(g, new double[][]{{a,e,c},{d,e,c},{d,e,f},{a,e,f}}, color, 1.12, x, y);
    }
    private static void face(Graphics2D g, double[][] points, Color color, double shade, int x, int y) {
        var polygon = new Polygon();
        for (var p : points) polygon.addPoint(x + (int) ((p[0] + p[2] - 16) * 8), y + (int) ((p[0] - p[2]) * 3.4 - p[1] * 8));
        g.setColor(new Color(channel(color.getRed(), 0, shade), channel(color.getGreen(), 0, shade), channel(color.getBlue(), 0, shade)));
        g.fillPolygon(polygon);
    }
}
