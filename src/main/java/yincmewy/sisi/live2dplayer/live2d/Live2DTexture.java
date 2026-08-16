package yincmewy.sisi.live2dplayer.live2d;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import yincmewy.sisi.live2dplayer.Live2dplayer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

public class Live2DTexture {
    private static final String CACHE_VERSION = "texture-bleed-v1";
    private static final String LOCATION_PREFIX = "live2d_external/";

    private final Path path;
    private final ResourceLocation location;
    private final AbstractTexture texture;
    private final int width;
    private final int height;
    private final String error;
    private boolean released;

    private Live2DTexture(Path path, ResourceLocation location, AbstractTexture texture, int width, int height, String error) {
        this.path = path;
        this.location = location;
        this.texture = texture;
        this.width = width;
        this.height = height;
        this.error = error == null ? "" : error;
    }

    public static Live2DTexture load(Path path, String key) {
        NativeImage image = null;
        Live2DUploadedTexture texture = null;
        try {
            image = readImage(path);
            int width = image.getWidth();
            int height = image.getHeight();
            ResourceLocation location = textureLocation(path, key);
            texture = new Live2DUploadedTexture(image, true, false);
            image = null;
            Minecraft.getInstance().getTextureManager().register(location, texture);
            return new Live2DTexture(path, location, texture, width, height, "");
        } catch (Exception exception) {
            if (texture != null) {
                texture.close();
            }
            if (image != null) {
                image.close();
            }
            return new Live2DTexture(path, null, null, 1, 1, exception.getMessage());
        }
    }

    private static ResourceLocation textureLocation(Path path, String key) throws Exception {
        String normalizedPath = path.toAbsolutePath().normalize().toString();
        String fingerprint = sha256(normalizedPath).substring(0, 16);
        return ResourceLocation.fromNamespaceAndPath(
                Live2dplayer.MODID,
                LOCATION_PREFIX + sanitize(key) + "/" + fingerprint
        );
    }

    private static NativeImage readImage(Path path) throws Exception {
        Path cache = cachePath(path);
        if (cache != null && Files.exists(cache)) {
            try (InputStream stream = Files.newInputStream(cache)) {
                return NativeImage.read(stream);
            } catch (Exception ignored) {
                deleteQuietly(cache);
            }
        }

        try (InputStream stream = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(stream);
            bleedTransparentPixels(image);
            writeCache(image, cache);
            return image;
        }
    }

    private static void writeCache(NativeImage image, Path target) {
        if (target == null) {
            return;
        }

        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            image.writeToFile(temporary.toFile());
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static Path cachePath(Path source) {
        try {
            Path normalized = source.toAbsolutePath().normalize();
            String fingerprint = CACHE_VERSION + "\n"
                    + normalized + "\n"
                    + Files.size(source) + "\n"
                    + Files.getLastModifiedTime(source).toMillis();
            return Live2DConfig.CACHE_DIR.resolve("textures").resolve(sha256(fingerprint) + ".png");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        char[] result = new char[digest.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xFF;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(result);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    public Path path() {
        return path;
    }

    public ResourceLocation location() {
        return location;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String error() {
        return error;
    }

    public boolean valid() {
        return location != null && error.isEmpty();
    }

    public int glId() {
        return texture == null ? -1 : texture.getId();
    }

    public void release() {
        if (released || location == null || texture == null) {
            return;
        }
        released = true;
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        textureManager.release(location);
        texture.close();
    }

    private static String sanitize(String text) {
        String safe = text == null ? "model" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        return safe.isBlank() ? "model" : safe;
    }

    private static void bleedTransparentPixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = image.getPixelRGBA(x, y);
            }
        }

        for (int pass = 0; pass < 4; pass++) {
            int[] next = pixels.clone();
            boolean changed = false;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    int pixel = pixels[index];
                    if (alpha(pixel) != 0) {
                        continue;
                    }

                    int neighbor = firstOpaqueNeighbor(pixels, width, height, x, y);
                    if (neighbor == 0) {
                        continue;
                    }
                    next[index] = (pixel & 0xFF000000) | (neighbor & 0x00FFFFFF);
                    changed = true;
                }
            }
            pixels = next;
            if (!changed) {
                break;
            }
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (alpha(image.getPixelRGBA(x, y)) == 0) {
                    image.setPixelRGBA(x, y, pixels[index]);
                }
            }
        }
    }

    private static int firstOpaqueNeighbor(int[] pixels, int width, int height, int x, int y) {
        int left = x > 0 ? pixels[y * width + x - 1] : 0;
        if (alpha(left) != 0) {
            return left;
        }
        int right = x + 1 < width ? pixels[y * width + x + 1] : 0;
        if (alpha(right) != 0) {
            return right;
        }
        int up = y > 0 ? pixels[(y - 1) * width + x] : 0;
        if (alpha(up) != 0) {
            return up;
        }
        int down = y + 1 < height ? pixels[(y + 1) * width + x] : 0;
        return alpha(down) != 0 ? down : 0;
    }

    private static int alpha(int pixel) {
        return (pixel >>> 24) & 0xFF;
    }

    private static class Live2DUploadedTexture extends AbstractTexture {
        private NativeImage image;
        private final boolean blur;
        private final boolean mipmap;

        private Live2DUploadedTexture(NativeImage image, boolean blur, boolean mipmap) {
            this.image = image;
            this.blur = blur;
            this.mipmap = mipmap;
        }

        @Override
        public void load(ResourceManager resourceManager) {
            NativeImage uploadImage = image;
            if (uploadImage == null) {
                return;
            }
            image = null;
            uploadAndClose(uploadImage);
        }

        private void uploadAndClose(NativeImage uploadImage) {
            try {
                RenderSystem.assertOnRenderThreadOrInit();
                TextureUtil.prepareImage(getId(), uploadImage.getWidth(), uploadImage.getHeight());
                setFilter(blur, mipmap);
                uploadImage.upload(0, 0, 0, false);
            } finally {
                uploadImage.close();
            }
        }

        @Override
        public void close() {
            if (image != null) {
                image.close();
                image = null;
            }
            releaseId();
        }
    }
}
