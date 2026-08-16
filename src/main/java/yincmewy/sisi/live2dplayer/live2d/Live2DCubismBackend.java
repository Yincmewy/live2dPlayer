package yincmewy.sisi.live2dplayer.live2d;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.FloatByReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class Live2DCubismBackend {
    private static final int POINTER_SIZE = Native.POINTER_SIZE;
    private static final int FLAG_BLEND_ADDITIVE = 1;
    private static final int FLAG_BLEND_MULTIPLICATIVE = 2;
    private static final int FLAG_IS_INVERTED_MASK = 8;
    private static final int FLAG_IS_VISIBLE = 1;
    private static final int FLAG_OPACITY_CHANGED = 1 << 2;
    private static final int FLAG_RENDER_ORDER_CHANGED = 1 << 4;
    private static final int FLAG_VERTEX_POSITIONS_CHANGED = 1 << 5;
    private static final int MEMORY_ALIGNMENT = 64;
    private static final int MAX_STENCIL_REFERENCE = 0xFF;
    private static final float VERTICAL_BOB_HEIGHT_RATIO = 0.055F;
    private static final float VERTICAL_BOB_MIN_PIXELS = 5.0F;
    private static final AtomicLong NEXT_LAYOUT_ID = new AtomicLong(1L);

    private Core core;
    private CubismModel loadedModel;
    private String loadedKey = "";
    private String status = "Cubism 渲染后端未加载";
    private final long startNanos = System.nanoTime();
    private Bounds lastBounds = Bounds.EMPTY;
    private boolean stencilBufferPrepared;
    private boolean stencilBufferReady;
    private final Object frameLock = new Object();
    private PreparedFrame preparedFrame = PreparedFrame.EMPTY;
    private String transientMotionName = "";
    private float transientMotionStartedAt = Float.NEGATIVE_INFINITY;
    private boolean transientMotionPending;

    public boolean renderHud(GuiGraphics graphics, Live2DModel model, Live2DConfig config,
                             float rotationX, float rotationY, float verticalMotion,
                             float horizontalSpeed, boolean airborne, float lipSync, float partialTick) {
        if (!ensureLoaded(model)) {
            return false;
        }

        float width = loadedModel.canvasWidth() * Math.max(0.01F, config.scale);
        float height = loadedModel.canvasHeight() * Math.max(0.01F, config.scale);
        float elapsed = elapsedSeconds(partialTick);
        Live2DMousePose pose = Live2DMousePose.fromPlayerRotation(rotationX, rotationY, config, elapsed);
        float bobY = clamp(verticalMotion, -1.0F, 1.0F)
                * Math.max(VERTICAL_BOB_MIN_PIXELS, height * VERTICAL_BOB_HEIGHT_RATIO);
        MotionContext context = new MotionContext(horizontalSpeed, airborne, lipSync);
        return renderModel(graphics, model, config, config.x, config.y + bobY, width, height, pose,
                elapsed, context, "player motion");
    }

    public boolean prepareHud(Live2DModel model, Live2DConfig config,
                              float rotationX, float rotationY, float verticalMotion,
                              float horizontalSpeed, boolean airborne, float lipSync,
                              float partialTick) {
        return prepareHud(model, config, rotationX, rotationY, verticalMotion, horizontalSpeed,
                airborne, lipSync, partialTick, null);
    }

    public boolean prepareHud(Live2DModel model, Live2DConfig config,
                              float rotationX, float rotationY, float verticalMotion,
                              float horizontalSpeed, boolean airborne, float lipSync,
                              float partialTick, Consumer<PreparedFrame> frameConsumer) {
        String key = model == null ? "" : model.modelJson().toAbsolutePath().normalize().toString();
        synchronized (frameLock) {
            if (!key.equals(loadedKey) || loadedModel == null || model == null || !model.valid()) {
                preparedFrame = PreparedFrame.EMPTY;
                return false;
            }

            float width = loadedModel.canvasWidth() * Math.max(0.01F, config.scale);
            float height = loadedModel.canvasHeight() * Math.max(0.01F, config.scale);
            float elapsed = elapsedSeconds(partialTick);
            Live2DMousePose pose = Live2DMousePose.fromPlayerRotation(rotationX, rotationY, config, elapsed);
            float bobY = clamp(verticalMotion, -1.0F, 1.0F)
                    * Math.max(VERTICAL_BOB_MIN_PIXELS, height * VERTICAL_BOB_HEIGHT_RATIO);
            MotionContext context = new MotionContext(horizontalSpeed, airborne, lipSync);
            loadedModel.applyParameters(model, config, pose, elapsed, context,
                    new TransientMotionState(transientMotionName, transientMotionStartedAt));
            core.csmUpdateModel(loadedModel.modelPointer);
            loadedModel.refreshDynamicDrawableData();
            preparedFrame = new PreparedFrame(width, height, bobY,
                    RenderSnapshot.from(loadedModel, config.mirror));
            try {
                if (frameConsumer != null) {
                    frameConsumer.accept(preparedFrame);
                }
            } finally {
                core.csmResetDrawableDynamicFlags(loadedModel.modelPointer);
            }
            return true;
        }
    }

    public boolean prepareHudWithMouse(Live2DModel model, Live2DConfig config,
                                       float mouseX, float mouseY, float verticalMotion,
                                       float lipSync, float partialTick) {
        return prepareHudWithMouse(model, config, mouseX, mouseY, verticalMotion, lipSync,
                partialTick, null);
    }

    public boolean prepareHudWithMouse(Live2DModel model, Live2DConfig config,
                                       float mouseX, float mouseY, float verticalMotion,
                                       float lipSync, float partialTick,
                                       Consumer<PreparedFrame> frameConsumer) {
        String key = model == null ? "" : model.modelJson().toAbsolutePath().normalize().toString();
        synchronized (frameLock) {
            if (!key.equals(loadedKey) || loadedModel == null || model == null || !model.valid()) {
                preparedFrame = PreparedFrame.EMPTY;
                return false;
            }

            float width = loadedModel.canvasWidth() * Math.max(0.01F, config.scale);
            float height = loadedModel.canvasHeight() * Math.max(0.01F, config.scale);
            float bobY = clamp(verticalMotion, -1.0F, 1.0F)
                    * Math.max(VERTICAL_BOB_MIN_PIXELS, height * VERTICAL_BOB_HEIGHT_RATIO);
            float elapsed = elapsedSeconds(partialTick);
            Live2DMousePose pose = Live2DMousePose.fromMinecraftMouse(mouseX, mouseY,
                    config.x, config.y + bobY, width, height, config, elapsed);
            MotionContext context = new MotionContext(0.0F, false, lipSync);
            loadedModel.applyParameters(model, config, pose, elapsed, context,
                    new TransientMotionState(transientMotionName, transientMotionStartedAt));
            core.csmUpdateModel(loadedModel.modelPointer);
            loadedModel.refreshDynamicDrawableData();
            preparedFrame = new PreparedFrame(width, height, bobY,
                    RenderSnapshot.from(loadedModel, config.mirror));
            try {
                if (frameConsumer != null) {
                    frameConsumer.accept(preparedFrame);
                }
            } finally {
                core.csmResetDrawableDynamicFlags(loadedModel.modelPointer);
            }
            return true;
        }
    }

    public PreparedFrame preparedFrame() {
        synchronized (frameLock) {
            return preparedFrame;
        }
    }

    public void setLastBounds(Bounds bounds) {
        lastBounds = bounds;
    }

    public boolean drawPrepared(GuiGraphics graphics, Live2DModel model, Live2DConfig config) {
        synchronized (frameLock) {
            if (loadedModel == null || preparedFrame.isEmpty()) {
                return false;
            }

            float x = config.x;
            float y = config.y + preparedFrame.yOffset();
            lastBounds = new Bounds(x, y, preparedFrame.width(), preparedFrame.height());
            try {
                boolean clipped = drawDrawables(graphics, loadedModel, config,
                        x, y, preparedFrame.width(), preparedFrame.height());
                core.csmResetDrawableDynamicFlags(loadedModel.modelPointer);
                status = loadedModel.hasClippingMasks
                        ? "Cubism Core 已渲染 / worker + main / clipping "
                        + (clipped ? "stencil" : "fallback")
                        : "Cubism Core 已渲染 / worker + main";
                return true;
            } catch (Throwable throwable) {
                status = "Cubism 渲染错误: " + safeMessage(throwable);
                return false;
            }
        }
    }

    public boolean prepareModelForRender(Live2DModel model) {
        synchronized (frameLock) {
            return ensureLoaded(model);
        }
    }

    public boolean renderPreview(GuiGraphics graphics, Live2DModel model, Live2DConfig config,
                                 float x, float y, float width, float height,
                                 double mouseX, double mouseY, float partialTick) {
        if (!ensureLoaded(model)) {
            return false;
        }

        float canvasWidth = loadedModel.canvasWidth();
        float canvasHeight = loadedModel.canvasHeight();
        float fit = Math.min(width / Math.max(1.0F, canvasWidth), height / Math.max(1.0F, canvasHeight)) * 0.92F;
        float renderWidth = canvasWidth * fit;
        float renderHeight = canvasHeight * fit;
        float renderX = x + (width - renderWidth) * 0.5F;
        float renderY = y + (height - renderHeight) * 0.5F;
        float elapsed = elapsedSeconds(partialTick);
        Live2DMousePose pose = Live2DMousePose.fromMinecraftMouse(mouseX, mouseY, renderX, renderY,
                renderWidth, renderHeight, config, elapsed);
        MotionContext context = new MotionContext(0.0F, false, 0.0F);
        return renderModel(graphics, model, config, renderX, renderY, renderWidth, renderHeight, pose,
                elapsed, context, "mouse preview");
    }

    public boolean renderHudWithMouse(GuiGraphics graphics, Live2DModel model, Live2DConfig config,
                                      float mouseX, float mouseY, float width, float height,
                                      float verticalMotion, float horizontalSpeed, boolean airborne,
                                      float lipSync, float partialTick) {
        if (!ensureLoaded(model)) {
            return false;
        }

        float canvasWidth = loadedModel.canvasWidth();
        float canvasHeight = loadedModel.canvasHeight();
        float scale = Math.max(0.01F, config.scale);
        float renderWidth = canvasWidth * scale;
        float renderHeight = canvasHeight * scale;
        float bobY = clamp(verticalMotion, -1.0F, 1.0F)
                * Math.max(VERTICAL_BOB_MIN_PIXELS, renderHeight * VERTICAL_BOB_HEIGHT_RATIO);
        float x = config.x;
        float y = config.y + bobY;
        float elapsed = elapsedSeconds(partialTick);
        Live2DMousePose pose = Live2DMousePose.fromMinecraftMouse(mouseX, mouseY, x, y,
                renderWidth, renderHeight, config, elapsed);
        MotionContext context = new MotionContext(horizontalSpeed, airborne, lipSync);
        return renderModel(graphics, model, config, x, y, renderWidth, renderHeight, pose,
                elapsed, context, "mouse HUD");
    }

    public String status() {
        return status;
    }

    public Bounds lastBounds() {
        return lastBounds;
    }

    public boolean prepareCore() {
        return ensureCoreLoaded();
    }

    public void clearBounds() {
        lastBounds = Bounds.EMPTY;
    }

    public void playMotion(String name, boolean loop) {
        transientMotionName = name == null ? "" : name;
        transientMotionStartedAt = Float.NEGATIVE_INFINITY;
        transientMotionPending = true;
        if (!transientMotionName.isBlank() && loadedModel != null) {
            transientMotionStartedAt = elapsedSeconds(0.0F);
            transientMotionPending = false;
        }
    }

    public void clear() {
        synchronized (frameLock) {
            releaseModel();
            loadedKey = "";
            if (core == null) {
                status = "Cubism 渲染后端未加载";
            }
            transientMotionName = "";
            transientMotionStartedAt = Float.NEGATIVE_INFINITY;
            transientMotionPending = false;
            lastBounds = Bounds.EMPTY;
            stencilBufferPrepared = false;
            stencilBufferReady = false;
            preparedFrame = PreparedFrame.EMPTY;
        }
    }

    private boolean renderModel(GuiGraphics graphics, Live2DModel sourceModel, Live2DConfig config,
                                float x, float y, float width, float height,
                                Live2DMousePose pose, float elapsedSeconds, MotionContext context,
                                String driverStatus) {
        try {
            lastBounds = new Bounds(x, y, width, height);
            loadedModel.applyParameters(sourceModel, config, pose, elapsedSeconds, context,
                    new TransientMotionState(transientMotionName, transientMotionStartedAt));
            core.csmUpdateModel(loadedModel.modelPointer);
            loadedModel.refreshDynamicDrawableData();
            boolean clipped = drawDrawables(graphics, loadedModel, config, x, y, width, height);
            core.csmResetDrawableDynamicFlags(loadedModel.modelPointer);
            status = loadedModel.hasClippingMasks
                    ? "Cubism Core 已渲染 / " + driverStatus + loadedModel.featureStatus(sourceModel)
                    + " / clipping " + (clipped ? "stencil" : "fallback")
                    : "Cubism Core 已渲染 / " + driverStatus + loadedModel.featureStatus(sourceModel);
            return true;
        } catch (Throwable throwable) {
            status = "Cubism 渲染错误: " + safeMessage(throwable);
            return false;
        }
    }

    private float elapsedSeconds(float partialTick) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0F + partialTick / 20.0F;
    }

    private boolean ensureLoaded(Live2DModel model) {
        if (model == null || !model.valid()) {
            status = model == null ? "未选择模型" : model.error();
            return false;
        }

        String key = model.modelJson() == null ? model.name() : model.modelJson().toAbsolutePath().normalize().toString();
        if (key.equals(loadedKey) && loadedModel != null) {
            return true;
        }

        releaseModel();
        loadedKey = key;
        if (!ensureCoreLoaded()) {
            return false;
        }

        try {
            loadedModel = CubismModel.load(core, model);
            if (transientMotionPending) {
                transientMotionStartedAt = elapsedSeconds(0.0F);
                transientMotionPending = false;
            }
            status = "Cubism Core 已加载 / " + model.textures().size() + " 纹理 / MC鼠标驱动";
            return true;
        } catch (Throwable throwable) {
            status = "Cubism 加载失败: " + safeMessage(throwable);
            releaseModel();
            return false;
        }
    }

    private boolean ensureCoreLoaded() {
        if (core != null) {
            return true;
        }

        Live2DNativeLibraryInstaller.install(Live2DConfig.CORE_DIR);

        List<String> errors = new ArrayList<>();
        for (Path candidate : coreCandidates()) {
            if (candidate == null || !Files.exists(candidate)) {
                continue;
            }
            try {
                core = Native.load(candidate.toAbsolutePath().normalize().toString(), Core.class);
                status = "Cubism Core 已加载: " + candidate.getFileName();
                return true;
            } catch (Throwable throwable) {
                errors.add(candidate.getFileName() + ": " + safeMessage(throwable));
            }
        }

        try {
            core = Native.load("Live2DCubismCore", Core.class);
            status = "Cubism Core 已加载: java.library.path";
            return true;
        } catch (Throwable throwable) {
            errors.add("java.library.path: " + safeMessage(throwable));
        }

        status = "Cubism Core 未找到：放到 " + Live2DConfig.CORE_DIR.resolve("Live2DCubismCore.dll");
        if (!errors.isEmpty()) {
            status = status + " / " + errors.get(0);
        }
        return false;
    }

    private List<Path> coreCandidates() {
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getProperty("live2dplayer.core", "");
        if (!explicit.isBlank()) {
            candidates.add(Path.of(explicit));
        }
        candidates.add(Live2DConfig.CORE_DIR.resolve("Live2DCubismCore.dll"));
        candidates.add(Live2DConfig.CORE_DIR.resolve("Live2DCubismCore64.dll"));
        return candidates;
    }

    private void releaseModel() {
        if (loadedModel != null) {
            loadedModel.release();
            loadedModel = null;
        }
    }

    private boolean drawDrawables(GuiGraphics graphics, CubismModel model, Live2DConfig config,
                                  float x, float y, float width, float height) {
        Matrix4f matrix = graphics.pose().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        boolean stencilReady = model.hasClippingMasks && prepareStencilBuffer();
        StencilState stencilState = stencilReady ? StencilState.capture() : null;
        boolean usedStencil = false;
        DrawableBatch batch = new DrawableBatch();
        StencilFrame stencilFrame = new StencilFrame();

        try {
            for (int drawable : model.drawableOrder) {
                if (!model.visible(drawable)) {
                    continue;
                }

                float alpha = clamp(config.alpha * model.opacity(drawable), 0.0F, 1.0F);
                if (stencilReady && model.maskCount(drawable) > 0) {
                    flushDrawableBatch(batch);
                    StencilReference stencilReference = stencilFrame.next();
                    beginStencilMask(stencilReference);
                    boolean maskWritten = drawClippingMasks(matrix, model, config, x, y, width, height, drawable);
                    if (maskWritten) {
                        usedStencil = true;
                        beginStencilDraw(model.invertedMask(drawable), stencilReference.value());
                        applyBlend(model.constantFlags(drawable));
                        drawDrawableGeometry(matrix, model, config, x, y, width, height, drawable, alpha);
                        finishStencilDraw();
                        continue;
                    }
                    finishStencilDraw();
                }

                drawDrawableGeometry(batch, matrix, model, config, x, y, width, height, drawable, alpha);
            }
            flushDrawableBatch(batch);
        } finally {
            flushDrawableBatch(batch);
            if (stencilState != null) {
                stencilState.restore();
            }
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return usedStencil;
    }

    private void flushDrawableBatch(DrawableBatch batch) {
        if (batch.active) {
            Tesselator.getInstance().end();
            batch.reset();
        }
    }

    private boolean drawClippingMasks(Matrix4f matrix, CubismModel model, Live2DConfig config,
                                      float x, float y, float width, float height, int drawable) {
        boolean wrote = false;
        int maskCount = model.maskCount(drawable);
        for (int i = 0; i < maskCount; i++) {
            int maskDrawable = model.maskDrawable(drawable, i);
            if (maskDrawable < 0 || maskDrawable >= model.drawableCount || !model.visible(maskDrawable)) {
                continue;
            }
            wrote |= drawDrawableGeometry(matrix, model, config, x, y, width, height, maskDrawable, 1.0F);
        }
        return wrote;
    }

    private boolean drawDrawableGeometry(Matrix4f matrix, CubismModel model, Live2DConfig config,
                                         float x, float y, float width, float height,
                                         int drawable, float alpha) {
        int textureIndex = model.textureIndex(drawable);
        if (textureIndex < 0 || textureIndex >= model.textures.size()) {
            return false;
        }
        Live2DTexture texture = model.textures.get(textureIndex);
        if (!texture.valid()) {
            return false;
        }

        int indexCount = model.indexCount(drawable);
        int vertexCount = model.vertexCount(drawable);
        int[] indices = model.indices(drawable);
        float[] positions = model.vertexPositions(drawable);
        float[] uvs = model.vertexUvs(drawable);
        if (indexCount <= 0 || vertexCount <= 0 || indices.length == 0 || positions.length == 0 || uvs.length == 0) {
            return false;
        }

        RenderSystem.setShaderTexture(0, texture.location());
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        putDrawableVertices(buffer, matrix, model, config, x, y, width, height,
                positions, uvs, indices, vertexCount, alpha);
        Tesselator.getInstance().end();
        return true;
    }

    private boolean drawDrawableGeometry(DrawableBatch batch, Matrix4f matrix, CubismModel model, Live2DConfig config,
                                         float x, float y, float width, float height,
                                         int drawable, float alpha) {
        int textureIndex = model.textureIndex(drawable);
        if (textureIndex < 0 || textureIndex >= model.textures.size()) {
            return false;
        }
        Live2DTexture texture = model.textures.get(textureIndex);
        if (!texture.valid()) {
            return false;
        }

        int indexCount = model.indexCount(drawable);
        int vertexCount = model.vertexCount(drawable);
        int[] indices = model.indices(drawable);
        float[] positions = model.vertexPositions(drawable);
        float[] uvs = model.vertexUvs(drawable);
        if (indexCount <= 0 || vertexCount <= 0 || indices.length == 0 || positions.length == 0 || uvs.length == 0) {
            return false;
        }

        int blendFlags = model.blendFlags(drawable);
        if (!batch.accepts(textureIndex, blendFlags)) {
            flushDrawableBatch(batch);
            RenderSystem.setShaderTexture(0, texture.location());
            applyBlend(model.constantFlags(drawable));
            batch.begin(textureIndex, blendFlags, Tesselator.getInstance().getBuilder());
        }

        putDrawableVertices(batch.buffer, matrix, model, config, x, y, width, height,
                positions, uvs, indices, vertexCount, alpha);
        return true;
    }

    private boolean prepareStencilBuffer() {
        if (stencilBufferPrepared) {
            return stencilBufferReady;
        }
        stencilBufferPrepared = true;
        try {
            if (!Minecraft.getInstance().getMainRenderTarget().isStencilEnabled()) {
                Minecraft.getInstance().getMainRenderTarget().enableStencil();
            }
            stencilBufferReady = Minecraft.getInstance().getMainRenderTarget().isStencilEnabled();
            return stencilBufferReady;
        } catch (Throwable ignored) {
            stencilBufferReady = false;
            return false;
        }
    }

    private void beginStencilMask(StencilReference reference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilMask(0xFF);
        if (reference.clear()) {
            GL11C.glClearStencil(0);
            GL11C.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
        }
        GL11C.glStencilFunc(GL11C.GL_ALWAYS, reference.value(), 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
        GlStateManager._colorMask(false, false, false, false);
        GlStateManager._depthMask(false);
        RenderSystem.disableBlend();
    }

    private void beginStencilDraw(boolean inverted, int reference) {
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(false);
        GL11C.glStencilMask(0x00);
        GL11C.glStencilFunc(inverted ? GL11C.GL_NOTEQUAL : GL11C.GL_EQUAL, reference, 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        RenderSystem.enableBlend();
    }

    private void finishStencilDraw() {
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(false);
        GL11C.glStencilMask(0xFF);
        GL11C.glStencilFunc(GL11C.GL_ALWAYS, 0, 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        RenderSystem.enableBlend();
    }

    private void putDrawableVertices(BufferBuilder buffer, Matrix4f matrix, CubismModel model, Live2DConfig config,
                                     float x, float y, float width, float height,
                                     float[] positions, float[] uvs, int[] indices, int vertexCount, float alpha) {
        for (int vertexIndex : indices) {
            if (vertexIndex < 0 || vertexIndex >= vertexCount) {
                continue;
            }
            int vertexOffset = vertexIndex * 2;
            if (vertexOffset + 1 >= positions.length || vertexOffset + 1 >= uvs.length) {
                continue;
            }
            putVertex(buffer, matrix, model, config, x, y, width, height, positions, uvs, vertexOffset, alpha);
        }
    }

    private void putVertex(BufferBuilder buffer, Matrix4f matrix, CubismModel model, Live2DConfig config,
                           float x, float y, float width, float height,
                           float[] positions, float[] uvs, int vertexOffset, float alpha) {
        float modelX = positions[vertexOffset];
        float modelY = positions[vertexOffset + 1];
        float pixelX = model.originX + modelX * model.pixelsPerUnit;
        float pixelY = model.originY - modelY * model.pixelsPerUnit;
        if (!model.canvasValid()) {
            pixelX = (modelX - model.minX) / Math.max(0.0001F, model.maxX - model.minX) * model.canvasWidth();
            pixelY = (model.maxY - modelY) / Math.max(0.0001F, model.maxY - model.minY) * model.canvasHeight();
        }

        float screenX = config.mirror
                ? x + width - pixelX / Math.max(1.0F, model.canvasWidth()) * width
                : x + pixelX / Math.max(1.0F, model.canvasWidth()) * width;
        float screenY = y + pixelY / Math.max(1.0F, model.canvasHeight()) * height;
        float u = uvs[vertexOffset];
        float v = 1.0F - uvs[vertexOffset + 1];
        buffer.vertex(matrix, screenX, screenY, 0.0F).uv(u, v).color(1.0F, 1.0F, 1.0F, alpha).endVertex();
    }

    private void applyBlend(int flags) {
        if ((flags & FLAG_BLEND_ADDITIVE) != 0) {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        } else if ((flags & FLAG_BLEND_MULTIPLICATIVE) != 0) {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
    }

    private static class DrawableBatch {
        private boolean active;
        private int textureIndex = -1;
        private int blendFlags;
        private BufferBuilder buffer;

        private boolean accepts(int textureIndex, int blendFlags) {
            return active && this.textureIndex == textureIndex && this.blendFlags == blendFlags;
        }

        private void begin(int textureIndex, int blendFlags, BufferBuilder buffer) {
            this.active = true;
            this.textureIndex = textureIndex;
            this.blendFlags = blendFlags;
            this.buffer = buffer;
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        }

        private void reset() {
            active = false;
            textureIndex = -1;
            blendFlags = 0;
            buffer = null;
        }
    }

    private static class StencilFrame {
        private int nextReference = 1;
        private boolean clearPending = true;

        private StencilReference next() {
            if (nextReference > MAX_STENCIL_REFERENCE) {
                nextReference = 1;
                clearPending = true;
            }
            StencilReference reference = new StencilReference(nextReference++, clearPending);
            clearPending = false;
            return reference;
        }
    }

    private record StencilReference(int value, boolean clear) {
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message;
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record StencilState(boolean stencilEnabled,
                                boolean depthMask,
                                boolean[] colorMask) {
        private static StencilState capture() {
            return new StencilState(
                    GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
                    readBoolean(GL11C.GL_DEPTH_WRITEMASK, true),
                    readColorMask()
            );
        }

        private void restore() {
            if (stencilEnabled) {
                GL11C.glEnable(GL11C.GL_STENCIL_TEST);
            } else {
                GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            }
            GL11C.glStencilFunc(GL11C.GL_ALWAYS, 0, 0xFF);
            GL11C.glStencilMask(0xFF);
            GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
            GL11C.glClearStencil(0);
            GlStateManager._depthMask(depthMask);
            GlStateManager._colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }

        private static boolean readBoolean(int pname, boolean fallback) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer value = stack.malloc(1);
                GL11C.glGetBooleanv(pname, value);
                return value.get(0) != 0;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static boolean[] readColorMask() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer value = stack.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, value);
                return new boolean[] {
                        value.get(0) != 0,
                        value.get(1) != 0,
                        value.get(2) != 0,
                        value.get(3) != 0
                };
            } catch (Throwable ignored) {
                return new boolean[] {true, true, true, true};
            }
        }
    }

    public interface Core extends Library {
        Pointer csmReviveMocInPlace(Pointer address, int size);

        int csmGetSizeofModel(Pointer moc);

        Pointer csmInitializeModelInPlace(Pointer moc, Pointer address, int size);

        void csmUpdateModel(Pointer model);

        void csmResetDrawableDynamicFlags(Pointer model);

        void csmReadCanvasInfo(Pointer model, CsmVector2 outSizeInPixels, CsmVector2 outOriginInPixels,
                               FloatByReference outPixelsPerUnit);

        int csmGetParameterCount(Pointer model);

        Pointer csmGetParameterIds(Pointer model);

        Pointer csmGetParameterValues(Pointer model);

        Pointer csmGetParameterMinimumValues(Pointer model);

        Pointer csmGetParameterMaximumValues(Pointer model);

        Pointer csmGetParameterDefaultValues(Pointer model);

        int csmGetDrawableCount(Pointer model);

        Pointer csmGetDrawableConstantFlags(Pointer model);

        Pointer csmGetDrawableDynamicFlags(Pointer model);

        Pointer csmGetDrawableTextureIndices(Pointer model);

        Pointer csmGetDrawableRenderOrders(Pointer model);

        Pointer csmGetRenderOrders(Pointer model);

        Pointer csmGetDrawableDrawOrders(Pointer model);

        Pointer csmGetDrawableOpacities(Pointer model);

        Pointer csmGetDrawableMaskCounts(Pointer model);

        Pointer csmGetDrawableMasks(Pointer model);

        Pointer csmGetDrawableVertexCounts(Pointer model);

        Pointer csmGetDrawableVertexPositions(Pointer model);

        Pointer csmGetDrawableVertexUvs(Pointer model);

        Pointer csmGetDrawableIndexCounts(Pointer model);

        Pointer csmGetDrawableIndices(Pointer model);
    }

    public static class CsmVector2 extends Structure {
        public float X;
        public float Y;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("X", "Y");
        }
    }

    private static class CubismModel implements Live2DMotion.Parameters, Live2DPhysics.Parameters {
        private final Core core;
        private final AlignedMemory mocMemory;
        private final AlignedMemory modelMemory;
        private final Pointer mocPointer;
        private final Pointer modelPointer;
        private final List<Live2DTexture> textures;
        private final int[] textureGlIds;
        private final long layoutId;
        private final Map<String, Integer> parameterIndex = new HashMap<>();
        private final int parameterCount;
        private final Pointer parameterValues;
        private final Pointer parameterMinimumValues;
        private final Pointer parameterMaximumValues;
        private final Pointer parameterDefaultValues;
        private final int drawableCount;
        private final Pointer drawableConstantFlags;
        private final Pointer drawableDynamicFlags;
        private final Pointer drawableTextureIndices;
        private final Pointer drawableRenderOrders;
        private final Pointer drawableOpacities;
        private final Pointer drawableMaskCounts;
        private final Pointer drawableMasks;
        private final Pointer drawableVertexCounts;
        private final Pointer drawableVertexPositions;
        private final Pointer drawableVertexUvs;
        private final Pointer drawableIndexCounts;
        private final Pointer drawableIndices;
        private final int[] drawableOrder;
        private final float[] parameterValuesCache;
        private final float[] parameterWrittenValuesCache;
        private final float[] parameterMinimumValuesCache;
        private final float[] parameterMaximumValuesCache;
        private final float[] parameterDefaultValuesCache;
        private final byte[] drawableDynamicFlagsCache;
        private final int[] drawableConstantFlagsCache;
        private final int[] drawableTextureIndicesCache;
        private final int[] drawableRenderOrdersCache;
        private final float[] drawableOpacitiesCache;
        private final int[] drawableMaskCountsCache;
        private final int[][] drawableMasksCache;
        private final int[] drawableVertexCountsCache;
        private final int[] drawableIndexCountsCache;
        private final float[][] drawableVertexPositionsCache;
        private final float[][] drawableVertexUvsCache;
        private final int[][] drawableIndicesCache;
        private final float canvasWidth;
        private final float canvasHeight;
        private final float originX;
        private final float originY;
        private final float pixelsPerUnit;
        private final float minX;
        private final float minY;
        private final float maxX;
        private final float maxY;
        private final boolean hasClippingMasks;
        private final Live2DPhysics physics;
        private float lastPhysicsElapsed = Float.NaN;
        private boolean parameterValuesDirty;
        private boolean drawableDataInitialized;

        private CubismModel(Core core, AlignedMemory mocMemory, AlignedMemory modelMemory, Pointer mocPointer,
                            Pointer modelPointer, List<Live2DTexture> textures, int parameterCount,
                            Pointer parameterValues, Pointer parameterMinimumValues, Pointer parameterMaximumValues,
                            Pointer parameterDefaultValues, int drawableCount, Pointer drawableConstantFlags,
                            Pointer drawableDynamicFlags, Pointer drawableTextureIndices, Pointer drawableRenderOrders,
                            Pointer drawableOpacities, Pointer drawableMaskCounts, Pointer drawableMasks,
                            Pointer drawableVertexCounts, Pointer drawableVertexPositions, Pointer drawableVertexUvs,
                            Pointer drawableIndexCounts, Pointer drawableIndices, float canvasWidth, float canvasHeight,
                            float originX, float originY, float pixelsPerUnit, float minX, float minY, float maxX,
                            float maxY, boolean hasClippingMasks, Live2DPhysics physics) {
            this.core = core;
            this.mocMemory = mocMemory;
            this.modelMemory = modelMemory;
            this.mocPointer = mocPointer;
            this.modelPointer = modelPointer;
            this.textures = textures;
            this.textureGlIds = new int[textures.size()];
            for (int i = 0; i < textures.size(); i++) {
                this.textureGlIds[i] = textures.get(i).glId();
            }
            this.layoutId = NEXT_LAYOUT_ID.getAndIncrement();
            this.parameterCount = parameterCount;
            this.parameterValues = parameterValues;
            this.parameterMinimumValues = parameterMinimumValues;
            this.parameterMaximumValues = parameterMaximumValues;
            this.parameterDefaultValues = parameterDefaultValues;
            this.drawableCount = drawableCount;
            this.drawableConstantFlags = drawableConstantFlags;
            this.drawableDynamicFlags = drawableDynamicFlags;
            this.drawableTextureIndices = drawableTextureIndices;
            this.drawableRenderOrders = drawableRenderOrders;
            this.drawableOpacities = drawableOpacities;
            this.drawableMaskCounts = drawableMaskCounts;
            this.drawableMasks = drawableMasks;
            this.drawableVertexCounts = drawableVertexCounts;
            this.drawableVertexPositions = drawableVertexPositions;
            this.drawableVertexUvs = drawableVertexUvs;
            this.drawableIndexCounts = drawableIndexCounts;
            this.drawableIndices = drawableIndices;
            this.parameterValuesCache = readFloatArray(parameterValues, parameterCount, 0.0F);
            this.parameterWrittenValuesCache = this.parameterValuesCache.clone();
            this.parameterMinimumValuesCache = readFloatArray(parameterMinimumValues, parameterCount, 0.0F);
            this.parameterMaximumValuesCache = readFloatArray(parameterMaximumValues, parameterCount, 1.0F);
            this.parameterDefaultValuesCache = readFloatArray(parameterDefaultValues, parameterCount, 0.0F);
            if (parameterDefaultValues == null && this.parameterValuesCache.length == this.parameterDefaultValuesCache.length) {
                System.arraycopy(this.parameterValuesCache, 0, this.parameterDefaultValuesCache, 0,
                        this.parameterDefaultValuesCache.length);
            }
            this.drawableDynamicFlagsCache = new byte[Math.max(0, drawableCount)];
            this.drawableConstantFlagsCache = readUnsignedByteArray(drawableConstantFlags, drawableCount);
            this.drawableTextureIndicesCache = readIntArray(drawableTextureIndices, drawableCount, -1);
            this.drawableRenderOrdersCache = readRenderOrders(drawableRenderOrders, drawableCount);
            this.drawableOpacitiesCache = readFloatArray(drawableOpacities, drawableCount, 1.0F);
            this.drawableMaskCountsCache = readNonNegativeIntArray(drawableMaskCounts, drawableCount);
            this.drawableMasksCache = readMasks(drawableMasks, this.drawableMaskCountsCache);
            this.drawableVertexCountsCache = readNonNegativeIntArray(drawableVertexCounts, drawableCount);
            this.drawableIndexCountsCache = readNonNegativeIntArray(drawableIndexCounts, drawableCount);
            this.drawableVertexPositionsCache = readDrawableFloatArrays(drawableVertexPositions, this.drawableVertexCountsCache, 2);
            this.drawableVertexUvsCache = readDrawableFloatArrays(drawableVertexUvs, this.drawableVertexCountsCache, 2);
            this.drawableIndicesCache = readDrawableIndexArrays(drawableIndices, this.drawableIndexCountsCache);
            this.drawableOrder = new int[Math.max(0, drawableCount)];
            for (int i = 0; i < this.drawableOrder.length; i++) {
                this.drawableOrder[i] = i;
            }
            updateDrawableOrder();
            refreshDynamicDrawableData();
            this.canvasWidth = canvasWidth;
            this.canvasHeight = canvasHeight;
            this.originX = originX;
            this.originY = originY;
            this.pixelsPerUnit = pixelsPerUnit;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.hasClippingMasks = hasClippingMasks;
            this.physics = physics;
        }

        private static CubismModel load(Core core, Live2DModel model) throws Exception {
            byte[] mocBytes = Files.readAllBytes(model.moc());
            AlignedMemory mocMemory = new AlignedMemory(mocBytes.length, MEMORY_ALIGNMENT);
            mocMemory.pointer.write(0, mocBytes, 0, mocBytes.length);
            Pointer mocPointer = core.csmReviveMocInPlace(mocMemory.pointer, mocBytes.length);
            if (mocPointer == null || Pointer.nativeValue(mocPointer) == 0L) {
                throw new IllegalStateException("csmReviveMocInPlace 返回空指针");
            }

            int modelSize = core.csmGetSizeofModel(mocPointer);
            if (modelSize <= 0) {
                throw new IllegalStateException("csmGetSizeofModel 返回 " + modelSize);
            }
            AlignedMemory modelMemory = new AlignedMemory(modelSize, MEMORY_ALIGNMENT);
            Pointer modelPointer = core.csmInitializeModelInPlace(mocPointer, modelMemory.pointer, modelSize);
            if (modelPointer == null || Pointer.nativeValue(modelPointer) == 0L) {
                throw new IllegalStateException("csmInitializeModelInPlace 返回空指针");
            }

            List<Live2DTexture> textures = new ArrayList<>();
            for (int i = 0; i < model.textures().size(); i++) {
                textures.add(Live2DTexture.load(model.textures().get(i), model.name() + "/texture_" + i));
            }

            int parameterCount = core.csmGetParameterCount(modelPointer);
            Pointer parameterIds = core.csmGetParameterIds(modelPointer);
            Pointer parameterValues = core.csmGetParameterValues(modelPointer);
            Pointer parameterMinimumValues = core.csmGetParameterMinimumValues(modelPointer);
            Pointer parameterMaximumValues = core.csmGetParameterMaximumValues(modelPointer);
            Pointer parameterDefaultValues = core.csmGetParameterDefaultValues(modelPointer);

            int drawableCount = core.csmGetDrawableCount(modelPointer);
            Pointer drawableConstantFlags = core.csmGetDrawableConstantFlags(modelPointer);
            Pointer drawableDynamicFlags = core.csmGetDrawableDynamicFlags(modelPointer);
            Pointer drawableTextureIndices = core.csmGetDrawableTextureIndices(modelPointer);
            Pointer drawableRenderOrders = renderOrders(core, modelPointer);
            Pointer drawableOpacities = core.csmGetDrawableOpacities(modelPointer);
            Pointer drawableMaskCounts = core.csmGetDrawableMaskCounts(modelPointer);
            Pointer drawableMasks = drawableMasks(core, modelPointer);
            Pointer drawableVertexCounts = core.csmGetDrawableVertexCounts(modelPointer);
            Pointer drawableVertexPositions = core.csmGetDrawableVertexPositions(modelPointer);
            Pointer drawableVertexUvs = core.csmGetDrawableVertexUvs(modelPointer);
            Pointer drawableIndexCounts = core.csmGetDrawableIndexCounts(modelPointer);
            Pointer drawableIndices = core.csmGetDrawableIndices(modelPointer);

            CsmVector2 size = new CsmVector2();
            CsmVector2 origin = new CsmVector2();
            FloatByReference pixelsPerUnit = new FloatByReference(1.0F);
            core.csmReadCanvasInfo(modelPointer, size, origin, pixelsPerUnit);
            size.read();
            origin.read();

            ModelBounds bounds = readBounds(drawableCount, drawableVertexCounts, drawableVertexPositions);
            boolean hasMasks = hasMasks(drawableCount, drawableMaskCounts, drawableMasks);
            Live2DPhysics physics = loadPhysics(model);
            CubismModel cubismModel = new CubismModel(core, mocMemory, modelMemory, mocPointer, modelPointer,
                    textures, parameterCount, parameterValues, parameterMinimumValues, parameterMaximumValues,
                    parameterDefaultValues, drawableCount, drawableConstantFlags, drawableDynamicFlags,
                    drawableTextureIndices, drawableRenderOrders, drawableOpacities, drawableMaskCounts, drawableMasks,
                    drawableVertexCounts, drawableVertexPositions, drawableVertexUvs, drawableIndexCounts,
                    drawableIndices, positive(size.X, bounds.width()), positive(size.Y, bounds.height()),
                    origin.X, origin.Y, positive(pixelsPerUnit.getValue(), 1.0F),
                    bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY(), hasMasks, physics);

            cubismModel.readParameterIds(parameterIds);
            return cubismModel;
        }

        private void updateDrawableOrder() {
            for (int i = 1; i < drawableOrder.length; i++) {
                int drawable = drawableOrder[i];
                int j = i - 1;
                while (j >= 0 && compareDrawableOrder(drawableOrder[j], drawable) > 0) {
                    drawableOrder[j + 1] = drawableOrder[j];
                    j--;
                }
                drawableOrder[j + 1] = drawable;
            }
        }

        private void refreshDynamicDrawableData() {
            readBytes(drawableDynamicFlags, drawableDynamicFlagsCache);
            if (drawableDynamicFlags == null) {
                Arrays.fill(drawableDynamicFlagsCache,
                        (byte) (FLAG_IS_VISIBLE | FLAG_VERTEX_POSITIONS_CHANGED));
            }
            boolean refreshAll = !drawableDataInitialized || drawableDynamicFlags == null;
            if (refreshAll || hasDynamicFlag(FLAG_RENDER_ORDER_CHANGED)) {
                readInts(drawableRenderOrders, drawableRenderOrdersCache);
                updateDrawableOrder();
            }
            if (refreshAll || hasDynamicFlag(FLAG_OPACITY_CHANGED)) {
                readFloats(drawableOpacities, drawableOpacitiesCache);
            }
            for (int drawable = 0; drawable < drawableVertexPositionsCache.length; drawable++) {
                if (!refreshAll
                        && (drawableDynamicFlagsCache[drawable] & FLAG_VERTEX_POSITIONS_CHANGED) == 0) {
                    continue;
                }
                float[] positions = drawableVertexPositionsCache[drawable];
                if (positions.length == 0) {
                    continue;
                }
                Pointer pointer = pointerAt(drawableVertexPositions, drawable);
                if (pointer != null) {
                    pointer.read(0, positions, 0, positions.length);
                }
            }
            drawableDataInitialized = true;
        }

        private boolean hasDynamicFlag(int flag) {
            for (byte dynamicFlag : drawableDynamicFlagsCache) {
                if ((dynamicFlag & flag) != 0) {
                    return true;
                }
            }
            return false;
        }

        private int compareDrawableOrder(int first, int second) {
            int order = Integer.compare(renderOrder(first), renderOrder(second));
            if (order != 0) {
                return order;
            }
            int mask = Integer.compare(maskCount(first) > 0 ? 1 : 0, maskCount(second) > 0 ? 1 : 0);
            if (mask != 0) {
                return mask;
            }
            int texture = Integer.compare(textureIndex(first), textureIndex(second));
            if (texture != 0) {
                return texture;
            }
            int blend = Integer.compare(blendFlags(first), blendFlags(second));
            if (blend != 0) {
                return blend;
            }
            return Integer.compare(first, second);
        }

        private void readParameterIds(Pointer parameterIds) {
            if (parameterIds == null) {
                return;
            }
            for (int i = 0; i < parameterCount; i++) {
                Pointer pointer = parameterIds.getPointer((long) i * POINTER_SIZE);
                if (pointer == null) {
                    continue;
                }
                String id = pointer.getString(0);
                if (id != null && !id.isBlank()) {
                    parameterIndex.put(id, i);
                }
            }
        }

        private String featureStatus(Live2DModel sourceModel) {
            List<String> features = new ArrayList<>();
            if (sourceModel != null && !sourceModel.motions().isEmpty()) {
                features.add("motion");
            }
            if (sourceModel != null && !sourceModel.groupIds("EyeBlink").isEmpty()) {
                features.add("blink");
            }
            if (physics != null && !physics.empty()) {
                features.add("physics");
            }
            return features.isEmpty() ? "" : " / " + String.join("+", features);
        }

        private void applyParameters(Live2DModel sourceModel, Live2DConfig config, Live2DMousePose pose,
                                     float elapsedSeconds, MotionContext context,
                                     TransientMotionState transientMotion) {
            resetParameters();
            Live2DMotion motion = selectMotion(sourceModel, config, context, elapsedSeconds, transientMotion);
            boolean motionControlsBlink = applyMotion(sourceModel, motion, elapsedSeconds,
                    config.motionScale, transientMotion);
            applyPose(pose, motion);
            applyEyeBlink(sourceModel, config, elapsedSeconds, motionControlsBlink);
            applyLipSync(sourceModel, config, elapsedSeconds, context.lipSync());
            applyExpression(sourceModel, config);
            applyPhysics(elapsedSeconds, config);
            writeParameterValues();
        }

        private Live2DMotion selectMotion(Live2DModel sourceModel, Live2DConfig config,
                                          MotionContext context, float elapsedSeconds,
                                          TransientMotionState transientMotion) {
            if (sourceModel == null || sourceModel.motions().isEmpty()) {
                return null;
            }

            if (transientMotion != null && !transientMotion.name().isBlank()) {
                Live2DMotion motion = findMotion(sourceModel, transientMotion.name());
                if (motion != null && (motion.loop()
                        || elapsedSeconds - transientMotion.startedAt() < motion.duration())) {
                    return motion;
                }
            }

            Live2DConfig.MotionMode mode = config == null
                    ? Live2DConfig.MotionMode.AUTO
                    : config.motionMode;
            return switch (mode) {
                case SELECTED -> findMotion(sourceModel, config.selectedMotion);
                case RANDOM -> randomMotion(sourceModel, elapsedSeconds);
                case OFF -> null;
                case AUTO -> automaticMotion(sourceModel, context);
            };
        }

        private Live2DMotion automaticMotion(Live2DModel sourceModel, MotionContext context) {
            if (context != null && context.airborne()) {
                Live2DMotion jump = findMotion(sourceModel, "Jump");
                if (jump != null) {
                    return jump;
                }
            }

            if (context != null && context.horizontalSpeed() > 0.14F) {
                Live2DMotion run = findMotion(sourceModel, "Run");
                if (run != null) {
                    return run;
                }
                Live2DMotion walk = findMotion(sourceModel, "Walk");
                if (walk != null) {
                    return walk;
                }
            }

            Live2DMotion idle = findMotion(sourceModel, "Idle");
            if (idle != null) {
                return idle;
            }
            for (Live2DMotion motion : sourceModel.motions()) {
                if (motion.name().toLowerCase(Locale.ROOT).contains("idle")) {
                    return motion;
                }
            }
            return sourceModel.motions().get(0);
        }

        private Live2DMotion randomMotion(Live2DModel sourceModel, float elapsedSeconds) {
            List<Live2DMotion> motions = sourceModel.motions();
            int index = Math.floorMod((int) Math.floor(elapsedSeconds), motions.size());
            return motions.get(index);
        }

        private Live2DMotion findMotion(Live2DModel sourceModel, String name) {
            if (sourceModel == null || name == null || name.isBlank()) {
                return null;
            }
            for (Live2DMotion motion : sourceModel.motions()) {
                if (motion.name().equalsIgnoreCase(name)) {
                    return motion;
                }
            }
            for (Live2DMotion motion : sourceModel.motions()) {
                if (motion.name().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                    return motion;
                }
            }
            return null;
        }

        private boolean applyMotion(Live2DModel sourceModel, Live2DMotion motion,
                                    float elapsedSeconds, float scale,
                                    TransientMotionState transientMotion) {
            if (sourceModel == null || motion == null) {
                return false;
            }
            float startOffset = transientMotion != null
                    && motion.name().equalsIgnoreCase(transientMotion.name())
                    ? transientMotion.startedAt()
                    : 0.0F;
            motion.apply(elapsedSeconds - Math.max(0.0F, startOffset), this, scale);
            return motion.controlsAny(sourceModel.groupIds("EyeBlink"));
        }

        private void applyPose(Live2DMousePose pose, Live2DMotion motion) {
            add("ParamAngleX", pose.angleX());
            add("ParamAngleY", pose.angleY());
            add("ParamAngleZ", pose.angleZ());
            add("ParamEyeBallX", pose.eyeBallX());
            add("ParamEyeBallY", pose.eyeBallY());
            add("ParamBodyAngleX", pose.bodyAngleX());
            add("ParamBodyAngleY", pose.bodyAngleY());
            add("ParamBodyAngleZ", pose.bodyAngleZ());
            if (motion == null || !motion.controls("ParamBreath")) {
                set("ParamBreath", pose.breath());
            }
        }

        private void applyEyeBlink(Live2DModel sourceModel, Live2DConfig config, float elapsedSeconds,
                                   boolean motionControlsBlink) {
            if (sourceModel == null || motionControlsBlink
                    || (config != null && !config.autoBlink)) {
                return;
            }
            List<String> eyeBlinkIds = sourceModel.groupIds("EyeBlink");
            if (eyeBlinkIds.isEmpty()) {
                return;
            }

            float idleSpeed = config == null ? 1.0F : clamp(config.idleSpeed, 0.0F, 5.0F);
            if (idleSpeed <= 0.0F) {
                return;
            }

            float cycle = positiveModulo(elapsedSeconds * Math.max(0.25F, idleSpeed * 0.85F) + 1.1F, 4.2F);
            float openness = 1.0F;
            if (cycle < 0.10F) {
                openness = 1.0F - smooth(cycle / 0.10F);
            } else if (cycle < 0.17F) {
                openness = 0.0F;
            } else if (cycle < 0.34F) {
                openness = smooth((cycle - 0.17F) / 0.17F);
            }

            for (String id : eyeBlinkIds) {
                multiply(id, openness);
            }
        }

        private void applyLipSync(Live2DModel sourceModel, Live2DConfig config,
                                  float elapsedSeconds, float lipSync) {
            if (sourceModel == null || config == null || !config.lipSync
                    || lipSync <= 0.0F) {
                return;
            }

            List<String> ids = sourceModel.groupIds("LipSync");
            if (ids.isEmpty()) {
                return;
            }

            float wave = 0.5F + 0.5F * (float) Math.sin(elapsedSeconds * 11.0D);
            float amount = clamp(lipSync, 0.0F, 1.0F) * wave;
            for (String id : ids) {
                set(id, get(id) + amount * 0.55F);
            }
        }

        private void applyPhysics(float elapsedSeconds, Live2DConfig config) {
            if (physics == null || physics.empty()
                    || (config != null && !config.physicsEnabled)) {
                lastPhysicsElapsed = elapsedSeconds;
                return;
            }
            float delta = physicsDelta(elapsedSeconds);
            physics.update(this, delta, config == null ? 1.0F : config.physicsScale);
        }

        private void resetParameters() {
            if (parameterValuesCache.length == 0 || parameterDefaultValuesCache.length == 0) {
                return;
            }
            int length = Math.min(parameterValuesCache.length, parameterDefaultValuesCache.length);
            for (int i = 0; i < length; i++) {
                setParameterCacheValue(i, parameterDefaultValuesCache[i]);
            }
        }

        private void writeParameterValues() {
            if (parameterValues == null || parameterValuesCache.length == 0 || !parameterValuesDirty) {
                return;
            }
            if (!parameterValuesChangedSinceLastWrite()) {
                parameterValuesDirty = false;
                return;
            }
            parameterValues.write(0, parameterValuesCache, 0, parameterValuesCache.length);
            System.arraycopy(parameterValuesCache, 0, parameterWrittenValuesCache, 0,
                    Math.min(parameterValuesCache.length, parameterWrittenValuesCache.length));
            parameterValuesDirty = false;
        }

        private void applyExpression(Live2DModel sourceModel, Live2DConfig config) {
            if (sourceModel == null || config == null) {
                return;
            }

            if (!config.selectedExpressions.isEmpty()) {
                for (Live2DExpression expression : sourceModel.expressions()) {
                    if (!config.selectedExpressions.contains(expression.name())) {
                        continue;
                    }
                    for (Live2DExpression.Parameter parameter : expression.parameters()) {
                        applyExpressionParameter(parameter);
                    }
                }
            } else if (config.selectedExpression != null && !config.selectedExpression.isBlank()) {
                for (Live2DExpression expression : sourceModel.expressions()) {
                    if (expression.name().equals(config.selectedExpression)) {
                        for (Live2DExpression.Parameter parameter : expression.parameters()) {
                            applyExpressionParameter(parameter);
                        }
                        break;
                    }
                }
            }
        }

        private void applyExpressionParameter(Live2DExpression.Parameter parameter) {
            Integer index = parameterIndex.get(parameter.id());
            if (!validParameterIndex(index)) {
                return;
            }
            float current = parameterValuesCache[index];
            String blend = parameter.blend() == null ? "add" : parameter.blend().toLowerCase(Locale.ROOT);
            float next;
            if ("overwrite".equals(blend)) {
                next = parameter.value();
            } else if ("multiply".equals(blend)) {
                next = current * parameter.value();
            } else {
                next = current + parameter.value();
            }
            setParameterValue(index, next);
        }

        private void add(String id, float value) {
            set(id, get(id) + value);
        }

        private void multiply(String id, float value) {
            set(id, get(id) * value);
        }

        @Override
        public boolean contains(String id) {
            return parameterIndex.containsKey(id);
        }

        @Override
        public float get(String id) {
            Integer index = parameterIndex.get(id);
            if (!validParameterIndex(index)) {
                return 0.0F;
            }
            return parameterValuesCache[index];
        }

        @Override
        public float minimum(String id) {
            Integer index = parameterIndex.get(id);
            if (index == null || index < 0 || index >= parameterMinimumValuesCache.length) {
                return 0.0F;
            }
            return parameterMinimumValuesCache[index];
        }

        @Override
        public float maximum(String id) {
            Integer index = parameterIndex.get(id);
            if (index == null || index < 0 || index >= parameterMaximumValuesCache.length) {
                return 1.0F;
            }
            return parameterMaximumValuesCache[index];
        }

        @Override
        public float defaultValue(String id) {
            Integer index = parameterIndex.get(id);
            if (index == null || index < 0 || index >= parameterDefaultValuesCache.length) {
                return 0.0F;
            }
            return parameterDefaultValuesCache[index];
        }

        @Override
        public void set(String id, float value) {
            Integer index = parameterIndex.get(id);
            if (!validParameterIndex(index)) {
                return;
            }
            setParameterValue(index, value);
        }

        private void setParameterValue(int index, float value) {
            if (index < 0 || index >= parameterValuesCache.length) {
                return;
            }
            setParameterCacheValue(index, clampParameter(index, value));
        }

        private void setParameterCacheValue(int index, float next) {
            if (Float.compare(parameterValuesCache[index], next) != 0) {
                parameterValuesCache[index] = next;
                parameterValuesDirty = true;
            }
        }

        private boolean parameterValuesChangedSinceLastWrite() {
            int length = Math.min(parameterValuesCache.length, parameterWrittenValuesCache.length);
            for (int i = 0; i < length; i++) {
                if (Float.compare(parameterValuesCache[i], parameterWrittenValuesCache[i]) != 0) {
                    return true;
                }
            }
            return parameterValuesCache.length != parameterWrittenValuesCache.length;
        }

        private float physicsDelta(float elapsedSeconds) {
            if (!Float.isFinite(lastPhysicsElapsed)) {
                lastPhysicsElapsed = elapsedSeconds;
                return 1.0F / 60.0F;
            }
            float delta = elapsedSeconds - lastPhysicsElapsed;
            lastPhysicsElapsed = elapsedSeconds;
            return clamp(delta, 0.0F, 1.0F / 15.0F);
        }

        private float clampParameter(int index, float value) {
            if (index < 0 || index >= parameterMinimumValuesCache.length || index >= parameterMaximumValuesCache.length) {
                return value;
            }
            float min = parameterMinimumValuesCache[index];
            float max = parameterMaximumValuesCache[index];
            return clamp(value, min, max);
        }

        private boolean validParameterIndex(Integer index) {
            return index != null && index >= 0 && index < parameterValuesCache.length;
        }

        private int renderOrder(int drawable) {
            return drawable >= 0 && drawable < drawableRenderOrdersCache.length ? drawableRenderOrdersCache[drawable] : drawable;
        }

        private boolean visible(int drawable) {
            return drawableDynamicFlags == null || (drawable >= 0 && drawable < drawableDynamicFlagsCache.length
                    && (drawableDynamicFlagsCache[drawable] & FLAG_IS_VISIBLE) != 0);
        }

        private int constantFlags(int drawable) {
            return drawable >= 0 && drawable < drawableConstantFlagsCache.length ? drawableConstantFlagsCache[drawable] : 0;
        }

        private int blendFlags(int drawable) {
            return constantFlags(drawable) & (FLAG_BLEND_ADDITIVE | FLAG_BLEND_MULTIPLICATIVE);
        }

        private int textureIndex(int drawable) {
            return drawable >= 0 && drawable < drawableTextureIndicesCache.length ? drawableTextureIndicesCache[drawable] : -1;
        }

        private float opacity(int drawable) {
            return drawable >= 0 && drawable < drawableOpacitiesCache.length ? drawableOpacitiesCache[drawable] : 1.0F;
        }

        private int maskCount(int drawable) {
            return drawable >= 0 && drawable < drawableMaskCountsCache.length ? drawableMaskCountsCache[drawable] : 0;
        }

        private int maskDrawable(int drawable, int maskIndex) {
            if (maskIndex < 0 || maskIndex >= maskCount(drawable)) {
                return -1;
            }
            int[] masks = drawable >= 0 && drawable < drawableMasksCache.length ? drawableMasksCache[drawable] : null;
            return masks == null || maskIndex >= masks.length ? -1 : masks[maskIndex];
        }

        private boolean invertedMask(int drawable) {
            return (constantFlags(drawable) & FLAG_IS_INVERTED_MASK) != 0;
        }

        private int vertexCount(int drawable) {
            return drawable >= 0 && drawable < drawableVertexCountsCache.length ? drawableVertexCountsCache[drawable] : 0;
        }

        private float[] vertexPositions(int drawable) {
            return drawable >= 0 && drawable < drawableVertexPositionsCache.length
                    ? drawableVertexPositionsCache[drawable]
                    : new float[0];
        }

        private float[] vertexUvs(int drawable) {
            return drawable >= 0 && drawable < drawableVertexUvsCache.length ? drawableVertexUvsCache[drawable] : new float[0];
        }

        private int indexCount(int drawable) {
            return drawable >= 0 && drawable < drawableIndexCountsCache.length ? drawableIndexCountsCache[drawable] : 0;
        }

        private int[] indices(int drawable) {
            return drawable >= 0 && drawable < drawableIndicesCache.length ? drawableIndicesCache[drawable] : new int[0];
        }

        private boolean canvasValid() {
            return canvasWidth > 0.0F && canvasHeight > 0.0F && pixelsPerUnit > 0.0F;
        }

        private float canvasWidth() {
            return Math.max(1.0F, canvasWidth);
        }

        private float canvasHeight() {
            return Math.max(1.0F, canvasHeight);
        }

        private void release() {
            for (Live2DTexture texture : textures) {
                texture.release();
            }
            textures.clear();
            modelMemory.keepAlive();
            mocMemory.keepAlive();
            Pointer.nativeValue(modelPointer);
            Pointer.nativeValue(mocPointer);
            core.toString();
        }

        private static Pointer pointerAt(Pointer array, int index) {
            if (array == null) {
                return null;
            }
            Pointer pointer = array.getPointer((long) index * POINTER_SIZE);
            return pointer == null || Pointer.nativeValue(pointer) == 0L ? null : pointer;
        }

        private static float[] readFloatArray(Pointer pointer, int count, float fallback) {
            int length = Math.max(0, count);
            float[] values = new float[length];
            if (fallback != 0.0F) {
                for (int i = 0; i < length; i++) {
                    values[i] = fallback;
                }
            }
            readFloats(pointer, values);
            return values;
        }

        private static void readFloats(Pointer pointer, float[] values) {
            if (pointer != null && values.length > 0) {
                pointer.read(0, values, 0, values.length);
            }
        }

        private static void readBytes(Pointer pointer, byte[] values) {
            if (pointer != null && values.length > 0) {
                pointer.read(0, values, 0, values.length);
            }
        }

        private static void readInts(Pointer pointer, int[] values) {
            if (pointer != null && values.length > 0) {
                pointer.read(0, values, 0, values.length);
            }
        }

        private static int[] readUnsignedByteArray(Pointer pointer, int count) {
            int length = Math.max(0, count);
            byte[] bytes = new byte[length];
            readBytes(pointer, bytes);
            int[] values = new int[length];
            for (int i = 0; i < length; i++) {
                values[i] = bytes[i] & 0xFF;
            }
            return values;
        }

        private static int[] readIntArray(Pointer pointer, int count, int fallback) {
            int length = Math.max(0, count);
            int[] values = new int[length];
            if (fallback != 0) {
                for (int i = 0; i < length; i++) {
                    values[i] = fallback;
                }
            }
            if (pointer != null && length > 0) {
                pointer.read(0, values, 0, length);
            }
            return values;
        }

        private static int[] readRenderOrders(Pointer pointer, int count) {
            int length = Math.max(0, count);
            int[] values = new int[length];
            if (pointer == null) {
                for (int i = 0; i < length; i++) {
                    values[i] = i;
                }
                return values;
            }
            pointer.read(0, values, 0, length);
            return values;
        }

        private static int[] readNonNegativeIntArray(Pointer pointer, int count) {
            int[] values = readIntArray(pointer, count, 0);
            for (int i = 0; i < values.length; i++) {
                values[i] = Math.max(0, values[i]);
            }
            return values;
        }

        private static int[][] readMasks(Pointer drawableMasks, int[] maskCounts) {
            int[][] masks = new int[maskCounts.length][];
            for (int drawable = 0; drawable < maskCounts.length; drawable++) {
                int count = maskCounts[drawable];
                if (count <= 0) {
                    masks[drawable] = new int[0];
                    continue;
                }
                int[] values = new int[count];
                Pointer pointer = pointerAt(drawableMasks, drawable);
                if (pointer != null) {
                    pointer.read(0, values, 0, count);
                }
                masks[drawable] = values;
            }
            return masks;
        }

        private static float[][] readDrawableFloatArrays(Pointer pointers, int[] counts, int components) {
            float[][] values = new float[counts.length][];
            int stride = Math.max(1, components);
            for (int drawable = 0; drawable < values.length; drawable++) {
                Pointer pointer = pointerAt(pointers, drawable);
                int count = Math.max(0, counts[drawable]);
                if (pointer == null || count == 0) {
                    values[drawable] = new float[0];
                    continue;
                }
                float[] drawableValues = new float[count * stride];
                pointer.read(0, drawableValues, 0, drawableValues.length);
                values[drawable] = drawableValues;
            }
            return values;
        }

        private static int[][] readDrawableIndexArrays(Pointer pointers, int[] counts) {
            int[][] indices = new int[counts.length][];
            for (int drawable = 0; drawable < counts.length; drawable++) {
                int count = Math.max(0, counts[drawable]);
                if (count <= 0) {
                    indices[drawable] = new int[0];
                    continue;
                }
                short[] source = new short[count];
                Pointer pointer = pointerAt(pointers, drawable);
                if (pointer == null) {
                    indices[drawable] = new int[0];
                    continue;
                }
                pointer.read(0, source, 0, count);
                int[] values = new int[count];
                for (int i = 0; i < count; i++) {
                    values[i] = source[i] & 0xFFFF;
                }
                indices[drawable] = values;
            }
            return indices;
        }

        private static ModelBounds readBounds(int drawableCount, Pointer drawableVertexCounts,
                                              Pointer drawableVertexPositions) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int drawable = 0; drawable < drawableCount; drawable++) {
                int count = drawableVertexCounts == null ? 0 : drawableVertexCounts.getInt((long) drawable * Integer.BYTES);
                Pointer positions = pointerAt(drawableVertexPositions, drawable);
                if (positions == null) {
                    continue;
                }
                for (int vertex = 0; vertex < count; vertex++) {
                    long offset = (long) vertex * 2L * Float.BYTES;
                    float x = positions.getFloat(offset);
                    float y = positions.getFloat(offset + Float.BYTES);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
            if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(maxX) || !Float.isFinite(maxY)) {
                return new ModelBounds(-1.0F, -1.0F, 1.0F, 1.0F);
            }
            return new ModelBounds(minX, minY, maxX, maxY);
        }

        private static boolean hasMasks(int drawableCount, Pointer drawableMaskCounts, Pointer drawableMasks) {
            if (drawableMaskCounts == null || drawableMasks == null) {
                return false;
            }
            for (int i = 0; i < drawableCount; i++) {
                if (drawableMaskCounts.getInt((long) i * Integer.BYTES) > 0 && pointerAt(drawableMasks, i) != null) {
                    return true;
                }
            }
            return false;
        }

        private static float positive(float value, float fallback) {
            return value > 0.0F && Float.isFinite(value) ? value : fallback;
        }

        private static Pointer renderOrders(Core core, Pointer modelPointer) {
            Pointer pointer = optionalPointer(() -> core.csmGetDrawableRenderOrders(modelPointer));
            if (pointer != null) {
                return pointer;
            }
            pointer = optionalPointer(() -> core.csmGetRenderOrders(modelPointer));
            if (pointer != null) {
                return pointer;
            }
            return optionalPointer(() -> core.csmGetDrawableDrawOrders(modelPointer));
        }

        private static Pointer drawableMasks(Core core, Pointer modelPointer) {
            return optionalPointer(() -> core.csmGetDrawableMasks(modelPointer));
        }

        private static Live2DPhysics loadPhysics(Live2DModel model) {
            if (model == null || model.physics() == null) {
                return null;
            }
            try {
                return Live2DPhysics.parse(model.physics());
            } catch (Exception ignored) {
                return null;
            }
        }

        private static Pointer optionalPointer(NativePointerCall call) {
            try {
                Pointer pointer = call.get();
                return pointer == null || Pointer.nativeValue(pointer) == 0L ? null : pointer;
            } catch (UnsatisfiedLinkError ignored) {
                return null;
            }
        }

        @FunctionalInterface
        private interface NativePointerCall {
            Pointer get();
        }

        private static float positiveModulo(float value, float modulo) {
            if (modulo <= 0.0F) {
                return 0.0F;
            }
            float result = value % modulo;
            return result < 0.0F ? result + modulo : result;
        }

        private static float smooth(float value) {
            float clamped = clamp(value, 0.0F, 1.0F);
            return clamped * clamped * (3.0F - 2.0F * clamped);
        }
    }

    private static class AlignedMemory {
        private final Memory memory;
        private final Pointer pointer;

        private AlignedMemory(long size, int alignment) {
            memory = new Memory(size + alignment);
            long base = Pointer.nativeValue(memory);
            long aligned = (base + alignment - 1L) & -alignment;
            pointer = new Pointer(aligned);
        }

        private void keepAlive() {
            memory.size();
        }
    }

    public record Bounds(float x, float y, float width, float height) {
        public static final Bounds EMPTY = new Bounds(0.0F, 0.0F, 0.0F, 0.0F);

        public boolean isEmpty() {
            return width <= 0.0F || height <= 0.0F;
        }

        public boolean contains(double px, double py) {
            return !isEmpty() && px >= x && px <= x + width && py >= y && py <= y + height;
        }

        public boolean contains(float px, float py) {
            return contains((double) px, (double) py);
        }
    }

    private record ModelBounds(float minX, float minY, float maxX, float maxY) {
        private float width() {
            return Math.max(1.0F, (maxX - minX) * 256.0F);
        }

        private float height() {
            return Math.max(1.0F, (maxY - minY) * 256.0F);
        }
    }

    private record MotionContext(float horizontalSpeed, boolean airborne, float lipSync) {
    }

    public record PreparedFrame(float width, float height, float yOffset, RenderSnapshot snapshot) {
        private static final PreparedFrame EMPTY = new PreparedFrame(0.0F, 0.0F, 0.0F, null);

        public boolean isEmpty() {
            return width <= 0.0F || height <= 0.0F;
        }
    }

    public record RenderSnapshot(
            long layoutId,
            int[] textureIds,
            int[] drawableOrder,
            byte[] dynamicFlags,
            int[] constantFlags,
            int[] textureIndices,
            float[] opacities,
            int[] maskCounts,
            int[][] masks,
            int[] vertexCounts,
            int[] indexCounts,
            float[][] vertexPositions,
            float[][] vertexUvs,
            int[][] indices,
            float canvasWidth,
            float canvasHeight,
            float originX,
            float originY,
            float pixelsPerUnit,
            float minX,
            float minY,
            float maxX,
            float maxY,
            boolean hasClippingMasks,
            boolean mirror
    ) {
        private static RenderSnapshot from(CubismModel model, boolean mirror) {
            return new RenderSnapshot(
                    model.layoutId,
                    model.textureGlIds,
                    model.drawableOrder,
                    model.drawableDynamicFlagsCache,
                    model.drawableConstantFlagsCache,
                    model.drawableTextureIndicesCache,
                    model.drawableOpacitiesCache,
                    model.drawableMaskCountsCache,
                    model.drawableMasksCache,
                    model.drawableVertexCountsCache,
                    model.drawableIndexCountsCache,
                    model.drawableVertexPositionsCache,
                    model.drawableVertexUvsCache,
                    model.drawableIndicesCache,
                    model.canvasWidth,
                    model.canvasHeight,
                    model.originX,
                    model.originY,
                    model.pixelsPerUnit,
                    model.minX,
                    model.minY,
                    model.maxX,
                    model.maxY,
                    model.hasClippingMasks,
                    mirror
            );
        }
    }

    private record TransientMotionState(String name, float startedAt) {
        private static final TransientMotionState EMPTY =
                new TransientMotionState("", Float.NEGATIVE_INFINITY);
    }
}
