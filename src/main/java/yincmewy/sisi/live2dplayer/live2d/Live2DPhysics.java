package yincmewy.sisi.live2dplayer.live2d;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Live2DPhysics {
    private static final float EPSILON = 0.0001F;
    private static final float MOVEMENT_THRESHOLD = 0.001F;
    private static final float MAX_WEIGHT = 100.0F;
    private static final float AIR_RESISTANCE = 5.0F;
    private static final float DEFAULT_PHYSICS_FPS = 60.0F;
    private static final float MIN_STEP_SECONDS = 1.0F / 60.0F;
    private static final float MAX_DELTA_SECONDS = 1.0F / 15.0F;
    private static final float MAX_REMAIN_SECONDS = 5.0F;
    private static final int MAX_SUB_STEPS = 2;

    private final List<Setting> settings;
    private final Vector2 gravity;
    private final Vector2 wind;
    private final float fps;
    private final Set<String> parameterIds;
    private final Map<String, Float> inputCaches = new HashMap<>();
    private final Map<String, Float> parameterCaches = new HashMap<>();
    private float currentRemainTime;
    private boolean initialized;

    private Live2DPhysics(List<Setting> settings, Vector2 gravity, Vector2 wind, float fps, Set<String> parameterIds) {
        this.settings = List.copyOf(settings);
        this.gravity = gravity;
        this.wind = wind;
        this.fps = fps;
        this.parameterIds = Set.copyOf(parameterIds);
    }

    public static Live2DPhysics parse(Path file) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject meta = object(root, "Meta");
        JsonObject forces = object(meta, "EffectiveForces");
        Vector2 gravity = vector(object(forces, "Gravity"), 0.0F, -1.0F);
        Vector2 wind = vector(object(forces, "Wind"), 0.0F, 0.0F);
        float fps = number(meta, "Fps", 0.0F);

        List<Setting> settings = new ArrayList<>();
        Set<String> parameterIds = new HashSet<>();
        for (JsonElement element : array(root, "PhysicsSettings")) {
            if (element.isJsonObject()) {
                Setting setting = Setting.parse(element.getAsJsonObject());
                if (setting != null) {
                    settings.add(setting);
                    setting.collectParameterIds(parameterIds);
                }
            }
        }

        return new Live2DPhysics(settings, gravity, wind, fps, parameterIds);
    }

    public boolean empty() {
        return settings.isEmpty();
    }

    public void update(Parameters parameters, float deltaSeconds) {
        update(parameters, deltaSeconds, 1.0F);
    }

    public void update(Parameters parameters, float deltaSeconds, float scale) {
        if (parameters == null || settings.isEmpty()) {
            return;
        }

        float delta = Float.isFinite(deltaSeconds) ? clamp(deltaSeconds, 0.0F, MAX_DELTA_SECONDS) : 0.0F;
        if (!initialized) {
            initialize(parameters);
        }

        float physicsDelta = physicsDelta(delta);
        float outputScale = Float.isFinite(scale) ? scale : 1.0F;
        currentRemainTime = Math.min(MAX_REMAIN_SECONDS, currentRemainTime + delta);
        int steps = 0;
        while (currentRemainTime >= physicsDelta && steps < MAX_SUB_STEPS) {
            updateParameterCaches(parameters, physicsDelta / currentRemainTime);
            for (Setting setting : settings) {
                setting.step(parameterCaches, parameters, gravity, wind, physicsDelta, outputScale);
            }
            currentRemainTime -= physicsDelta;
            steps++;
        }
        if (currentRemainTime >= physicsDelta) {
            currentRemainTime %= physicsDelta;
        }

        float alpha = clamp(currentRemainTime / physicsDelta, 0.0F, 1.0F);
        for (Setting setting : settings) {
            setting.applyInterpolated(parameters, alpha, outputScale);
        }
    }

    private void initialize(Parameters parameters) {
        inputCaches.clear();
        parameterCaches.clear();
        for (String id : parameterIds) {
            if (parameters.contains(id)) {
                float value = parameters.get(id);
                inputCaches.put(id, value);
                parameterCaches.put(id, value);
            }
        }
        for (Setting setting : settings) {
            setting.initialize();
        }
        currentRemainTime = 0.0F;
        initialized = true;
    }

    private void updateParameterCaches(Parameters parameters, float inputWeight) {
        float alpha = clamp(inputWeight, 0.0F, 1.0F);
        for (String id : parameterIds) {
            float previous = inputCaches.getOrDefault(id, parameters.get(id));
            float current = parameters.contains(id) ? parameters.get(id) : previous;
            float next = previous * (1.0F - alpha) + current * alpha;
            inputCaches.put(id, next);
            parameterCaches.put(id, next);
        }
    }

    private float physicsDelta(float currentDelta) {
        if (fps > EPSILON && Float.isFinite(fps)) {
            return 1.0F / clamp(fps, 1.0F, 60.0F);
        }
        return clamp(currentDelta, MIN_STEP_SECONDS, MAX_DELTA_SECONDS);
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key)
                : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key)
                : new JsonArray();
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
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

    private static Vector2 vector(JsonObject object, float fallbackX, float fallbackY) {
        return new Vector2(number(object, "X", fallbackX), number(object, "Y", fallbackY));
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }

    private static float normalizeParameter(float value, float minimum, float maximum,
                                            NormalizationRange normalized, boolean inverted) {
        float maxValue = Math.max(maximum, minimum);
        float minValue = Math.min(maximum, minimum);
        float middleValue = (minValue + maxValue) * 0.5F;
        float paramValue = clamp(value, minValue, maxValue);
        float normalizedMinimum = normalized.minimum();
        float normalizedMaximum = normalized.maximum();
        float normalizedDefault = normalized.defaults();

        float result;
        if (paramValue > middleValue) {
            float denominator = Math.max(EPSILON, maxValue - middleValue);
            float ratio = (paramValue - middleValue) / denominator;
            result = normalizedDefault + (normalizedMaximum - normalizedDefault) * ratio;
        } else if (paramValue < middleValue) {
            float denominator = Math.max(EPSILON, middleValue - minValue);
            float ratio = (paramValue - minValue) / denominator;
            result = normalizedMinimum + (normalizedDefault - normalizedMinimum) * ratio;
        } else {
            result = normalizedDefault;
        }

        result = clamp(result, Math.min(normalizedMinimum, normalizedMaximum),
                Math.max(normalizedMinimum, normalizedMaximum));
        return inverted ? result : -result;
    }

    private static float directionToRadian(Vector2 from, Vector2 to) {
        float result = (float) Math.atan2(to.y(), to.x()) - (float) Math.atan2(from.y(), from.x());
        while (result > Math.PI) {
            result -= (float) Math.PI * 2.0F;
        }
        while (result < -Math.PI) {
            result += (float) Math.PI * 2.0F;
        }
        return result;
    }

    private static Vector2 directionFromRadians(float radians) {
        return new Vector2((float) Math.sin(radians), (float) Math.cos(radians)).normalized();
    }

    public interface Parameters {
        boolean contains(String id);

        float get(String id);

        float minimum(String id);

        float maximum(String id);

        float defaultValue(String id);

        void set(String id, float value);
    }

    private static class Setting {
        private final List<Input> inputs;
        private final List<Output> outputs;
        private final List<Vertex> vertices;
        private final Normalization normalization;
        private final List<Particle> particles = new ArrayList<>();
        private float[] previousOutputs;
        private float[] currentOutputs;

        private Setting(List<Input> inputs, List<Output> outputs, List<Vertex> vertices,
                        Normalization normalization) {
            this.inputs = List.copyOf(inputs);
            this.outputs = List.copyOf(outputs);
            this.vertices = List.copyOf(vertices);
            this.normalization = normalization;
            this.previousOutputs = new float[outputs.size()];
            this.currentOutputs = new float[outputs.size()];
        }

        private static Setting parse(JsonObject object) {
            List<Vertex> vertices = new ArrayList<>();
            for (JsonElement element : array(object, "Vertices")) {
                if (element.isJsonObject()) {
                    vertices.add(Vertex.parse(element.getAsJsonObject()));
                }
            }
            if (vertices.size() < 2) {
                return null;
            }

            List<Input> inputs = new ArrayList<>();
            for (JsonElement element : array(object, "Input")) {
                if (element.isJsonObject()) {
                    Input input = Input.parse(element.getAsJsonObject());
                    if (input != null) {
                        inputs.add(input);
                    }
                }
            }

            List<Output> outputs = new ArrayList<>();
            for (JsonElement element : array(object, "Output")) {
                if (element.isJsonObject()) {
                    Output output = Output.parse(element.getAsJsonObject());
                    if (output != null) {
                        outputs.add(output);
                    }
                }
            }

            return new Setting(inputs, outputs, vertices, Normalization.parse(object(object, "Normalization")));
        }

        private void collectParameterIds(Set<String> ids) {
            for (Input input : inputs) {
                ids.add(input.id());
            }
            for (Output output : outputs) {
                ids.add(output.id());
            }
        }

        private void initialize() {
            particles.clear();
            for (int i = 0; i < vertices.size(); i++) {
                Vertex vertex = vertices.get(i);
                Vector2 initialPosition;
                if (i == 0) {
                    initialPosition = new Vector2(0.0F, 0.0F);
                } else {
                    Vector2 previous = particles.get(i - 1).initialPosition;
                    initialPosition = new Vector2(previous.x(), previous.y() + vertex.radius());
                }
                particles.add(new Particle(initialPosition, vertex.mobility(), vertex.delay(),
                        vertex.acceleration(), vertex.radius()));
            }
            previousOutputs = new float[outputs.size()];
            currentOutputs = new float[outputs.size()];
        }

        private void step(Map<String, Float> values, Parameters parameters,
                          Vector2 gravity, Vector2 wind, float deltaSeconds, float outputScale) {
            if (particles.size() != vertices.size()) {
                initialize();
            }

            System.arraycopy(currentOutputs, 0, previousOutputs, 0, currentOutputs.length);

            Target target = readTarget(values, parameters);
            updateParticles(target, wind, deltaSeconds);
            for (int i = 0; i < outputs.size(); i++) {
                Output output = outputs.get(i);
                float value = output.calculate(particles, gravity);
                currentOutputs[i] = output.apply(values, parameters, value * outputScale);
            }
        }

        private void applyInterpolated(Parameters parameters, float alpha, float outputScale) {
            for (int i = 0; i < outputs.size(); i++) {
                Output output = outputs.get(i);
                float value = lerp(previousOutputs[i], currentOutputs[i], alpha) * outputScale;
                output.set(parameters, value);
            }
        }

        private Target readTarget(Map<String, Float> values, Parameters parameters) {
            float x = 0.0F;
            float y = 0.0F;
            float angle = 0.0F;
            for (Input input : inputs) {
                if (!parameters.contains(input.id())) {
                    continue;
                }
                NormalizationRange range = input.type() == PhysicsType.ANGLE
                        ? normalization.angle()
                        : normalization.position();
                float currentValue = values.getOrDefault(input.id(), parameters.get(input.id()));
                float normalized = normalizeParameter(currentValue, parameters.minimum(input.id()),
                        parameters.maximum(input.id()), range, input.reflect());
                normalized *= clamp(input.weight() / MAX_WEIGHT, 0.0F, 1.0F);
                if (input.type() == PhysicsType.X) {
                    x += normalized;
                } else if (input.type() == PhysicsType.Y) {
                    y += normalized;
                } else {
                    angle += normalized;
                }
            }

            float radians = (float) Math.toRadians(-angle);
            float sin = (float) Math.sin(radians);
            float cos = (float) Math.cos(radians);
            float rotatedX = x * cos - y * sin;
            float rotatedY = x * sin + y * cos;
            return new Target(rotatedX, rotatedY, angle);
        }

        private void updateParticles(Target target, Vector2 wind, float deltaSeconds) {
            Particle root = particles.get(0);
            root.position = new Vector2(target.x(), target.y());
            root.lastPosition = root.position;

            Vector2 currentGravity = directionFromRadians((float) Math.toRadians(target.angle()));
            for (int i = 1; i < particles.size(); i++) {
                Particle previous = particles.get(i - 1);
                Particle current = particles.get(i);
                float delay = current.delay * deltaSeconds * DEFAULT_PHYSICS_FPS * 0.5F;

                current.force = currentGravity.multiply(current.acceleration).add(wind);
                current.lastPosition = current.position;

                Vector2 direction = current.position.subtract(previous.position);
                float radians = directionToRadian(current.lastGravity, currentGravity) / AIR_RESISTANCE;
                direction = direction.rotate(radians);

                current.position = previous.position.add(direction);
                if (Math.abs(delay) > EPSILON) {
                    current.position = current.position
                            .add(current.velocity.multiply(delay))
                            .add(current.force.multiply(delay * delay));
                }

                Vector2 distance = current.position.subtract(previous.position);
                Vector2 normalized = distance.normalized();
                if (normalized.lengthSquared() <= EPSILON) {
                    normalized = new Vector2(0.0F, 1.0F);
                }
                current.position = previous.position.add(normalized.multiply(Math.max(EPSILON, current.radius)));
                if (Math.abs(current.position.x()) < MOVEMENT_THRESHOLD) {
                    current.position = new Vector2(0.0F, current.position.y());
                }

                if (Math.abs(delay) > EPSILON) {
                    current.velocity = current.position.subtract(current.lastPosition)
                            .multiply(current.mobility / delay);
                } else {
                    current.velocity = Vector2.ZERO;
                }
                current.force = Vector2.ZERO;
                current.lastGravity = currentGravity;
            }
        }
    }

    private record Input(String id, PhysicsType type, float weight, boolean reflect) {
        private static Input parse(JsonObject object) {
            JsonObject source = object(object, "Source");
            if (!"Parameter".equalsIgnoreCase(string(source, "Target", "Parameter"))) {
                return null;
            }
            String id = string(source, "Id", "");
            if (id.isBlank()) {
                return null;
            }
            return new Input(id, PhysicsType.from(string(object, "Type", "X")),
                    number(object, "Weight", 100.0F), bool(object, "Reflect", false));
        }
    }

    private record Output(String id, int vertexIndex, float scale, float weight, PhysicsType type, boolean reflect) {
        private static Output parse(JsonObject object) {
            JsonObject destination = object(object, "Destination");
            if (!"Parameter".equalsIgnoreCase(string(destination, "Target", "Parameter"))) {
                return null;
            }
            String id = string(destination, "Id", "");
            if (id.isBlank()) {
                return null;
            }
            return new Output(id, Math.max(0, Math.round(number(object, "VertexIndex", 0.0F))),
                    number(object, "Scale", 1.0F), number(object, "Weight", 100.0F),
                    PhysicsType.from(string(object, "Type", "X")), bool(object, "Reflect", false));
        }

        private float calculate(List<Particle> particles, Vector2 gravity) {
            if (vertexIndex <= 0 || vertexIndex >= particles.size()) {
                return 0.0F;
            }

            Vector2 translation = particles.get(vertexIndex).position.subtract(particles.get(vertexIndex - 1).position);
            float value;
            if (type == PhysicsType.X) {
                value = translation.x() * scale;
            } else if (type == PhysicsType.Y) {
                value = translation.y() * scale;
            } else {
                Vector2 parentGravity = gravity.multiply(-1.0F).normalized();
                value = directionToRadian(parentGravity, translation) * scale;
            }
            return reflect ? -value : value;
        }

        private float apply(Map<String, Float> values, Parameters parameters, float value) {
            if (!parameters.contains(id)) {
                return 0.0F;
            }
            float current = values.getOrDefault(id, parameters.get(id));
            float next = blend(parameters, current, value);
            values.put(id, next);
            return next;
        }

        private void set(Parameters parameters, float value) {
            if (!parameters.contains(id)) {
                return;
            }
            parameters.set(id, clamp(value, parameters.minimum(id), parameters.maximum(id)));
        }

        private float blend(Parameters parameters, float current, float value) {
            float alpha = clamp(weight / MAX_WEIGHT, 0.0F, 1.0F);
            float next = lerp(current, value, alpha);
            return clamp(next, parameters.minimum(id), parameters.maximum(id));
        }
    }

    private record Vertex(Vector2 position, float mobility, float delay, float acceleration, float radius) {
        private static Vertex parse(JsonObject object) {
            return new Vertex(vector(object(object, "Position"), 0.0F, 0.0F),
                    number(object, "Mobility", 1.0F),
                    number(object, "Delay", 1.0F),
                    number(object, "Acceleration", 1.0F),
                    number(object, "Radius", 0.0F));
        }
    }

    private record Normalization(NormalizationRange position, NormalizationRange angle) {
        private static Normalization parse(JsonObject object) {
            return new Normalization(
                    NormalizationRange.parse(object(object, "Position")),
                    NormalizationRange.parse(object(object, "Angle"))
            );
        }
    }

    private record NormalizationRange(float minimum, float defaults, float maximum) {
        private static NormalizationRange parse(JsonObject object) {
            return new NormalizationRange(number(object, "Minimum", -1.0F),
                    number(object, "Default", 0.0F),
                    number(object, "Maximum", 1.0F));
        }
    }

    private enum PhysicsType {
        X,
        Y,
        ANGLE;

        private static PhysicsType from(String value) {
            if ("Y".equalsIgnoreCase(value)) {
                return Y;
            }
            if ("Angle".equalsIgnoreCase(value)) {
                return ANGLE;
            }
            return X;
        }
    }

    private record Vector2(float x, float y) {
        private static final Vector2 ZERO = new Vector2(0.0F, 0.0F);

        private Vector2 add(Vector2 other) {
            return new Vector2(x + other.x, y + other.y);
        }

        private Vector2 subtract(Vector2 other) {
            return new Vector2(x - other.x, y - other.y);
        }

        private Vector2 multiply(float value) {
            return new Vector2(x * value, y * value);
        }

        private Vector2 rotate(float radians) {
            float sin = (float) Math.sin(radians);
            float cos = (float) Math.cos(radians);
            return new Vector2(x * cos - y * sin, x * sin + y * cos);
        }

        private float lengthSquared() {
            return x * x + y * y;
        }

        private float length() {
            return (float) Math.sqrt(lengthSquared());
        }

        private Vector2 normalized() {
            float length = length();
            if (length <= EPSILON || !Float.isFinite(length)) {
                return ZERO;
            }
            return new Vector2(x / length, y / length);
        }
    }

    private record Target(float x, float y, float angle) {
    }

    private static class Particle {
        private final Vector2 initialPosition;
        private final float mobility;
        private final float delay;
        private final float acceleration;
        private final float radius;
        private Vector2 position;
        private Vector2 lastPosition;
        private Vector2 velocity = Vector2.ZERO;
        private Vector2 force = Vector2.ZERO;
        private Vector2 lastGravity = new Vector2(0.0F, 1.0F);

        private Particle(Vector2 initialPosition, float mobility, float delay, float acceleration, float radius) {
            this.initialPosition = initialPosition;
            this.mobility = mobility;
            this.delay = delay;
            this.acceleration = acceleration;
            this.radius = radius;
            this.position = initialPosition;
            this.lastPosition = initialPosition;
        }
    }
}
