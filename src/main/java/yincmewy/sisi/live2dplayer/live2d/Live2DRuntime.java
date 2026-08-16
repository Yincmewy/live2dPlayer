package yincmewy.sisi.live2dplayer.live2d;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public enum Live2DRuntime {
    INSTANCE;

    private static final float ROTATION_EPSILON = 0.001F;
    private static final float ROTATION_FULL_YAW_SPEED = 260.0F;
    private static final float ROTATION_FULL_PITCH_SPEED = 190.0F;
    private static final float ROTATION_INPUT_SMOOTHING = 36.0F;
    private static final float ROTATION_RETURN_SPEED = 5.5F;
    private static final float ROTATION_MAX_SAMPLE_SECONDS = 0.12F;
    private static final float VERTICAL_FULL_SPEED = 0.42F;
    private static final float VERTICAL_INPUT_SMOOTHING = 12.0F;
    private static final float VERTICAL_RETURN_SPEED = 7.0F;
    private static final float VERTICAL_EPSILON = 0.01F;

    private final Live2DModelManager modelManager = new Live2DModelManager();
    private final Live2DRenderer renderer = new Live2DRenderer();
    private Live2DConfig config = Live2DConfig.load();
    private List<Live2DModel> models = new ArrayList<>();
    private boolean initialized;
    private Live2DRenderThread renderThread;

    private float rotationResponseX;
    private float rotationResponseY;
    private float verticalResponseY;
    private float horizontalSpeed;
    private float lipSyncResponse;
    private float lastPlayerYaw;
    private float lastPlayerPitch;
    private long lastRotationSampleNanos;
    private boolean hasRotationSample;

    private boolean dragging;
    private double lastDragX;
    private double lastDragY;
    private double dragStartX;
    private double dragStartY;
    private double dragDistance;
    private boolean resizing;
    private double lastResizeY;
    private float resizeBaseScale;
    private float resizeBaseY;
    private float resizeBaseHeight;

    public void openConfig(Screen parent) {
        init();
        Minecraft.getInstance().setScreen(Live2DConfigScreen.create(parent));
    }

    public void init() {
        if (!initialized) {
            refreshModels();
            initialized = true;
        }
        if (renderThread == null || !renderThread.isAlive()) {
            renderThread = new Live2DRenderThread(this);
            renderThread.start();
        }
    }

    public void refreshModels() {
        renderer.clear();
        models = modelManager.refresh();
        ensureSelectedModel();
        if (selectedModel() != null) {
            ensureSelectedExpressions();
            ensureSelectedMotion();
        }
    }

    public List<Live2DModel> models() {
        return models;
    }

    public Live2DConfig config() {
        return config;
    }

    public void setEnabled(boolean enabled) {
        config.enabled = enabled;
        saveConfig();
    }

    public Live2DRenderer renderer() {
        return renderer;
    }

    public Live2DModel selectedModel() {
        String selected = config.selectedModel == null ? "" : config.selectedModel;
        for (Live2DModel model : models) {
            if (model.name().equals(selected)) {
                return model;
            }
        }
        return models.isEmpty() ? null : models.get(0);
    }

    public void selectModel(String name) {
        config.selectedModel = name == null ? "" : name;
        config.selectedExpression = "";
        config.selectedExpressions.clear();
        config.selectedMotion = "Idle";
        saveConfig();
    }

    public void selectExpression(String name) {
        config.selectedExpressions.clear();
        if (name != null && !name.isBlank()) {
            config.selectedExpressions.add(name);
        }
        config.selectedExpression = name == null ? "" : name;
        saveConfig();
    }

    public void toggleExpression(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (config.selectedExpressions.contains(name)) {
            config.selectedExpressions.remove(name);
        } else {
            config.selectedExpressions.add(name);
        }
        config.selectedExpression = config.selectedExpressions.isEmpty()
                ? ""
                : config.selectedExpressions.get(config.selectedExpressions.size() - 1);
        saveConfig();
    }

    public boolean isExpressionSelected(String name) {
        return name != null && config.selectedExpressions.contains(name);
    }

    public void selectMotion(String name) {
        config.selectedMotion = name == null ? "" : name;
        config.motionMode = Live2DConfig.MotionMode.SELECTED;
        saveConfig();
    }

    public void cycleExpression(int direction) {
        Live2DModel model = selectedModel();
        if (model == null || model.expressions().isEmpty()) {
            return;
        }
        List<String> names = expressionNames(model);
        int current = names.indexOf(config.selectedExpression);
        int next = (current + direction + names.size()) % names.size();
        String nextExpression = names.get(next);
        if (config.selectedExpressions.contains(nextExpression)
                && config.selectedExpressions.size() >= names.size()) {
            config.selectedExpressions.clear();
        } else if (!config.selectedExpressions.contains(nextExpression)) {
            config.selectedExpressions.add(nextExpression);
        }
        config.selectedExpression = nextExpression;
        saveConfig();
    }

    public void cycleMotion(int direction) {
        Live2DModel model = selectedModel();
        if (model == null || model.motions().isEmpty()) {
            return;
        }
        List<String> names = motionNames(model);
        int current = names.indexOf(config.selectedMotion);
        int next = (current + direction + names.size()) % names.size();
        selectMotion(names.get(next));
    }

    public void triggerMotion(String name) {
        Live2DModel model = selectedModel();
        if (model == null) {
            return;
        }
        String motion = name == null || name.isBlank() ? "Tap" : name;
        for (Live2DMotion candidate : model.motions()) {
            if (candidate.name().equalsIgnoreCase(motion)) {
                renderer.playMotion(motion, false);
                return;
            }
        }
        for (Live2DMotion candidate : model.motions()) {
            if (candidate.name().toLowerCase(Locale.ROOT).contains(motion.toLowerCase(Locale.ROOT))) {
                renderer.playMotion(candidate.name(), false);
                return;
            }
        }
    }

    public void triggerRandomExpression() {
        Live2DModel model = selectedModel();
        if (model == null || model.expressions().isEmpty()) {
            return;
        }
        List<Live2DExpression> expressions = model.expressions();
        toggleExpression(expressions.get(ThreadLocalRandom.current().nextInt(expressions.size())).name());
    }

    public void toggle() {
        config.enabled = !config.enabled;
        saveConfig();
    }

    public boolean isEditMode() {
        return config.editMode;
    }

    public boolean isEditorInteractive() {
        return config.editMode && Minecraft.getInstance().screen != null;
    }

    public void toggleEditMode() {
        config.editMode = !config.editMode;
        saveConfig();
    }

    public void resetPosition() {
        config.resetTransform();
        saveConfig();
    }

    public void saveConfig() {
        config.save();
    }

    public void reloadConfig() {
        renderer.clear();
        config = Live2DConfig.load();
        refreshModels();
    }

    public void stopRenderThread() {
        if (renderThread != null) {
            renderThread.stopWorker();
            renderThread = null;
        }
    }

    public List<String> modelNames() {
        return models.stream().map(Live2DModel::name).toList();
    }

    public List<String> expressionNames(Live2DModel model) {
        return model == null
                ? List.of()
                : model.expressions().stream().map(Live2DExpression::name).toList();
    }

    public List<String> motionNames(Live2DModel model) {
        return model == null
                ? List.of()
                : model.motions().stream().map(Live2DMotion::name).toList();
    }

    public void tick() {
        init();
        Minecraft mc = Minecraft.getInstance();
        if (!config.enabled || mc.player == null) {
            resetRotationResponse();
            return;
        }
        updateRotationResponse(mc);
        updateHorizontalSpeed(mc);
        updateLipSyncResponse(mc);
    }

    public void renderHud(GuiGraphics graphics, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Live2DModel model = selectedModel();
        boolean hasPlayer = mc.level != null && mc.player != null;
        boolean screenVisible = shouldRenderInCurrentScreen(mc);
        if (!config.enabled || model == null || !model.valid()
                || !screenVisible || (!hasPlayer && mc.screen == null) || config.alpha <= 0.0F) {
            renderer.clearBounds();
            return;
        }

        config.clampToScreen(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        renderer.drawHud(graphics, model, config);
    }

    public void prepareFrame() {
        Minecraft mc = Minecraft.getInstance();
        Live2DModel model = selectedModel();
        boolean hasPlayer = mc.level != null && mc.player != null;
        boolean screenVisible = shouldRenderInCurrentScreen(mc);
        if (!config.enabled || model == null || !model.valid()
                || !screenVisible || (!hasPlayer && mc.screen == null) || config.alpha <= 0.0F) {
            return;
        }

        if (config.followMouse) {
            renderer.prepareHudWithMouse(model, config, (float) guiMouseX(), (float) guiMouseY(),
                    verticalResponseY, lipSyncResponse, mc.getFrameTime());
        } else {
            renderer.prepareHud(model, config, rotationResponseX, rotationResponseY,
                    verticalResponseY, horizontalSpeed, hasPlayer && !mc.player.onGround(),
                    lipSyncResponse, mc.getFrameTime());
        }
    }

    public long workerFrameIntervalNanos() {
        int interval = Math.max(1, Math.min(6, config.renderIntervalFrames));
        return 16_666_667L * interval;
    }

    public Live2DCubismBackend.Bounds bounds() {
        return renderer.lastBounds();
    }

    public double guiMouseX() {
        Minecraft mc = Minecraft.getInstance();
        double scale = Math.max(1.0D, mc.getWindow().getGuiScale());
        return mc.mouseHandler.xpos() / scale;
    }

    public double guiMouseY() {
        Minecraft mc = Minecraft.getInstance();
        double scale = Math.max(1.0D, mc.getWindow().getGuiScale());
        return mc.mouseHandler.ypos() / scale;
    }

    public boolean isDragging() {
        return dragging;
    }

    public boolean beginDrag(double mouseX, double mouseY) {
        if (!isEditorInteractive() || !config.dragEnabled || !bounds().contains(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        lastDragX = mouseX;
        lastDragY = mouseY;
        dragStartX = mouseX;
        dragStartY = mouseY;
        dragDistance = 0.0D;
        return true;
    }

    public void dragTo(double mouseX, double mouseY) {
        if (!dragging) {
            return;
        }
        config.x += (float) (mouseX - lastDragX);
        config.y += (float) (mouseY - lastDragY);
        dragDistance += Math.abs(mouseX - lastDragX) + Math.abs(mouseY - lastDragY);
        lastDragX = mouseX;
        lastDragY = mouseY;
        config.clampToScreen(Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                Minecraft.getInstance().getWindow().getGuiScaledHeight());
    }

    public boolean endDrag() {
        if (!dragging) {
            return false;
        }
        dragging = false;
        saveConfig();
        double total = Math.hypot(lastDragX - dragStartX, lastDragY - dragStartY);
        boolean wasClick = total < 4.0D && dragDistance < 5.0D;
        if (wasClick && config.clickInteraction) {
            triggerMotion("Tap");
            if (config.randomExpressionOnClick) {
                triggerRandomExpression();
            }
        }
        return wasClick;
    }

    public boolean beginResize(double mouseX, double mouseY) {
        if (!isEditorInteractive() || !resizeHandleBounds().contains(mouseX, mouseY)) {
            return false;
        }
        resizing = true;
        lastResizeY = mouseY;
        Live2DCubismBackend.Bounds bounds = bounds();
        resizeBaseScale = config.scale;
        resizeBaseY = bounds.y();
        resizeBaseHeight = Math.max(1.0F, bounds.height());
        return true;
    }

    public void resizeTo(double mouseX, double mouseY) {
        if (!resizing) {
            return;
        }
        float ratio = (float) ((mouseY - resizeBaseY) / resizeBaseHeight);
        config.scale = clamp(resizeBaseScale * Math.max(0.08F, ratio), 0.03F, 4.0F);
        lastResizeY = mouseY;
    }

    public boolean endResize() {
        if (!resizing) {
            return false;
        }
        resizing = false;
        saveConfig();
        return true;
    }

    public boolean isResizing() {
        return resizing;
    }

    public void cancelEditorInteraction() {
        boolean active = dragging || resizing;
        dragging = false;
        resizing = false;
        if (active) {
            saveConfig();
        }
    }

    public ResizeHandle resizeHandleBounds() {
        Live2DCubismBackend.Bounds bounds = bounds();
        if (bounds.isEmpty()) {
            return ResizeHandle.EMPTY;
        }
        float size = 12.0F;
        return new ResizeHandle(bounds.x() + bounds.width() - size,
                bounds.y() + bounds.height() - size, size, size);
    }

    public record ResizeHandle(float x, float y, float width, float height) {
        public static final ResizeHandle EMPTY = new ResizeHandle(0.0F, 0.0F, 0.0F, 0.0F);

        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    private boolean shouldRenderInCurrentScreen(Minecraft mc) {
        return mc.screen == null || config.renderInScreens || config.editMode;
    }

    private void updateRotationResponse(Minecraft mc) {
        long now = System.nanoTime();
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        if (!hasRotationSample) {
            hasRotationSample = true;
            lastPlayerYaw = yaw;
            lastPlayerPitch = pitch;
            lastRotationSampleNanos = now;
            return;
        }

        float sampleSeconds = clamp((now - lastRotationSampleNanos) / 1_000_000_000.0F,
                0.001F, ROTATION_MAX_SAMPLE_SECONDS);
        float yawDelta = Mth.wrapDegrees(yaw - lastPlayerYaw);
        float pitchDelta = pitch - lastPlayerPitch;

        lastPlayerYaw = yaw;
        lastPlayerPitch = pitch;
        lastRotationSampleNanos = now;

        rotationResponseX = updateAxisResponse(rotationResponseX, yawDelta,
                sampleSeconds, ROTATION_FULL_YAW_SPEED);
        rotationResponseY = updateAxisResponse(rotationResponseY, pitchDelta,
                sampleSeconds, ROTATION_FULL_PITCH_SPEED);
        updateVerticalResponse(mc, sampleSeconds);
    }

    private float updateAxisResponse(float current, float deltaDegrees,
                                     float sampleSeconds, float fullSpeed) {
        if (Math.abs(deltaDegrees) > ROTATION_EPSILON) {
            float speed = Math.abs(deltaDegrees) / Math.max(0.001F, sampleSeconds);
            float target = Math.copySign(clamp(speed / fullSpeed, 0.0F, 1.0F), deltaDegrees);
            return lerp(current, target, responseAlpha(ROTATION_INPUT_SMOOTHING, sampleSeconds));
        }
        return lerp(current, 0.0F, responseAlpha(ROTATION_RETURN_SPEED, sampleSeconds));
    }

    private void updateVerticalResponse(Minecraft mc, float sampleSeconds) {
        double velocityY = mc.player.getDeltaMovement().y;
        float target = 0.0F;
        if (!mc.player.onGround() && Math.abs(velocityY) > VERTICAL_EPSILON) {
            target = clamp((float) (-velocityY / VERTICAL_FULL_SPEED), -1.0F, 1.0F);
        }

        float speed = target == 0.0F ? VERTICAL_RETURN_SPEED : VERTICAL_INPUT_SMOOTHING;
        verticalResponseY = lerp(verticalResponseY, target, responseAlpha(speed, sampleSeconds));
        if (target == 0.0F && Math.abs(verticalResponseY) < VERTICAL_EPSILON) {
            verticalResponseY = 0.0F;
        }
    }

    private void updateHorizontalSpeed(Minecraft mc) {
        double dx = mc.player.getX() - mc.player.xOld;
        double dz = mc.player.getZ() - mc.player.zOld;
        float target = (float) Math.sqrt(dx * dx + dz * dz);
        horizontalSpeed = lerp(horizontalSpeed, target, 0.25F);
    }

    private void updateLipSyncResponse(Minecraft mc) {
        float target = config.lipSync && (horizontalSpeed > 0.035F || !mc.player.onGround())
                ? 1.0F
                : 0.0F;
        lipSyncResponse = lerp(lipSyncResponse, target, 0.12F);
    }

    private void resetRotationResponse() {
        rotationResponseX = 0.0F;
        rotationResponseY = 0.0F;
        verticalResponseY = 0.0F;
        horizontalSpeed = 0.0F;
        lipSyncResponse = 0.0F;
        hasRotationSample = false;
    }

    private void ensureSelectedModel() {
        if (models.isEmpty()) {
            return;
        }
        String selected = config.selectedModel == null ? "" : config.selectedModel;
        for (Live2DModel model : models) {
            if (model.name().equals(selected)) {
                return;
            }
        }
        config.selectedModel = models.get(0).name();
    }

    private void ensureSelectedExpressions() {
        Live2DModel model = selectedModel();
        if (model == null) {
            return;
        }
        List<String> available = expressionNames(model);
        config.selectedExpressions.removeIf(expression -> !available.contains(expression));
        if (config.selectedExpressions.isEmpty()) {
            String legacy = config.selectedExpression == null ? "" : config.selectedExpression;
            if (legacy.isBlank()) {
                return;
            }
            for (String expression : available) {
                if (expression.equals(legacy)) {
                    config.selectedExpressions.add(expression);
                    break;
                }
            }
        }
    }

    private void ensureSelectedExpression() {
        Live2DModel model = selectedModel();
        if (model == null) {
            return;
        }
        String selected = config.selectedExpression == null ? "" : config.selectedExpression;
        if (selected.isBlank()) {
            return;
        }
        for (Live2DExpression expression : model.expressions()) {
            if (expression.name().equals(selected)) {
                return;
            }
        }
        config.selectedExpression = "";
    }

    private void ensureSelectedMotion() {
        Live2DModel model = selectedModel();
        if (model == null) {
            return;
        }
        String selected = config.selectedMotion == null ? "" : config.selectedMotion;
        for (Live2DMotion motion : model.motions()) {
            if (motion.name().equals(selected)) {
                return;
            }
        }
        config.selectedMotion = model.motions().isEmpty() ? "Idle" : model.motions().get(0).name();
    }

    private float responseAlpha(float speed, float sampleSeconds) {
        return clamp(1.0F - (float) Math.exp(-speed * sampleSeconds), 0.0F, 1.0F);
    }

    private float lerp(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }

    private float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
