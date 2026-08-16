package yincmewy.sisi.live2dplayer.live2d;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record Live2DExpression(String name, Path file, List<Parameter> parameters) {
    public static Live2DExpression parse(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        List<Parameter> parameters = new ArrayList<>();
        JsonArray array = root.has("Parameters") && root.get("Parameters").isJsonArray()
                ? root.getAsJsonArray("Parameters")
                : new JsonArray();

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            parameters.add(new Parameter(
                    string(object, "Id", ""),
                    number(object, "Value", 0.0F),
                    string(object, "Blend", "Add")
            ));
        }

        return new Live2DExpression(stripSuffix(file.getFileName().toString(), ".exp3.json"), file, List.copyOf(parameters));
    }

    private static String stripSuffix(String text, String suffix) {
        return text.endsWith(suffix) ? text.substring(0, text.length() - suffix.length()) : text;
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float number(JsonObject object, String key, float fallback) {
        try {
            return object.has(key) ? object.get(key).getAsFloat() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public record Parameter(String id, float value, String blend) {
    }
}
