package yincmewy.sisi.live2dplayer.live2d;

import java.util.List;

public record Live2DParameterGroup(String target, String name, List<String> ids) {
    public Live2DParameterGroup {
        target = target == null ? "" : target;
        name = name == null ? "" : name;
        ids = ids == null ? List.of() : List.copyOf(ids);
    }

    public boolean isParameterGroup(String expectedName) {
        return "Parameter".equalsIgnoreCase(target) && name.equalsIgnoreCase(expectedName);
    }
}
