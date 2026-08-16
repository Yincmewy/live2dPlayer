package yincmewy.sisi.live2dplayer.live2d;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Live2DOffscreenRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int QUALITY_TEXTURE_LIMIT = 3072;
    private static final float SUPERSAMPLE_SCALE = 1.35F;
    private static final int RESOLUTION_QUANTUM = 64;
    private static final float RESIZE_GROW_THRESHOLD = 1.12F;
    private static final float RESIZE_SHRINK_THRESHOLD = 0.58F;
    private static final long RESIZE_DEBOUNCE_NANOS = 250_000_000L;
    private static final int BUFFER_COUNT = 3;

    private static final int SLOT_FREE = 0;
    private static final int SLOT_RENDERING = 1;
    private static final int SLOT_READY = 2;
    private static final int SLOT_DISPLAYED = 3;

    private static final int FLAG_VISIBLE = 1;
    private static final int FLAG_ADDITIVE = 1;
    private static final int FLAG_MULTIPLICATIVE = 2;
    private static final int FLAG_INVERTED_MASK = 8;
    private static final int FLAG_VERTEX_POSITIONS_CHANGED = 1 << 5;

    private final Object lock = new Object();
    private long offscreenWindow;
    private volatile boolean contextReady;
    private boolean initializationAttempted;
    private boolean glObjectsReady;
    private boolean resetRequested;
    private boolean loggedFirstFrame;
    private boolean loggedFirstComposite;
    private boolean loggedRenderFailure;
    private volatile float guiPixelScale = 1.0F;
    private volatile boolean targetResizeRequested;
    private int requestedTargetWidth;
    private int requestedTargetHeight;
    private int resizeCandidateWidth;
    private int resizeCandidateHeight;
    private long resizeCandidateSince;

    private int program;
    private int vao;
    private int positionVbo;
    private int uvVbo;
    private int ebo;
    private int positionAttribute;
    private int uvAttribute;
    private int textureUniform;
    private int opacityUniform;
    private int modelTransformUniform;
    private int mirrorUniform;

    private long meshLayoutId = -1L;
    private MeshLayout meshLayout;
    private FloatBuffer positionBuffer;
    private boolean positionBufferUploaded;

    private final int[] textures = new int[BUFFER_COUNT];
    private final int[] framebuffers = new int[BUFFER_COUNT];
    private final int[] slotStates = new int[BUFFER_COUNT];
    private final long[] readyFences = new long[BUFFER_COUNT];
    private final long[] useFences = new long[BUFFER_COUNT];
    private final long[] slotGenerations = new long[BUFFER_COUNT];
    private final float[] slotCanvasWidths = new float[BUFFER_COUNT];
    private final float[] slotCanvasHeights = new float[BUFFER_COUNT];
    private final float[] slotYOffsetRatios = new float[BUFFER_COUNT];
    private int targetWidth;
    private int targetHeight;
    private int maxTargetSize = 2048;
    private int msaaSamples = 1;
    private int msaaFramebuffer;
    private int msaaColorBuffer;
    private int msaaDepthStencilBuffer;
    private int displayedIndex = -1;
    private long generation;

    public boolean initialize(long shareWindow) {
        if (contextReady) {
            return true;
        }
        if (shareWindow == 0L || initializationAttempted) {
            return false;
        }
        synchronized (lock) {
            if (contextReady) {
                return true;
            }
            if (initializationAttempted) {
                return false;
            }
            initializationAttempted = true;
            try {
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                offscreenWindow = GLFW.glfwCreateWindow(1, 1, "Live2D Worker", 0L, shareWindow);
                if (offscreenWindow == 0L) {
                    LOGGER.warn("Unable to create the shared Live2D OpenGL context; using direct rendering");
                    return false;
                }
                contextReady = true;
                LOGGER.info("Live2D shared OpenGL worker context created");
                return true;
            } catch (Throwable throwable) {
                LOGGER.warn("Unable to create the shared Live2D OpenGL context; using direct rendering", throwable);
                return false;
            }
        }
    }

    public void setGuiPixelScale(float scale) {
        if (Float.isFinite(scale)) {
            guiPixelScale = Math.max(1.0F, scale);
        }
    }

    public void render(Live2DCubismBackend.PreparedFrame frame) {
        if (!contextReady || frame == null || frame.snapshot() == null) {
            return;
        }
        try {
            GLFW.glfwMakeContextCurrent(offscreenWindow);
            if (!glObjectsReady) {
                initializeGl();
            }
            if (!glObjectsReady) {
                return;
            }
            applyPendingReset();
            applyPendingTargetResize();

            Live2DCubismBackend.RenderSnapshot snapshot = frame.snapshot();
            if (snapshot.layoutId() != meshLayoutId) {
                if (!allSlotsReusable()) {
                    return;
                }
                releaseMeshObjects();
                releaseFrameTargets();
                initializeMesh(snapshot);
                int[] resolution = resolutionFor(frame.width(), frame.height());
                initializeFrameTargets(resolution[0], resolution[1]);
            } else if (targetWidth == 0 || targetHeight == 0) {
                int[] resolution = resolutionFor(frame.width(), frame.height());
                initializeFrameTargets(resolution[0], resolution[1]);
            }

            int writeIndex = acquireWriteSlot();
            if (writeIndex < 0) {
                return;
            }

            boolean published = false;
            try {
                updatePositions(snapshot);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, msaaFramebuffer);
                GL11C.glViewport(0, 0, targetWidth, targetHeight);
                GL11C.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                GL11C.glClearStencil(0);
                GL11C.glStencilMask(0xFF);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_STENCIL_BUFFER_BIT);
                drawSnapshot(snapshot);

                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, msaaFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffers[writeIndex]);
                GL30.glBlitFramebuffer(0, 0, targetWidth, targetHeight,
                        0, 0, targetWidth, targetHeight,
                        GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

                long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                GL11C.glFlush();
                synchronized (lock) {
                    readyFences[writeIndex] = fence;
                    slotCanvasWidths[writeIndex] = snapshot.canvasWidth();
                    slotCanvasHeights[writeIndex] = snapshot.canvasHeight();
                    slotYOffsetRatios[writeIndex] = frame.height() <= 0.0F
                            ? 0.0F
                            : frame.yOffset() / frame.height();
                    slotGenerations[writeIndex] = ++generation;
                    slotStates[writeIndex] = SLOT_READY;
                    published = true;
                }
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true;
                    LOGGER.info("Live2D GPU frame cache ready at {}x{} with {}x MSAA",
                            targetWidth, targetHeight, msaaSamples);
                }
            } finally {
                if (!published) {
                    synchronized (lock) {
                        slotStates[writeIndex] = SLOT_FREE;
                    }
                }
            }
        } catch (Throwable throwable) {
            synchronized (lock) {
                resetRequested = true;
                Arrays.fill(slotStates, SLOT_FREE);
                displayedIndex = -1;
            }
            if (!loggedRenderFailure) {
                loggedRenderFailure = true;
                LOGGER.warn("Live2D worker rendering failed; the direct compatibility path remains active", throwable);
            }
        }
    }

    public DrawResult draw(GuiGraphics graphics, float x, float y, float scale, float alpha) {
        if (!contextReady || targetResizeRequested || alpha <= 0.0F) {
            return DrawResult.NOT_DRAWN;
        }

        int texture;
        int slot;
        float canvasWidth;
        float canvasHeight;
        float yOffsetRatio;
        synchronized (lock) {
            promoteNewestReadyFrame();
            slot = displayedIndex;
            if (slot < 0 || textures[slot] == 0) {
                return DrawResult.NOT_DRAWN;
            }
            texture = textures[slot];
            canvasWidth = slotCanvasWidths[slot];
            canvasHeight = slotCanvasHeights[slot];
            yOffsetRatio = slotYOffsetRatios[slot];
        }

        float width = Math.max(1.0F, canvasWidth * Math.max(0.01F, scale));
        float height = Math.max(1.0F, canvasHeight * Math.max(0.01F, scale));
        int[] desiredResolution = resolutionFor(width, height);
        if (needsResize(desiredResolution[0], desiredResolution[1])) {
            if (resizeCandidateReady(desiredResolution[0], desiredResolution[1])) {
                requestTargetResize(desiredResolution[0], desiredResolution[1]);
                return DrawResult.NOT_DRAWN;
            }
        } else {
            clearResizeCandidate();
        }
        float renderY = y + yOffsetRatio * height;
        drawTexture(graphics, texture, x, renderY, width, height, alpha);
        if (!loggedFirstComposite) {
            loggedFirstComposite = true;
            LOGGER.info("Live2D cached frame composited by the Minecraft render thread");
        }
        return new DrawResult(true, new Live2DCubismBackend.Bounds(x, renderY, width, height));
    }

    public void discardFrames() {
        if (!contextReady) {
            return;
        }
        try {
            GL11C.glFinish();
        } catch (Throwable ignored) {
        }
        synchronized (lock) {
            displayedIndex = -1;
            Arrays.fill(slotStates, SLOT_FREE);
            resetRequested = true;
        }
    }

    private void initializeGl() {
        GL.createCapabilities();
        GLFW.glfwSwapInterval(0);
        maxTargetSize = Math.max(256, Math.min(QUALITY_TEXTURE_LIMIT,
                GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE)));
        msaaSamples = Math.max(1, Math.min(4, GL11C.glGetInteger(GL30.GL_MAX_SAMPLES)));
        program = createProgram();
        vao = GL30.glGenVertexArrays();
        positionVbo = GL15.glGenBuffers();
        uvVbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        positionAttribute = GL20.glGetAttribLocation(program, "a_position");
        uvAttribute = GL20.glGetAttribLocation(program, "a_uv");
        textureUniform = GL20.glGetUniformLocation(program, "u_texture");
        opacityUniform = GL20.glGetUniformLocation(program, "u_opacity");
        modelTransformUniform = GL20.glGetUniformLocation(program, "u_model_transform");
        mirrorUniform = GL20.glGetUniformLocation(program, "u_mirror");
        glObjectsReady = program != 0 && vao != 0 && positionVbo != 0 && uvVbo != 0
                && ebo != 0 && positionAttribute >= 0 && uvAttribute >= 0;
    }

    private int createProgram() {
        int vertex = compileShader(GL20.GL_VERTEX_SHADER, """
                #version 150
                in vec2 a_position;
                in vec2 a_uv;
                out vec2 v_uv;
                uniform vec4 u_model_transform;
                uniform bool u_mirror;
                void main() {
                    vec2 normalized = a_position * u_model_transform.xy + u_model_transform.zw;
                    if (u_mirror) normalized.x = 1.0 - normalized.x;
                    gl_Position = vec4(normalized * 2.0 - 1.0, 0.0, 1.0);
                    v_uv = a_uv;
                }
                """);
        if (vertex == 0) {
            return 0;
        }
        int fragment = compileShader(GL20.GL_FRAGMENT_SHADER, """
                #version 150
                uniform sampler2D u_texture;
                uniform float u_opacity;
                in vec2 v_uv;
                out vec4 fragColor;
                void main() {
                    vec4 color = texture(u_texture, v_uv);
                    color.a *= u_opacity;
                    if (color.a <= 0.001) discard;
                    fragColor = color;
                }
                """);
        if (fragment == 0) {
            GL20.glDeleteShader(vertex);
            return 0;
        }

        int result = GL20.glCreateProgram();
        GL20.glAttachShader(result, vertex);
        GL20.glAttachShader(result, fragment);
        GL20.glLinkProgram(result);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            LOGGER.error("Live2D shader link failed: {}", GL20.glGetProgramInfoLog(result));
            GL20.glDeleteProgram(result);
            return 0;
        }
        return result;
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            LOGGER.error("Live2D shader compile failed: {}", GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void initializeMesh(Live2DCubismBackend.RenderSnapshot snapshot) {
        meshLayout = MeshLayout.create(snapshot);
        positionBuffer = MemoryUtil.memAllocFloat(Math.max(1, meshLayout.totalVertices * 2));
        FloatBuffer uvBuffer = MemoryUtil.memAllocFloat(Math.max(1, meshLayout.totalVertices * 2));
        IntBuffer indexBuffer = MemoryUtil.memAllocInt(Math.max(1, meshLayout.totalIndices));
        try {
            for (int drawable = 0; drawable < meshLayout.drawableCount; drawable++) {
                copyPositions(snapshot, drawable);
                float[] uvs = snapshot.vertexUvs()[drawable];
                int vertexBase = meshLayout.vertexOffsets[drawable];
                int vertexCount = meshLayout.vertexCounts[drawable];
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    int source = vertex * 2;
                    int target = (vertexBase + vertex) * 2;
                    uvBuffer.put(target, source < uvs.length ? uvs[source] : 0.0F);
                    uvBuffer.put(target + 1, source + 1 < uvs.length ? 1.0F - uvs[source + 1] : 0.0F);
                }

                int[] indices = snapshot.indices()[drawable];
                int indexBase = meshLayout.indexOffsets[drawable];
                int indexCount = meshLayout.indexCounts[drawable];
                for (int index = 0; index < indexCount; index++) {
                    int localIndex = index < indices.length ? indices[index] : 0;
                    if (localIndex < 0 || localIndex >= vertexCount) {
                        localIndex = 0;
                    }
                    indexBuffer.put(indexBase + index, vertexBase + localIndex);
                }
            }

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVbo);
            positionBuffer.position(0).limit(positionBuffer.capacity());
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, positionBuffer, GL15.GL_DYNAMIC_DRAW);
            GL20.glEnableVertexAttribArray(positionAttribute);
            GL20.glVertexAttribPointer(positionAttribute, 2, GL11C.GL_FLOAT, false, 0, 0L);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, uvVbo);
            uvBuffer.position(0).limit(uvBuffer.capacity());
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvBuffer, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(uvAttribute);
            GL20.glVertexAttribPointer(uvAttribute, 2, GL11C.GL_FLOAT, false, 0, 0L);

            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            indexBuffer.position(0).limit(indexBuffer.capacity());
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
            GL30.glBindVertexArray(0);
            meshLayoutId = snapshot.layoutId();
            positionBufferUploaded = true;
        } finally {
            MemoryUtil.memFree(uvBuffer);
            MemoryUtil.memFree(indexBuffer);
        }
    }

    private void updatePositions(Live2DCubismBackend.RenderSnapshot snapshot) {
        boolean changed = !positionBufferUploaded;
        for (int drawable = 0; drawable < meshLayout.drawableCount; drawable++) {
            byte flags = drawable < snapshot.dynamicFlags().length
                    ? snapshot.dynamicFlags()[drawable]
                    : (byte) FLAG_VERTEX_POSITIONS_CHANGED;
            if (!positionBufferUploaded || (flags & FLAG_VERTEX_POSITIONS_CHANGED) != 0) {
                copyPositions(snapshot, drawable);
                changed = true;
            }
        }
        if (changed) {
            positionBuffer.position(0).limit(positionBuffer.capacity());
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVbo);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, positionBuffer);
            positionBufferUploaded = true;
        }
    }

    private void copyPositions(Live2DCubismBackend.RenderSnapshot snapshot, int drawable) {
        float[] positions = snapshot.vertexPositions()[drawable];
        int targetBase = meshLayout.vertexOffsets[drawable] * 2;
        int floatCount = meshLayout.vertexCounts[drawable] * 2;
        for (int i = 0; i < floatCount; i++) {
            positionBuffer.put(targetBase + i, i < positions.length ? positions[i] : 0.0F);
        }
    }

    private void initializeFrameTargets(int width, int height) {
        targetWidth = width;
        targetHeight = height;
        GL11C.glGenTextures(textures);
        GL30.glGenFramebuffers(framebuffers);
        for (int i = 0; i < BUFFER_COUNT; i++) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8,
                    width, height, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffers[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, textures[i], 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Live2D resolve framebuffer incomplete: 0x"
                        + Integer.toHexString(status));
            }
        }

        msaaFramebuffer = GL30.glGenFramebuffers();
        msaaColorBuffer = GL30.glGenRenderbuffers();
        msaaDepthStencilBuffer = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, msaaColorBuffer);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, msaaSamples,
                GL11C.GL_RGBA8, width, height);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, msaaDepthStencilBuffer);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, msaaSamples,
                GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, msaaFramebuffer);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_RENDERBUFFER, msaaColorBuffer);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                GL30.GL_RENDERBUFFER, msaaDepthStencilBuffer);
        int msaaStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (msaaStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Live2D MSAA framebuffer incomplete: 0x"
                    + Integer.toHexString(msaaStatus));
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        synchronized (lock) {
            Arrays.fill(slotStates, SLOT_FREE);
            displayedIndex = -1;
        }
    }

    private void drawSnapshot(Live2DCubismBackend.RenderSnapshot snapshot) {
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glEnable(GL13.GL_MULTISAMPLE);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        GL20.glUniform1i(textureUniform, 0);
        GL20.glUniform1i(mirrorUniform, snapshot.mirror() ? 1 : 0);
        setModelTransform(snapshot);

        int[] maskReferences = meshLayout.maskReferences;
        Arrays.fill(maskReferences, 0);
        int nextStencilReference = 1;
        int boundTexture = -1;
        int blendFlags = Integer.MIN_VALUE;

        for (int drawable : snapshot.drawableOrder()) {
            if (!visible(snapshot, drawable)) {
                continue;
            }
            int maskGroup = meshLayout.maskGroupByDrawable[drawable];
            if (maskGroup >= 0) {
                int reference = maskReferences[maskGroup];
                if (reference == 0) {
                    if (nextStencilReference > 0xFF) {
                        GL11C.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
                        Arrays.fill(maskReferences, 0);
                        nextStencilReference = 1;
                    }
                    reference = writeMaskGroup(snapshot, maskGroup, nextStencilReference);
                    if (reference > 0) {
                        nextStencilReference++;
                    }
                    maskReferences[maskGroup] = reference;
                    boundTexture = -1;
                    blendFlags = Integer.MIN_VALUE;
                }
                if (reference > 0) {
                    beginClippedDraw((snapshot.constantFlags()[drawable] & FLAG_INVERTED_MASK) != 0,
                            reference);
                    boundTexture = bindTexture(snapshot, drawable, boundTexture);
                    blendFlags = applyBlend(snapshot.constantFlags()[drawable], blendFlags);
                    drawMesh(snapshot, drawable, snapshot.opacities()[drawable]);
                    GL11C.glDisable(GL11C.GL_STENCIL_TEST);
                    continue;
                }
            }

            boundTexture = bindTexture(snapshot, drawable, boundTexture);
            blendFlags = applyBlend(snapshot.constantFlags()[drawable], blendFlags);
            drawMesh(snapshot, drawable, snapshot.opacities()[drawable]);
        }

        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
    }

    private int writeMaskGroup(Live2DCubismBackend.RenderSnapshot snapshot,
                               int group, int reference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilMask(0xFF);
        GL11C.glStencilFunc(GL11C.GL_ALWAYS, reference, 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
        GL11C.glColorMask(false, false, false, false);
        GL11C.glDisable(GL11C.GL_BLEND);

        boolean wrote = false;
        int boundTexture = -1;
        for (int maskDrawable : meshLayout.maskGroups[group]) {
            if (!visible(snapshot, maskDrawable)) {
                continue;
            }
            boundTexture = bindTexture(snapshot, maskDrawable, boundTexture);
            wrote |= drawMesh(snapshot, maskDrawable, 1.0F);
        }

        GL11C.glColorMask(true, true, true, true);
        GL11C.glStencilMask(0x00);
        GL11C.glEnable(GL11C.GL_BLEND);
        if (!wrote) {
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            return -1;
        }
        return reference;
    }

    private void beginClippedDraw(boolean inverted, int reference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glColorMask(true, true, true, true);
        GL11C.glStencilMask(0x00);
        GL11C.glStencilFunc(inverted ? GL11C.GL_NOTEQUAL : GL11C.GL_EQUAL, reference, 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        GL11C.glEnable(GL11C.GL_BLEND);
    }

    private int bindTexture(Live2DCubismBackend.RenderSnapshot snapshot,
                            int drawable, int currentlyBound) {
        int textureIndex = snapshot.textureIndices()[drawable];
        if (textureIndex < 0 || textureIndex >= snapshot.textureIds().length) {
            return currentlyBound;
        }
        int texture = snapshot.textureIds()[textureIndex];
        if (texture > 0 && texture != currentlyBound) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            return texture;
        }
        return currentlyBound;
    }

    private boolean drawMesh(Live2DCubismBackend.RenderSnapshot snapshot,
                             int drawable, float opacity) {
        if (drawable < 0 || drawable >= meshLayout.drawableCount
                || meshLayout.indexCounts[drawable] <= 0) {
            return false;
        }
        int textureIndex = snapshot.textureIndices()[drawable];
        if (textureIndex < 0 || textureIndex >= snapshot.textureIds().length
                || snapshot.textureIds()[textureIndex] <= 0) {
            return false;
        }
        GL20.glUniform1f(opacityUniform, clamp(opacity, 0.0F, 1.0F));
        GL11C.glDrawElements(GL11C.GL_TRIANGLES, meshLayout.indexCounts[drawable],
                GL11C.GL_UNSIGNED_INT, (long) meshLayout.indexOffsets[drawable] * Integer.BYTES);
        return true;
    }

    private int applyBlend(int flags, int currentFlags) {
        int blendFlags = flags & (FLAG_ADDITIVE | FLAG_MULTIPLICATIVE);
        if (blendFlags == currentFlags) {
            return currentFlags;
        }
        if ((blendFlags & FLAG_ADDITIVE) != 0) {
            GL14.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE,
                    GL11C.GL_ONE, GL11C.GL_ONE);
        } else if ((blendFlags & FLAG_MULTIPLICATIVE) != 0) {
            GL14.glBlendFuncSeparate(GL11C.GL_DST_COLOR, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ZERO, GL11C.GL_ONE);
        } else {
            GL14.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        }
        return blendFlags;
    }

    private void setModelTransform(Live2DCubismBackend.RenderSnapshot snapshot) {
        float scaleX;
        float scaleY;
        float offsetX;
        float offsetY;
        if (snapshot.canvasWidth() > 0.0F && snapshot.canvasHeight() > 0.0F
                && snapshot.pixelsPerUnit() > 0.0F) {
            scaleX = snapshot.pixelsPerUnit() / snapshot.canvasWidth();
            scaleY = -snapshot.pixelsPerUnit() / snapshot.canvasHeight();
            offsetX = snapshot.originX() / snapshot.canvasWidth();
            offsetY = snapshot.originY() / snapshot.canvasHeight();
        } else {
            float width = Math.max(0.0001F, snapshot.maxX() - snapshot.minX());
            float height = Math.max(0.0001F, snapshot.maxY() - snapshot.minY());
            scaleX = 1.0F / width;
            scaleY = -1.0F / height;
            offsetX = -snapshot.minX() / width;
            offsetY = snapshot.maxY() / height;
        }
        GL20.glUniform4f(modelTransformUniform, scaleX, scaleY, offsetX, offsetY);
    }

    private int acquireWriteSlot() {
        synchronized (lock) {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (slotStates[i] != SLOT_FREE || !fenceSignaled(useFences[i])) {
                    continue;
                }
                deleteFence(useFences[i]);
                useFences[i] = 0L;
                slotStates[i] = SLOT_RENDERING;
                return i;
            }
            return -1;
        }
    }

    private void promoteNewestReadyFrame() {
        int newest = -1;
        long newestGeneration = -1L;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (slotStates[i] == SLOT_READY && slotGenerations[i] > newestGeneration
                    && fenceSignaled(readyFences[i])) {
                newest = i;
                newestGeneration = slotGenerations[i];
            }
        }
        if (newest < 0) {
            return;
        }

        if (displayedIndex >= 0) {
            deleteFence(useFences[displayedIndex]);
            useFences[displayedIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11C.glFlush();
            slotStates[displayedIndex] = SLOT_FREE;
        }
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (i != newest && slotStates[i] == SLOT_READY
                    && slotGenerations[i] < newestGeneration) {
                deleteFence(readyFences[i]);
                readyFences[i] = 0L;
                slotStates[i] = SLOT_FREE;
            }
        }
        deleteFence(readyFences[newest]);
        readyFences[newest] = 0L;
        slotStates[newest] = SLOT_DISPLAYED;
        displayedIndex = newest;
    }

    private boolean allSlotsReusable() {
        synchronized (lock) {
            if (displayedIndex >= 0) {
                return false;
            }
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (slotStates[i] == SLOT_RENDERING || !fenceSignaled(readyFences[i])
                        || !fenceSignaled(useFences[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean fenceSignaled(long fence) {
        if (fence == 0L) {
            return true;
        }
        int result = GL32.glClientWaitSync(fence, 0, 0L);
        return result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED;
    }

    private void deleteFence(long fence) {
        if (fence != 0L) {
            GL32.glDeleteSync(fence);
        }
    }

    private void applyPendingReset() {
        boolean reset;
        synchronized (lock) {
            reset = resetRequested;
            resetRequested = false;
            if (reset) {
                targetResizeRequested = false;
            }
        }
        if (reset) {
            GL11C.glFinish();
            releaseMeshObjects();
            releaseFrameTargets();
        }
    }

    private void applyPendingTargetResize() {
        int width;
        int height;
        synchronized (lock) {
            if (!targetResizeRequested || resetRequested) {
                return;
            }
            width = requestedTargetWidth;
            height = requestedTargetHeight;
        }

        GL11C.glFinish();
        releaseFrameTargets();
        initializeFrameTargets(width, height);
        synchronized (lock) {
            targetResizeRequested = false;
        }
        LOGGER.info("Live2D GPU frame cache resized to {}x{} with {}x MSAA",
                width, height, msaaSamples);
    }

    private void requestTargetResize(int width, int height) {
        GL11C.glFinish();
        synchronized (lock) {
            if (!targetResizeRequested) {
                requestedTargetWidth = width;
                requestedTargetHeight = height;
                targetResizeRequested = true;
            }
        }
        clearResizeCandidate();
    }

    private boolean resizeCandidateReady(int width, int height) {
        long now = System.nanoTime();
        if (resizeCandidateWidth != width || resizeCandidateHeight != height) {
            resizeCandidateWidth = width;
            resizeCandidateHeight = height;
            resizeCandidateSince = now;
            return false;
        }
        return now - resizeCandidateSince >= RESIZE_DEBOUNCE_NANOS;
    }

    private void clearResizeCandidate() {
        resizeCandidateWidth = 0;
        resizeCandidateHeight = 0;
        resizeCandidateSince = 0L;
    }

    private boolean needsResize(int desiredWidth, int desiredHeight) {
        int currentLongest = Math.max(targetWidth, targetHeight);
        int desiredLongest = Math.max(desiredWidth, desiredHeight);
        return currentLongest <= 0
                || desiredLongest > currentLongest * RESIZE_GROW_THRESHOLD
                || desiredLongest < currentLongest * RESIZE_SHRINK_THRESHOLD;
    }

    private void releaseMeshObjects() {
        if (positionBuffer != null) {
            MemoryUtil.memFree(positionBuffer);
            positionBuffer = null;
        }
        meshLayout = null;
        meshLayoutId = -1L;
        positionBufferUploaded = false;
    }

    private void releaseFrameTargets() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            deleteFence(readyFences[i]);
            deleteFence(useFences[i]);
            readyFences[i] = 0L;
            useFences[i] = 0L;
        }
        if (msaaFramebuffer != 0) {
            GL30.glDeleteFramebuffers(msaaFramebuffer);
            GL30.glDeleteRenderbuffers(msaaColorBuffer);
            GL30.glDeleteRenderbuffers(msaaDepthStencilBuffer);
            msaaFramebuffer = 0;
            msaaColorBuffer = 0;
            msaaDepthStencilBuffer = 0;
        }
        if (framebuffers[0] != 0) {
            GL30.glDeleteFramebuffers(framebuffers);
            GL11C.glDeleteTextures(textures);
            Arrays.fill(framebuffers, 0);
            Arrays.fill(textures, 0);
        }
        synchronized (lock) {
            Arrays.fill(slotStates, SLOT_FREE);
            displayedIndex = -1;
        }
        targetWidth = 0;
        targetHeight = 0;
    }

    private int[] resolutionFor(float logicalWidth, float logicalHeight) {
        float safeWidth = Math.max(1.0F, logicalWidth) * guiPixelScale * SUPERSAMPLE_SCALE;
        float safeHeight = Math.max(1.0F, logicalHeight) * guiPixelScale * SUPERSAMPLE_SCALE;
        float downscale = Math.min(1.0F, maxTargetSize / Math.max(safeWidth, safeHeight));
        return new int[] {
                quantize(Math.round(safeWidth * downscale)),
                quantize(Math.round(safeHeight * downscale))
        };
    }

    private int quantize(int value) {
        int clamped = Math.max(RESOLUTION_QUANTUM, Math.min(maxTargetSize, value));
        return Math.min(maxTargetSize,
                (clamped + RESOLUTION_QUANTUM - 1) & -RESOLUTION_QUANTUM);
    }

    private static boolean visible(Live2DCubismBackend.RenderSnapshot snapshot, int drawable) {
        return drawable >= 0 && drawable < snapshot.dynamicFlags().length
                && (snapshot.dynamicFlags()[drawable] & FLAG_VISIBLE) != 0;
    }

    private static void drawTexture(GuiGraphics graphics, int texture,
                                    float x, float y, float width, float height, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, texture);

        Matrix4f matrix = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertex(buffer, matrix, x, y + height, 0.0F, 1.0F, alpha);
        vertex(buffer, matrix, x + width, y + height, 1.0F, 1.0F, alpha);
        vertex(buffer, matrix, x + width, y, 1.0F, 0.0F, alpha);
        vertex(buffer, matrix, x + width, y, 1.0F, 0.0F, alpha);
        vertex(buffer, matrix, x, y, 0.0F, 0.0F, alpha);
        vertex(buffer, matrix, x, y + height, 0.0F, 1.0F, alpha);
        Tesselator.getInstance().end();

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, float x, float y,
                               float u, float v, float alpha) {
        buffer.vertex(matrix, x, y, 0.0F).uv(u, v)
                .color(1.0F, 1.0F, 1.0F, clamp(alpha, 0.0F, 1.0F)).endVertex();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public void close() {
        if (!contextReady) {
            return;
        }
        GLFW.glfwMakeContextCurrent(offscreenWindow);
        if (glObjectsReady) {
            GL11C.glFinish();
            releaseMeshObjects();
            releaseFrameTargets();
            GL20.glDeleteProgram(program);
            GL15.glDeleteBuffers(positionVbo);
            GL15.glDeleteBuffers(uvVbo);
            GL15.glDeleteBuffers(ebo);
            GL30.glDeleteVertexArrays(vao);
        }
        GLFW.glfwMakeContextCurrent(0L);
        GLFW.glfwDestroyWindow(offscreenWindow);
        offscreenWindow = 0L;
        contextReady = false;
        glObjectsReady = false;
    }

    public record DrawResult(boolean drawn, Live2DCubismBackend.Bounds bounds) {
        private static final DrawResult NOT_DRAWN =
                new DrawResult(false, Live2DCubismBackend.Bounds.EMPTY);
    }

    private static final class MeshLayout {
        private final int drawableCount;
        private final int totalVertices;
        private final int totalIndices;
        private final int[] vertexOffsets;
        private final int[] vertexCounts;
        private final int[] indexOffsets;
        private final int[] indexCounts;
        private final int[] maskGroupByDrawable;
        private final int[][] maskGroups;
        private final int[] maskReferences;

        private MeshLayout(int drawableCount, int totalVertices, int totalIndices,
                           int[] vertexOffsets, int[] vertexCounts,
                           int[] indexOffsets, int[] indexCounts,
                           int[] maskGroupByDrawable, int[][] maskGroups) {
            this.drawableCount = drawableCount;
            this.totalVertices = totalVertices;
            this.totalIndices = totalIndices;
            this.vertexOffsets = vertexOffsets;
            this.vertexCounts = vertexCounts;
            this.indexOffsets = indexOffsets;
            this.indexCounts = indexCounts;
            this.maskGroupByDrawable = maskGroupByDrawable;
            this.maskGroups = maskGroups;
            this.maskReferences = new int[maskGroups.length];
        }

        private static MeshLayout create(Live2DCubismBackend.RenderSnapshot snapshot) {
            int drawableCount = snapshot.vertexCounts().length;
            int[] vertexOffsets = new int[drawableCount];
            int[] vertexCounts = new int[drawableCount];
            int[] indexOffsets = new int[drawableCount];
            int[] indexCounts = new int[drawableCount];
            int totalVertices = 0;
            int totalIndices = 0;
            for (int drawable = 0; drawable < drawableCount; drawable++) {
                vertexOffsets[drawable] = totalVertices;
                indexOffsets[drawable] = totalIndices;
                vertexCounts[drawable] = Math.max(0, snapshot.vertexCounts()[drawable]);
                indexCounts[drawable] = drawable < snapshot.indexCounts().length
                        ? Math.max(0, snapshot.indexCounts()[drawable])
                        : 0;
                totalVertices = Math.addExact(totalVertices, vertexCounts[drawable]);
                totalIndices = Math.addExact(totalIndices, indexCounts[drawable]);
            }

            int[] maskGroupByDrawable = new int[drawableCount];
            Arrays.fill(maskGroupByDrawable, -1);
            List<int[]> groups = new ArrayList<>();
            for (int drawable = 0; drawable < drawableCount; drawable++) {
                int maskCount = drawable < snapshot.maskCounts().length
                        ? snapshot.maskCounts()[drawable]
                        : 0;
                if (maskCount <= 0 || drawable >= snapshot.masks().length) {
                    continue;
                }
                int[] masks = Arrays.copyOf(snapshot.masks()[drawable],
                        Math.min(maskCount, snapshot.masks()[drawable].length));
                int group = -1;
                for (int i = 0; i < groups.size(); i++) {
                    if (Arrays.equals(groups.get(i), masks)) {
                        group = i;
                        break;
                    }
                }
                if (group < 0) {
                    group = groups.size();
                    groups.add(masks);
                }
                maskGroupByDrawable[drawable] = group;
            }
            return new MeshLayout(drawableCount, totalVertices, totalIndices,
                    vertexOffsets, vertexCounts, indexOffsets, indexCounts,
                    maskGroupByDrawable, groups.toArray(int[][]::new));
        }
    }
}
