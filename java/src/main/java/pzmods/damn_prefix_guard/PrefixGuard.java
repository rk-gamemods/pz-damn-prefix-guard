package pzmods.damn_prefix_guard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.ConcurrentModificationException;

public final class PrefixGuard {
    private static final String DAMN_MOD_ID = "damnlib";
    private static final String SUPPORTED_GAME_VERSION = "42.20.3";
    private static final String[] OFFICIAL_ROOT_TAIL = {
        "steamapps", "workshop", "content", "108600", "3171167894", "mods", "damnlib"
    };
    private PrefixGuard() {
    }

    public static boolean shouldSuppress(Object self, Object[] arguments, Throwable thrown) {
        if (self == null || arguments == null || arguments.length != 1) {
            return false;
        }
        if (!(arguments[0] instanceof String input) || input.isBlank()) {
            return false;
        }
        if (thrown == null || thrown.getClass() != IllegalArgumentException.class) {
            return false;
        }
        if (!("Invalid prefix found for: " + input).equals(thrown.getMessage())) {
            return false;
        }

        try {
            if (!isSupportedGameVersion(self)) {
                return false;
            }
            Path inputPath = Path.of(input);
            if (!inputPath.isAbsolute()
                || Files.isSymbolicLink(inputPath)
                || !Files.isRegularFile(inputPath, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }

            Method getModDir = self.getClass().getMethod("getModDir", String.class);
            if (getModDir.getReturnType() != String.class) {
                return false;
            }
            Object rootValue = getModDir.invoke(self, DAMN_MOD_ID);
            if (!(rootValue instanceof String rootString) || rootString.isBlank()) {
                return false;
            }

            Path rootPath = Path.of(rootString);
            if (!rootPath.isAbsolute()) {
                return false;
            }
            Path realRoot = rootPath.toRealPath();
            if (!hasOfficialRootTail(realRoot)) {
                return false;
            }

            Path normalizedInput = inputPath.toAbsolutePath().normalize();
            Path realInput = inputPath.toRealPath();
            if (realInput.equals(realRoot) || !realInput.startsWith(realRoot)) {
                return false;
            }

            Method isKnownFile = self.getClass().getMethod("isKnownFile", String.class);
            if (isKnownFile.getReturnType() != boolean.class
                || !Boolean.TRUE.equals(isKnownFile.invoke(self, input))) {
                return false;
            }

            Field activeFileMapField = self.getClass().getField("activeFileMap");
            if (!Map.class.isAssignableFrom(activeFileMapField.getType())) {
                return false;
            }
            Object activeFileMapValue = activeFileMapField.get(self);
            if (!(activeFileMapValue instanceof Map<?, ?> activeFileMap)) {
                return false;
            }

            for (Object value : activeFileMap.values()) {
                if (!(value instanceof String activePath) || activePath.isBlank()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(activePath);
                    if (candidate.isAbsolute()
                        && candidate.toAbsolutePath().normalize().equals(normalizedInput)) {
                        return true;
                    }
                } catch (InvalidPathException | SecurityException ignored) {
                    // A stale unrelated entry cannot veto a later exact match.
                }
            }
        } catch (ReflectiveOperationException
                 | IOException
                 | InvalidPathException
                 | SecurityException
                 | ClassCastException
                 | ConcurrentModificationException ignored) {
            // Fail closed. Reflection, filesystem, or concurrent-map drift must
            // preserve the original game exception.
        }
        return false;
    }

    private static boolean hasOfficialRootTail(Path path) {
        if (path.getNameCount() < OFFICIAL_ROOT_TAIL.length) {
            return false;
        }
        int offset = path.getNameCount() - OFFICIAL_ROOT_TAIL.length;
        for (int i = 0; i < OFFICIAL_ROOT_TAIL.length; i++) {
            if (!OFFICIAL_ROOT_TAIL[i].equalsIgnoreCase(path.getName(offset + i).toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedGameVersion(Object self) throws ReflectiveOperationException {
        ClassLoader loader = self.getClass().getClassLoader();
        Class<?> coreClass = Class.forName("zombie.core.Core", false, loader);
        Method getInstance = coreClass.getMethod("getInstance");
        Object core = getInstance.invoke(null);
        if (core == null) {
            return false;
        }
        Method getGameVersion = coreClass.getMethod("getGameVersion");
        Object version = getGameVersion.invoke(core);
        return version != null && SUPPORTED_GAME_VERSION.equals(version.toString());
    }
}
