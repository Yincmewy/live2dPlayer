package yincmewy.sisi.live2dplayer.live2d;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class Live2DNativeLibraryInstaller {
    public static final String RESOURCE_ROOT = "assets/live2dplayer/native/";

    private static final List<String> BUNDLED_DLLS = List.of(
            "Live2DCubismCore.dll",
            "Live2DCubismCore64.dll",
            "msvcp140.dll",
            "msvcp140_1.dll",
            "msvcp140_2.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "ucrtbase.dll",
            "concrt140.dll"
    );

    private Live2DNativeLibraryInstaller() {
    }

    public static void install(Path coreDirectory) {
        if (coreDirectory == null) {
            return;
        }

        try {
            Files.createDirectories(coreDirectory);
        } catch (Exception exception) {
            System.err.println("[Live2D] failed to create core directory " + coreDirectory + ": " + exception);
            return;
        }

        ClassLoader loader = Live2DNativeLibraryInstaller.class.getClassLoader();
        for (String dllName : BUNDLED_DLLS) {
            copyIfBundled(loader, dllName, coreDirectory.resolve(dllName));
        }
    }

    private static void copyIfBundled(ClassLoader loader, String dllName, Path target) {
        if (Files.exists(target)) {
            return;
        }

        String resource = RESOURCE_ROOT + dllName;
        try (InputStream stream = Live2DNativeLibraryInstaller.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Live2D] installed bundled native library: " + target);
        } catch (Exception exception) {
            System.err.println("[Live2D] failed to install bundled native library " + resource + ": " + exception);
        }
    }
}
