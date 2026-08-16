package yincmewy.sisi.live2dplayer.live2d;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

public class Live2DRenderer {
    private final Live2DCubismBackend cubismBackend = new Live2DCubismBackend();
    private final Live2DOffscreenRenderer offscreenRenderer = new Live2DOffscreenRenderer();
    private String loadedModelKey = "";
    private Live2DTexture iconTexture;
    private String loadError = "";
    private String loadStatus = "";

    public void renderHud(GuiGraphics graphics, Live2DModel model, Live2DConfig config,
                          float rotationX, float rotationY, float verticalMotion,
                          float horizontalSpeed, boolean airborne, float lipSync, float partialTick) {
        if (model == null || !model.valid() || config.alpha <= 0.0F) {
            return;
        }

        ensureLoaded(model);
        if (!cubismBackend.renderHud(graphics, model, config, rotationX, rotationY, verticalMotion,
                horizontalSpeed, airborne, lipSync, partialTick)) {
            if (config.showStatus) {
                drawHudStatus(graphics, loadStatus(), config);
            }
        } else if (config.showStatus) {
            drawHudStatus(graphics, loadStatus(), config);
        }
    }

    public boolean prepareHud(Live2DModel model, Live2DConfig config,
                              float rotationX, float rotationY, float verticalMotion,
                              float horizontalSpeed, boolean airborne, float lipSync,
                              float partialTick) {
        boolean prepared = cubismBackend.prepareHud(model, config, rotationX, rotationY,
                verticalMotion, horizontalSpeed, airborne, lipSync, partialTick,
                offscreenRenderer::render);
        return prepared;
    }

    public boolean prepareHudWithMouse(Live2DModel model, Live2DConfig config,
                                       float mouseX, float mouseY, float verticalMotion,
                                       float lipSync, float partialTick) {
        boolean prepared = cubismBackend.prepareHudWithMouse(model, config, mouseX, mouseY,
                verticalMotion, lipSync, partialTick, offscreenRenderer::render);
        return prepared;
    }

    public boolean drawHud(GuiGraphics graphics, Live2DModel model, Live2DConfig config) {
        if (model == null || !model.valid() || config.alpha <= 0.0F) {
            return false;
        }
        offscreenRenderer.setGuiPixelScale((float) Minecraft.getInstance().getWindow().getGuiScale());
        ensureLoaded(model);
        Live2DOffscreenRenderer.DrawResult offscreen = offscreenRenderer.draw(
                graphics, config.x, config.y, config.scale, config.alpha);
        boolean drawn = offscreen.drawn();
        if (drawn) {
            cubismBackend.setLastBounds(offscreen.bounds());
        } else {
            drawn = cubismBackend.drawPrepared(graphics, model, config);
        }
        if (drawn && config.editMode && Minecraft.getInstance().screen != null) {
            drawEditorOverlay(graphics, cubismBackend.lastBounds());
        }
        return drawn;
    }

    public void renderHudWithMouse(GuiGraphics graphics, Live2DModel model, Live2DConfig config,
                                   float mouseX, float mouseY, float width, float height,
                                   float verticalMotion, float lipSync, float partialTick) {
        if (model == null || !model.valid() || config.alpha <= 0.0F) {
            return;
        }

        ensureLoaded(model);
        cubismBackend.renderHudWithMouse(graphics, model, config, mouseX, mouseY,
                width, height, verticalMotion, 0.0F, false, lipSync, partialTick);
    }

    public void renderPreview(GuiGraphics graphics, Live2DModel model, Live2DConfig config,
                              float x, float y, float width, float height,
                              double mouseX, double mouseY, float partialTick) {
        if (model == null) {
            drawCenteredText(graphics, "No model selected", x, y, width, height);
            return;
        }

        ensureLoaded(model);
        if (!model.valid()) {
            drawCenteredText(graphics, model.error(), x, y, width, height);
            return;
        }

        if (cubismBackend.renderPreview(graphics, model, config, x, y, width, height,
                mouseX, mouseY, partialTick)) {
            return;
        }

        if (iconTexture == null || !iconTexture.valid()) {
            drawCenteredText(graphics, cubismBackend.status(), x, y, width, height);
            return;
        }

        float fitScale = Math.min(width / Math.max(1.0F, iconTexture.width()),
                height / Math.max(1.0F, iconTexture.height())) * 0.86F;
        float renderW = iconTexture.width() * fitScale;
        float renderH = iconTexture.height() * fitScale;
        drawTexture(graphics, iconTexture, x + (width - renderW) * 0.5F,
                y + (height - renderH) * 0.5F, renderW, renderH, config.alpha);
    }

    public String loadError() {
        return loadError;
    }

    public String loadStatus() {
        String backendStatus = cubismBackend.status();
        String status = loadStatus;
        if (status.isBlank()) {
            status = loadError;
        }
        if (backendStatus != null && !backendStatus.isBlank()) {
            status = status.isBlank() ? backendStatus : status + " / " + backendStatus;
        }
        if (!loadError.isBlank() && (status == null || !status.contains(loadError))) {
            status = status == null || status.isBlank() ? loadError : status + " / " + loadError;
        }
        return status;
    }

