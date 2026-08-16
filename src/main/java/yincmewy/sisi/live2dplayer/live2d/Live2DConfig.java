package yincmewy.sisi.live2dplayer.live2d;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import yincmewy.sisi.live2dplayer.Live2dplayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class Live2DConfig {
    private static final int CURRENT_CONFIG_VERSION = 2;

    public enum MotionMode {
        AUTO,
        SELECTED,
        RANDOM,
        OFF
    }

    public static final Path ROOT_DIR = FMLPaths.CONFIGDIR.get().resolve(Live2dplayer.MODID);
    public static final Path CORE_DIR = ROOT_DIR.resolve("core");
    public static final Path MODEL_DIR = ROOT_DIR.resolve("model");
    public static final Path CACHE_DIR = ROOT_DIR.resolve("cache");
    public static final Path CONFIG_PATH = ROOT_DIR.resolve("config.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public boolean enabled = false;
    public String selectedModel = "";
    public String selectedExpression = "";
    public final List<String> selectedExpressions = new ArrayList<>();
    public String selectedMotion = "Idle";
    public MotionMode motionMode = MotionMode.AUTO;

    public float x = 18.0F;
    public float y = 72.0F;
    public float scale = 0.28F;
    public float alpha = 1.0F;
    public float mouseStrength = 1.0F;
    public float idleSpeed = 1.0F;
    public float motionScale = 1.0F;
    public float physicsScale = 1.0F;
    public int renderIntervalFrames = 2;

    public boolean mirror = false;
    public boolean followMouse = false;
    public boolean autoBlink = true;
    public boolean physicsEnabled = true;
    public boolean lipSync = false;
    public boolean showStatus = false;
    public boolean dragEnabled = true;
    public boolean clickInteraction = true;
    public boolean randomExpressionOnClick = false;
    public boolean renderInScreens = true;
    public boolean editMode = false;

    public static Live2DConfig load() {
        Live2DConfig config = new Live2DConfig();
        ensureDirectories();
        if (!Files.exists(CONFIG_PATH)) {
            return config;
        }

        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            int configVersion = intValue(root, "configVersion", 0);
            config.enabled = bool(root, "enabled", config.enabled);
            config.selectedModel = string(root, "selectedModel", config.selectedModel);
            config.selectedExpression = string(root, "selectedExpression", config.selectedExpression);
            config.selectedExpressions.clear();
            if (root.has("selectedExpressions") && root.get("selectedExpressions").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("selectedExpressions")) {
                    if (element.isJsonPrimitive()) {
                        String value = element.getAsString();
                        if (value != null && !value.isBlank()) {
                            config.selectedExpressions.add(value);
                        }
                    }
                }
            } else if (config.selectedExpression != null && !config.selectedExpression.isBlank()) {
                config.selectedExpressions.add(config.selectedExpression);
            }
            config.selectedMotion = string(root, "selectedMotion", config.selectedMotion);
            config.motionMode = enumValue(root, "motionMode", config.motionMode);
            config.x = clamp(number(root, "x", config.x), -4096.0F, 4096.0F);
            config.y = clamp(number(root, "y", config.y), -4096.0F, 4096.0F);
            config.scale = clamp(number(root, "scale", config.scale), 0.03F, 4.0F);
            config.alpha = clamp(number(root, "alpha", config.alpha), 0.0F, 1.0F);
            config.mouseStrength = clamp(number(root, "mouseStrength", config.mouseStrength), 0.0F, 3.0F);
            config.idleSpeed = clamp(number(root, "idleSpeed", config.idleSpeed), 0.0F, 5.0F);
            config.motionScale = clamp(number(root, "motionScale", config.motionScale), 0.0F, 3.0F);
            config.physicsScale = clamp(number(root, "physicsScale", config.physicsScale), 0.0F, 5.0F);
            config.renderIntervalFrames = clampInt(
                    intValue(root, "renderIntervalFrames", config.renderIntervalFrames),
                    1, 6
            );
            config.mirror = bool(root, "mirror", config.mirror);
            config.followMouse = bool(root, "followMouse", config.followMouse);
            config.autoBlink = bool(root, "autoBlink", config.autoBlink);
            config.physicsEnabled = bool(root, "physicsEnabled", config.physicsEnabled);
            config.lipSync = bool(root, "lipSync", config.lipSync);
            config.showStatus = bool(root, "showStatus", config.showStatus);
            config.dragEnabled = bool(root, "dragEnabled", config.dragEnabled);
            config.clickInteraction = bool(root, "clickInteraction", config.clickInteraction);
            config.randomExpressionOnClick = bool(root, "randomExpressionOnClick", config.randomExpressionOnClick);
            config.renderInScreens = configVersion < CURRENT_CONFIG_VERSION
                    || bool(root, "renderInScreens", config.renderInScreens);
            config.editMode = bool(root, "editMode", config.editMode);
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        return config;
    }

    public void save() {
        try {
            ensureDirectories();
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CURRENT_CONFIG_VERSION);
            root.addProperty("enabled", enabled);
            root.addProperty("selectedModel", text(selectedModel));
            root.addProperty("selectedExpression", text(selectedExpression));
            JsonArray expressions = new JsonArray();
            for (String expression : selectedExpressions) {
                if (expression != null && !expression.isBlank()) {
                    expressions.add(expression);
                }
            }
            root.add("selectedExpressions", expressions);
            root.addProperty("selectedMotion", text(selectedMotion));
            root.addProperty("motionMode", motionMode == null ? MotionMode.AUTO.name() : motionMode.name());
            root.addProperty("x", x);
            root.addProperty("y", y);
            root.addProperty("scale", scale);
            root.addProperty("alpha", alpha);
            root.addProperty("mouseStrength", mouseStrength);
            root.addProperty("idleSpeed", idleSpeed);
            root.addProperty("motionScale", motionScale);
            root.addProperty("physicsScale", physicsScale);
            root.addProperty("renderIntervalFrames", renderIntervalFrames);
            root.addProperty("mirror", mirror);
            root.addProperty("followMouse", followMouse);
            root.addProperty("autoBlink", autoBlink);
            root.addProperty("physicsEnabled", physicsEnabled);
            root.addProperty("lipSync", lipSync);
            root.addProperty("showStatus", showStatus);
            root.addProperty("dragEnabled", dragEnabled);
            root.addProperty("clickInteraction", clickInteraction);
            root.addProperty("randomExpressionOnClick", randomExpressionOnClick);
            root.addProperty("renderInScreens", renderInScreens);
            root.addProperty("editMode", editMode);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public static void ensureDirectories() {
        try {
            Files.createDirectories(ROOT_DIR);
            Files.createDirectories(CORE_DIR);
            Files.createDirectories(MODEL_DIR);
            Files.createDirectories(CACHE_DIR);
            Live2DNativeLibraryInstaller.install(CORE_DIR);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public void resetTransform() {
        x = 18.0F;
        y = 72.0F;
        scale = 0.28F;
        alpha = 1.0F;
        mouseStrength = 1.0F;
        idleSpeed = 1.0F;
        motionScale = 1.0F;
        physicsScale = 1.0F;
        mirror = false;
        followMouse = false;
    }

    public void clampToScreen(int width, int height) {
        x = clamp(x, -256.0F, Math.max(256.0F, width + 256.0F));
        y = clamp(y, -256.0F, Math.max(256.0F, height + 256.0F));
        scale = clamp(scale, 0.03F, 4.0F);
        alpha = clamp(alpha, 0.0F, 1.0F);
        mouseStrength = clamp(mouseStrength, 0.0F, 3.0F);
        idleSpeed = clamp(idleSpeed, 0.0F, 5.0F);
        motionScale = clamp(motionScale, 0.0F, 3.0F);
        physicsScale = clamp(physicsScale, 0.0F, 5.0F);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
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

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static MotionMode enumValue(JsonObject object, String key, MotionMode fallback) {
        try {
            if (!object.has(key)) {
                return fallback;
            }
            return MotionMode.valueOf(object.get(key).getAsString().toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
