package yincmewy.sisi.live2dplayer.live2d;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import yincmewy.sisi.live2dplayer.Live2dplayer;

@Mod.EventBusSubscriber(modid = Live2dplayer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class Live2DClientEvents {
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.live2dplayer.open_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "key.categories.live2dplayer"
    );
    public static final KeyMapping EDIT_MODE = new KeyMapping(
            "key.live2dplayer.edit_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            "key.categories.live2dplayer"
    );
    private static boolean wasLeftDown;
    private static boolean wasRightDown;

    private Live2DClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Live2DRuntime runtime = Live2DRuntime.INSTANCE;
        runtime.tick();

        if (OPEN_CONFIG.consumeClick()) {
            runtime.openConfig(mc.screen);
        }
        if (EDIT_MODE.consumeClick()) {
            runtime.toggleEditMode();
        }

        long window = mc.getWindow().getWindow();
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (runtime.config().enabled && runtime.isEditorInteractive()) {
            if (leftDown && !wasLeftDown) {
                if (runtime.resizeHandleBounds().contains(runtime.guiMouseX(), runtime.guiMouseY())) {
                    runtime.beginResize(runtime.guiMouseX(), runtime.guiMouseY());
                } else {
                    runtime.beginDrag(runtime.guiMouseX(), runtime.guiMouseY());
                }
            }
            if (leftDown && runtime.isDragging()) {
                runtime.dragTo(runtime.guiMouseX(), runtime.guiMouseY());
            }
            if (!leftDown && runtime.isDragging()) {
                runtime.endDrag();
            }
            if (leftDown && runtime.isResizing()) {
                runtime.resizeTo(runtime.guiMouseX(), runtime.guiMouseY());
            }
            if (!leftDown && runtime.isResizing()) {
                runtime.endResize();
            }
            if (rightDown && !wasRightDown) {
                runtime.cycleExpression(1);
            }
        } else if (runtime.isDragging() || runtime.isResizing()) {
            runtime.cancelEditorInteraction();
        }

        wasLeftDown = leftDown;
        wasRightDown = rightDown;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            Live2DRuntime.INSTANCE.renderHud(event.getGuiGraphics(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Live2DRuntime.INSTANCE.renderHud(event.getGuiGraphics(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onScreenKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        if (EDIT_MODE.matches(event.getKeyCode(), event.getScanCode())) {
            Live2DRuntime.INSTANCE.toggleEditMode();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenMouseButtonPre(ScreenEvent.MouseButtonPressed.Pre event) {
        Live2DRuntime runtime = Live2DRuntime.INSTANCE;
        if (capturesEditorMouse(runtime, event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenMouseDraggedPre(ScreenEvent.MouseDragged.Pre event) {
        Live2DRuntime runtime = Live2DRuntime.INSTANCE;
        if (runtime.isDragging()) {
            runtime.dragTo(event.getMouseX(), event.getMouseY());
            event.setCanceled(true);
        } else if (runtime.isResizing()) {
            runtime.resizeTo(event.getMouseX(), event.getMouseY());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenMouseReleasedPre(ScreenEvent.MouseButtonReleased.Pre event) {
        Live2DRuntime runtime = Live2DRuntime.INSTANCE;
        if (runtime.isDragging() || runtime.isResizing()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenMouseScrollPre(ScreenEvent.MouseScrolled.Pre event) {
        if (scaleAtMouse(event.getMouseX(), event.getMouseY(), event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    private static boolean capturesEditorMouse(Live2DRuntime runtime,
                                               double mouseX, double mouseY, int button) {
        return runtime.config().enabled && runtime.isEditorInteractive()
                && runtime.bounds().contains(mouseX, mouseY)
                && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private static boolean scaleAtMouse(double mouseX, double mouseY, double scrollDelta) {
        Live2DRuntime runtime = Live2DRuntime.INSTANCE;
        if (!runtime.config().enabled || !runtime.isEditorInteractive()
                || !runtime.bounds().contains(mouseX, mouseY)) {
            return false;
        }
        runtime.config().scale = clamp(
                runtime.config().scale + (float) scrollDelta * 0.04F,
                0.03F,
                4.0F
        );
        runtime.saveConfig();
        return true;
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
