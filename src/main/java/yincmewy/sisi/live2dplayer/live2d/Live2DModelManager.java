package yincmewy.sisi.live2dplayer.live2d;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class Live2DModelManager {
    public List<Live2DModel> refresh() {
        ensureUserDirectory();
        return scanModels();
    }

    public void ensureUserDirectory() {
        Live2DConfig.ensureDirectories();
    }

    private List<Live2DModel> scanModels() {
        List<Live2DModel> models = new ArrayList<>();
        try (Stream<Path> stream = Files.list(Live2DConfig.MODEL_DIR)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(folder -> models.add(parseFolder(folder)));
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return models;
    }

    private Live2DModel parseFolder(Path folder) {
        String name = folder.getFileName().toString();
        Path modelJson = findModelJson(folder);
        if (modelJson == null) {
            return new Live2DModel(name, folder, null, null, null, null, findIcon(folder, folder), List.of(),
                    List.of(), List.of(), List.of(), "未找到 .model3.json");
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(modelJson, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject refs = root.has("FileReferences") && root.get("FileReferences").isJsonObject()
                    ? root.getAsJsonObject("FileReferences")
                    : new JsonObject();
            Path base = modelJson.getParent();
            Path moc = resolveOptional(base, refs, "Moc");
            Path physics = resolveOptional(base, refs, "Physics");
            Path displayInfo = resolveOptional(base, refs, "DisplayInfo");
            List<Path> textures = readTextures(base, refs);
            List<Live2DMotion> motions = readMotions(base, refs);
            List<Live2DExpression> expressions = readExpressions(base, refs);
            List<Live2DParameterGroup> groups = readGroups(root);
            Path icon = findIcon(folder, base);

            String error = "";
            if (moc == null) {
                error = "model3 缺少 Moc 或文件不存在";
            } else if (textures.isEmpty()) {
                error = "model3 未声明可用纹理";
            }
            return new Live2DModel(name, folder, modelJson, moc, physics, displayInfo, icon, textures,
                    motions, expressions, groups, error);
        } catch (Exception exception) {
            return new Live2DModel(name, folder, modelJson, null, null, null, findIcon(folder, modelJson.getParent()),
                    List.of(), List.of(), List.of(), List.of(), exception.getMessage());
        }
    }

    private Path findModelJson(Path folder) {
        try (Stream<Path> stream = Files.walk(folder, 3)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".model3.json"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Path> readTextures(Path base, JsonObject refs) {
        List<Path> textures = new ArrayList<>();
        if (!refs.has("Textures") || !refs.get("Textures").isJsonArray()) {
            return textures;
        }

        for (JsonElement element : refs.getAsJsonArray("Textures")) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            Path path = safeResolve(base, element.getAsString());
            if (Files.exists(path)) {
                textures.add(path);
            }
        }
        return textures;
    }

    private List<Live2DExpression> readExpressions(Path base, JsonObject refs) {
        Set<Path> files = new LinkedHashSet<>();
        if (refs.has("Expressions") && refs.get("Expressions").isJsonArray()) {
            for (JsonElement element : refs.getAsJsonArray("Expressions")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (object.has("File")) {
                    Path path = safeResolve(base, object.get("File").getAsString());
                    if (Files.exists(path)) {
                        files.add(path.normalize());
                    }
                }
            }
        }

        try (Stream<Path> stream = Files.walk(base, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exp3.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> files.add(path.normalize()));
        } catch (Exception ignored) {
        }

        List<Live2DExpression> expressions = new ArrayList<>();
        for (Path file : files) {
            try {
                expressions.add(Live2DExpression.parse(file));
            } catch (Exception ignored) {
            }
        }
        return expressions;
    }

    private List<Live2DMotion> readMotions(Path base, JsonObject refs) {
        Map<Path, MotionRef> files = new LinkedHashMap<>();
        if (refs.has("Motions") && refs.get("Motions").isJsonObject()) {
            JsonObject motions = refs.getAsJsonObject("Motions");
            for (Map.Entry<String, JsonElement> entry : motions.entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                String groupName = entry.getKey();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    if (!object.has("File")) {
                        continue;
                    }
                    Path path = safeResolve(base, object.get("File").getAsString());
                    if (Files.exists(path)) {
                        boolean loop = "Idle".equalsIgnoreCase(groupName) || bool(object, "Loop", false);
                        float fadeIn = number(object, "FadeInTime", 0.0F);
                        files.put(path.normalize(), new MotionRef(path, groupName, loop, fadeIn));
                    }
                }
            }
        }

        try (Stream<Path> stream = Files.walk(base, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".motion3.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> files.putIfAbsent(path.normalize(),
                            new MotionRef(path, motionName(path), true, 0.0F)));
        } catch (Exception ignored) {
        }

        List<Live2DMotion> motions = new ArrayList<>();
        for (MotionRef ref : files.values()) {
            try {
                motions.add(Live2DMotion.parse(ref.file(), ref.name(), ref.loop(), ref.fadeInSeconds()));
            } catch (Exception ignored) {
            }
        }
        return motions;
    }

    private List<Live2DParameterGroup> readGroups(JsonObject root) {
        List<Live2DParameterGroup> groups = new ArrayList<>();
        if (!root.has("Groups") || !root.get("Groups").isJsonArray()) {
            return groups;
        }

        JsonArray array = root.getAsJsonArray("Groups");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("Name")) {
                List<String> ids = new ArrayList<>();
                if (object.has("Ids") && object.get("Ids").isJsonArray()) {
                    for (JsonElement idElement : object.getAsJsonArray("Ids")) {
                        if (idElement.isJsonPrimitive()) {
                            ids.add(idElement.getAsString());
                        }
                    }
                }
                groups.add(new Live2DParameterGroup(string(object, "Target", "Parameter"),
                        object.get("Name").getAsString(), ids));
            }
        }
        return groups;
    }

    private Path resolveOptional(Path base, JsonObject refs, String key) {
        if (!refs.has(key)) {
            return null;
        }
        Path path = safeResolve(base, refs.get(key).getAsString());
        return Files.exists(path) ? path : null;
    }

    private Path findIcon(Path folder, Path modelBase) {
        Path icon = folder.resolve("icon.png");
        if (Files.exists(icon)) {
            return icon;
        }
        if (modelBase != null) {
            icon = modelBase.resolve("icon.png");
            if (Files.exists(icon)) {
                return icon;
            }
        }

        try (Stream<Path> stream = Files.walk(folder, 2)) {
            Path vtube = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vtube.json"))
                    .findFirst()
                    .orElse(null);
            if (vtube != null) {
                JsonObject root = JsonParser.parseString(Files.readString(vtube, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject refs = root.has("FileReferences") && root.get("FileReferences").isJsonObject()
                        ? root.getAsJsonObject("FileReferences")
                        : new JsonObject();
                if (refs.has("Icon")) {
                    Path resolved = safeResolve(vtube.getParent(), refs.get("Icon").getAsString());
                    if (Files.exists(resolved)) {
                        return resolved;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Path safeResolve(Path base, String relative) {
        if (relative == null || relative.isBlank()) {
            return base;
        }
        return base.resolve(relative.replace('\\', '/')).normalize();
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private float number(JsonObject object, String key, float fallback) {
        try {
            return object.has(key) ? object.get(key).getAsFloat() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String motionName(Path path) {
        String fileName = path == null || path.getFileName() == null ? "Motion" : path.getFileName().toString();
        return fileName.toLowerCase(Locale.ROOT).endsWith(".motion3.json")
                ? fileName.substring(0, fileName.length() - ".motion3.json".length())
                : fileName;
    }

    private record MotionRef(Path file, String name, boolean loop, float fadeInSeconds) {
    }
}
