package yincmewy.sisi.live2dplayer.live2d;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Live2DMotion {
    private static final int SEGMENT_LINEAR = 0;
    private static final int SEGMENT_BEZIER = 1;
    private static final int SEGMENT_STEPPED = 2;
    private static final int SEGMENT_INVERSE_STEPPED = 3;

    private final String name;
    private final Path file;
    private final float duration;
    private final boolean loop;
    private final float fadeInSeconds;
    private final List<Curve> curves;
    private final Set<String> controlledParameters;

    private Live2DMotion(String name, Path file, float duration, boolean loop, float fadeInSeconds,
                         List<Curve> curves, Set<String> controlledParameters) {
        this.name = name == null ? "" : name;
        this.file = file;
        this.duration = duration;
        this.loop = loop;
        this.fadeInSeconds = fadeInSeconds;
        this.curves = List.copyOf(curves);
        this.controlledParameters = Set.copyOf(controlledParameters);
    }

    public static Live2DMotion parse(Path file, String name, boolean loop, float fadeInSeconds) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject meta = object(root, "Meta");
        float duration = number(meta, "Duration", 0.0F);
        boolean motionLoop = bool(meta, "Loop", loop);

        List<Curve> curves = new ArrayList<>();
        Set<String> controlledParameters = new HashSet<>();
        JsonArray curveArray = array(root, "Curves");
        for (JsonElement element : curveArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            Curve curve = Curve.parse(element.getAsJsonObject());
            if (curve == null) {
                continue;
            }
            curves.add(curve);
            duration = Math.max(duration, curve.endTime());
            if (curve.parameterTarget()) {
                controlledParameters.add(curve.id());
            }
        }

        return new Live2DMotion(name, file, Math.max(0.0F, duration), motionLoop,
                Math.max(0.0F, fadeInSeconds), curves, controlledParameters);
    }

    public String name() {
        return name;
    }

    public float duration() {
        return duration;
    }

    public boolean loop() {
        return loop;
    }

    public Path file() {
        return file;
    }

    public boolean controlsAny(List<String> parameterIds) {
        if (parameterIds == null || parameterIds.isEmpty() || controlledParameters.isEmpty()) {
            return false;
        }
        for (String id : parameterIds) {
            if (controlledParameters.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean controls(String parameterId) {
        return parameterId != null && controlledParameters.contains(parameterId);
    }

    public void apply(float elapsedSeconds, Parameters parameters) {
        apply(elapsedSeconds, parameters, 1.0F);
    }

    public void apply(float elapsedSeconds, Parameters parameters, float scale) {
        if (parameters == null || curves.isEmpty()) {
            return;
        }

        float time = motionTime(elapsedSeconds);
        float appliedScale = Float.isFinite(scale) ? scale : 1.0F;
        float fade = fadeInSeconds <= 0.0F ? 1.0F : smooth(clamp(elapsedSeconds / fadeInSeconds, 0.0F, 1.0F));
        for (Curve curve : curves) {
            if (!curve.parameterTarget()) {
                continue;
            }
            float value = curve.evaluate(time) * appliedScale;
            if (fade < 1.0F) {
                value = lerp(parameters.get(curve.id()), value, fade);
            }
            parameters.set(curve.id(), value);
        }
    }

    private float motionTime(float elapsedSeconds) {
        if (duration <= 0.0F) {
            return Math.max(0.0F, elapsedSeconds);
        }
        if (loop) {
            float result = elapsedSeconds % duration;
            return result < 0.0F ? result + duration : result;
        }
        return clamp(elapsedSeconds, 0.0F, duration);
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

    private static float lerp(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }

    private static float smooth(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public interface Parameters {
        float get(String id);

        void set(String id, float value);
    }

    private record Curve(String target, String id, float startValue, List<Segment> segments) {
        private static Curve parse(JsonObject object) {
            String target = string(object, "Target", "");
            String id = string(object, "Id", "");
            if (id.isBlank()) {
                return null;
            }

            JsonArray segmentValues = array(object, "Segments");
            if (segmentValues.size() < 2) {
                return null;
            }

            float startTime = number(segmentValues, 0, 0.0F);
            float startValue = number(segmentValues, 1, 0.0F);
            float currentTime = startTime;
            float currentValue = startValue;
            List<Segment> segments = new ArrayList<>();
            int index = 2;
            while (index < segmentValues.size()) {
                int type = Math.round(number(segmentValues, index++, SEGMENT_LINEAR));
                if (type == SEGMENT_BEZIER) {
                    if (index + 5 >= segmentValues.size()) {
                        break;
                    }
                    float cp1Time = number(segmentValues, index++, currentTime);
                    float cp1Value = number(segmentValues, index++, currentValue);
                    float cp2Time = number(segmentValues, index++, cp1Time);
                    float cp2Value = number(segmentValues, index++, cp1Value);
                    float endTime = number(segmentValues, index++, cp2Time);
                    float endValue = number(segmentValues, index++, cp2Value);
                    segments.add(new Segment(type, currentTime, currentValue, endTime, endValue,
                            cp1Time, cp1Value, cp2Time, cp2Value));
                    currentTime = endTime;
                    currentValue = endValue;
                } else {
                    if (index + 1 >= segmentValues.size()) {
                        break;
                    }
                    float endTime = number(segmentValues, index++, currentTime);
                    float endValue = number(segmentValues, index++, currentValue);
                    segments.add(new Segment(type, currentTime, currentValue, endTime, endValue,
                            currentTime, currentValue, endTime, endValue));
                    currentTime = endTime;
                    currentValue = endValue;
                }
            }
            return new Curve(target, id, startValue, List.copyOf(segments));
        }

        private boolean parameterTarget() {
            return "Parameter".equalsIgnoreCase(target);
        }

        private float endTime() {
            return segments.isEmpty() ? 0.0F : segments.get(segments.size() - 1).endTime();
        }

        private float evaluate(float time) {
            if (segments.isEmpty()) {
                return startValue;
            }
            if (time <= segments.get(0).startTime()) {
                return segments.get(0).startValue();
            }
            for (Segment segment : segments) {
                if (time <= segment.endTime()) {
                    return segment.evaluate(time);
                }
            }
            return segments.get(segments.size() - 1).endValue();
        }

        private static String string(JsonObject object, String key, String fallback) {
            try {
                return object.has(key) ? object.get(key).getAsString() : fallback;
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private static JsonArray array(JsonObject object, String key) {
            return object != null && object.has(key) && object.get(key).isJsonArray()
                    ? object.getAsJsonArray(key)
                    : new JsonArray();
        }

        private static float number(JsonArray array, int index, float fallback) {
            try {
                return index >= 0 && index < array.size() ? array.get(index).getAsFloat() : fallback;
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    private record Segment(int type, float startTime, float startValue, float endTime, float endValue,
                           float cp1Time, float cp1Value, float cp2Time, float cp2Value) {
        private float evaluate(float time) {
            if (endTime <= startTime) {
                return endValue;
            }

            float progress = clamp((time - startTime) / (endTime - startTime), 0.0F, 1.0F);
            if (type == SEGMENT_STEPPED) {
                return startValue;
            }
            if (type == SEGMENT_INVERSE_STEPPED) {
                return endValue;
            }
            if (type == SEGMENT_BEZIER) {
                float t = solveBezierTime(time);
                return cubic(startValue, cp1Value, cp2Value, endValue, t);
            }
            return lerp(startValue, endValue, progress);
        }

        private float solveBezierTime(float time) {
            float low = 0.0F;
            float high = 1.0F;
            for (int i = 0; i < 12; i++) {
                float mid = (low + high) * 0.5F;
                float x = cubic(startTime, cp1Time, cp2Time, endTime, mid);
                if (x < time) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            return (low + high) * 0.5F;
        }

        private static float cubic(float p0, float p1, float p2, float p3, float t) {
            float oneMinus = 1.0F - t;
            return oneMinus * oneMinus * oneMinus * p0
                    + 3.0F * oneMinus * oneMinus * t * p1
                    + 3.0F * oneMinus * t * t * p2
                    + t * t * t * p3;
        }
    }
}