    public Live2DCubismBackend.Bounds lastBounds() {
        return cubismBackend.lastBounds();
    }

    public boolean prepareCore() {
        return cubismBackend.prepareCore();
    }

    public void clearBounds() {
        cubismBackend.clearBounds();
    }

    public void playMotion(String name, boolean loop) {
        cubismBackend.playMotion(name, loop);
    }

    public void clear() {
        cubismBackend.clear();
        offscreenRenderer.discardFrames();
        releaseLoadedTextures();
        loadedModelKey = "";
        loadError = "";
        loadStatus = "";
    }

    private void ensureLoaded(Live2DModel model) {
        offscreenRenderer.initialize(Minecraft.getInstance().getWindow().getWindow());
        String key = model.modelJson() == null
                ? model.name()
                : model.modelJson().toAbsolutePath().normalize().toString();
        if (key.equals(loadedModelKey)) {
            return;
        }

        offscreenRenderer.discardFrames();
        releaseLoadedTextures();
        loadedModelKey = key;
        loadError = "";
        loadStatus = model.valid() ? "Model package parsed" : model.error();

        if (model.icon() != null) {
            iconTexture = Live2DTexture.load(model.icon(), model.name() + "/icon");
            if (!iconTexture.valid()) {
                loadError = iconTexture.error();
            }
        }
        if (model.valid()) {
            if (!cubismBackend.prepareModelForRender(model)) {
                loadError = cubismBackend.status();
            }
        }
    }

    private void releaseLoadedTextures() {
        if (iconTexture != null) {
            iconTexture.release();
            iconTexture = null;
        }
    }

    private void drawCenteredText(GuiGraphics graphics, String text,
                                  float x, float y, float width, float height) {
        String safe = text == null || text.isBlank() ? "No preview" : text;
        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(safe);
        graphics.drawString(font, safe,
                Math.round(x + (width - textWidth) * 0.5F),
                Math.round(y + height * 0.5F - font.lineHeight * 0.5F),
                0xFFAAAEBA);
    }

    private void drawHudStatus(GuiGraphics graphics, String text, Live2DConfig config) {
        String safe = text == null || text.isBlank() ? "Live2D not rendered" : clip(text, 260);
        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(safe);
        int alpha = Math.round(clamp(config.alpha, 0.0F, 1.0F) * 255.0F);
        if (alpha <= 0) {
            return;
        }
        int x = Math.round(config.x);
        int y = Math.round(config.y);
        graphics.fill(x, y, x + textWidth + 12, y + 18, (alpha * 180 / 255) << 24 | 0x00080C);
        graphics.drawString(font, safe, x + 6, y + 6, (alpha << 24) | 0x00FF9696);
    }

    private String clip(String text, int maxWidth) {
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        String result = text;
        while (!result.isEmpty() && font.width(result + suffix) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    private static void drawTexture(GuiGraphics graphics, Live2DTexture texture,
                                    float x, float y, float width, float height, float alpha) {
        if (texture == null || !texture.valid() || alpha <= 0.0F
                || width <= 0.0F || height <= 0.0F) {
            return;
        }

        Matrix4f matrix = graphics.pose().last().pose();
        float clampedAlpha = clamp(alpha, 0.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.location());

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertex(buffer, matrix, x, y + height, 0.0F, 1.0F, clampedAlpha);
        vertex(buffer, matrix, x + width, y + height, 1.0F, 1.0F, clampedAlpha);
        vertex(buffer, matrix, x + width, y, 1.0F, 0.0F, clampedAlpha);
        vertex(buffer, matrix, x + width, y, 1.0F, 0.0F, clampedAlpha);
        vertex(buffer, matrix, x, y, 0.0F, 0.0F, clampedAlpha);
        vertex(buffer, matrix, x, y + height, 0.0F, 1.0F, clampedAlpha);
        Tesselator.getInstance().end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void drawEditorOverlay(GuiGraphics graphics,
                                          Live2DCubismBackend.Bounds bounds) {
        if (bounds.isEmpty()) {
            return;
        }
        int x1 = Math.round(bounds.x());
        int y1 = Math.round(bounds.y());
        int x2 = Math.round(bounds.x() + bounds.width());
        int y2 = Math.round(bounds.y() + bounds.height());
        int border = 0x99FFFFFF;
        int handle = 0xFFFFC84A;

        graphics.fill(x1, y1, x2, y1 + 1, border);
        graphics.fill(x1, y2 - 1, x2, y2, border);
        graphics.fill(x1, y1, x1 + 1, y2, border);
        graphics.fill(x2 - 1, y1, x2, y2, border);

        int handleSize = 12;
        graphics.fill(x2 - handleSize, y2 - handleSize, x2, y2, handle);
        graphics.fill(x2 - handleSize, y2 - 1, x2, y2, border);
        graphics.fill(x2 - 1, y2 - handleSize, x2, y2, border);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix,
                               float x, float y, float u, float v, float alpha) {
        buffer.vertex(matrix, x, y, 0.0F).uv(u, v)
                .color(1.0F, 1.0F, 1.0F, alpha).endVertex();
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
