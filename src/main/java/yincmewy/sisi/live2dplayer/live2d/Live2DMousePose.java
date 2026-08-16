package yincmewy.sisi.live2dplayer.live2d;

public record Live2DMousePose(
        float normalizedX,
        float normalizedY,
        float angleX,
        float angleY,
        float angleZ,
        float eyeBallX,
        float eyeBallY,
        float bodyAngleX,
        float bodyAngleY,
        float bodyAngleZ,
        float breath
) {
    public static Live2DMousePose fromMinecraftMouse(double mouseX, double mouseY, float x, float y,
                                                     float width, float height, Live2DConfig config,
                                                     float elapsedSeconds) {
        float safeWidth = Math.max(1.0F, width);
        float safeHeight = Math.max(1.0F, height);
        float centerX = x + safeWidth * 0.5F;
        float centerY = y + safeHeight * 0.42F;
        float strength = config == null ? 1.0F : clamp(config.mouseStrength, 0.0F, 3.0F);
        float normalizedX = clamp((float) ((mouseX - centerX) / (safeWidth * 0.5F)), -1.0F, 1.0F) * strength;
        float normalizedY = clamp((float) ((mouseY - centerY) / (safeHeight * 0.5F)), -1.0F, 1.0F) * strength;
        return fromNormalized(normalizedX, normalizedY, config, elapsedSeconds);
    }

    public static Live2DMousePose fromPlayerRotation(float rotationX, float rotationY, Live2DConfig config,
                                                     float elapsedSeconds) {
        float strength = config == null ? 1.0F : clamp(config.mouseStrength, 0.0F, 3.0F);
        return fromNormalized(rotationX * strength, rotationY * strength, config, elapsedSeconds);
    }

    private static Live2DMousePose fromNormalized(float normalizedX, float normalizedY, Live2DConfig config,
                                                  float elapsedSeconds) {
        if (config != null && config.mirror) {
            normalizedX = -normalizedX;
        }

        normalizedX = clamp(normalizedX, -1.0F, 1.0F);
        normalizedY = clamp(normalizedY, -1.0F, 1.0F);
        float idleSpeed = config == null ? 1.0F : clamp(config.idleSpeed, 0.0F, 5.0F);
        float breath = idleSpeed <= 0.0F
                ? 0.5F
                : 0.5F + 0.5F * (float) Math.sin(elapsedSeconds * Math.PI * 2.0D * idleSpeed * 0.28D);

        return new Live2DMousePose(
                normalizedX,
                normalizedY,
                normalizedX * 30.0F,
                -normalizedY * 30.0F,
                normalizedX * 10.0F,
                normalizedX,
                -normalizedY,
                normalizedX * 10.0F,
                -normalizedY * 8.0F,
                normalizedX * 4.0F,
                breath
        );
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
