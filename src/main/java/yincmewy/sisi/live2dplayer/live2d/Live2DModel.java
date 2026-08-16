package yincmewy.sisi.live2dplayer.live2d;

import java.nio.file.Path;
import java.util.List;

public final class Live2DModel {
    private final String name;
    private final Path folder;
    private final Path modelJson;
    private final Path moc;
    private final Path physics;
    private final Path displayInfo;
    private final Path icon;
    private final List<Path> textures;
    private final List<Live2DMotion> motions;
    private final List<Live2DExpression> expressions;
    private final List<Live2DParameterGroup> groups;
    private final String error;

    public Live2DModel(String name, Path folder, Path modelJson, Path moc, Path physics, Path displayInfo, Path icon,
                       List<Path> textures, List<Live2DMotion> motions, List<Live2DExpression> expressions,
                       List<Live2DParameterGroup> groups, String error) {
        this.name = name;
        this.folder = folder;
        this.modelJson = modelJson;
        this.moc = moc;
        this.physics = physics;
        this.displayInfo = displayInfo;
        this.icon = icon;
        this.textures = List.copyOf(textures);
        this.motions = List.copyOf(motions);
        this.expressions = List.copyOf(expressions);
        this.groups = List.copyOf(groups);
        this.error = error == null ? "" : error;
    }

    public String name() {
        return name;
    }

    public Path folder() {
        return folder;
    }

    public Path modelJson() {
        return modelJson;
    }

    public Path moc() {
        return moc;
    }

    public Path physics() {
        return physics;
    }

    public Path displayInfo() {
        return displayInfo;
    }

    public Path icon() {
        return icon;
    }

    public List<Path> textures() {
        return textures;
    }

    public List<Live2DMotion> motions() {
        return motions;
    }

    public List<Live2DExpression> expressions() {
        return expressions;
    }

    public List<Live2DParameterGroup> groups() {
        return groups;
    }

    public List<String> groupIds(String groupName) {
        for (Live2DParameterGroup group : groups) {
            if (group.isParameterGroup(groupName)) {
                return group.ids();
            }
        }
        return List.of();
    }

    public String error() {
        return error;
    }

    public boolean valid() {
        return error.isEmpty() && modelJson != null && moc != null && !textures.isEmpty();
    }

    public Path primaryImagePath() {
        if (icon != null) {
            return icon;
        }
        return textures.isEmpty() ? null : textures.get(0);
    }
}
