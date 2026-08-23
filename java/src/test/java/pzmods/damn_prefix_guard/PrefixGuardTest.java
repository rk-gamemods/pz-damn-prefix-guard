package pzmods.damn_prefix_guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import zombie.core.Core;

public final class PrefixGuardTest {
    private int assertions;

    public static void main(String[] args) throws Exception {
        PrefixGuardTest suite = new PrefixGuardTest();
        suite.run();
        System.out.println("PrefixGuardTest: " + suite.assertions + " assertions passed");
    }

    private void run() throws Exception {
        Path temp = Files.createTempDirectory("damn-prefix-guard-test-");
        try {
            Path root = officialRoot(temp);
            Path file = root.resolve("common/media/models_X/Vehicles_test.fbx");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "v1");

            FakeFileSystem fs = new FakeFileSystem(root);
            fs.activate(file);
            String input = file.toString();

            check(suppress(fs, input), "accepts the exact active DAMN file");
            check(suppress(fs, input), "repeated invocation is idempotent");

            Core.setVersionForTest("42.21.0");
            check(!suppress(fs, input), "future game versions fail closed at runtime");
            Core.setVersionForTest("42.20.3");

            Files.writeString(file, "upstream-v2-content");
            check(suppress(fs, input), "asset content changes are not shadowed or pinned");

            check(!PrefixGuard.shouldSuppress(fs, new Object[] {input},
                new IllegalArgumentException("different message")), "rejects a changed message");
            check(!PrefixGuard.shouldSuppress(fs, new Object[] {input},
                new DerivedIllegalArgumentException("Invalid prefix found for: " + input)),
                "rejects exception subclasses");
            check(!PrefixGuard.shouldSuppress(fs, new Object[] {input, "extra"}, exception(input)),
                "rejects changed method arity");
            check(!PrefixGuard.shouldSuppress(fs, new Object[] {42}, exception(input)),
                "rejects changed argument type");
            check(!PrefixGuard.shouldSuppress(fs, new Object[] {input}, null),
                "leaves normal returns unchanged");

            Path inactive = root.resolve("common/media/models_X/inactive.fbx");
            Files.writeString(inactive, "inactive");
            check(!suppress(fs, inactive.toString()), "rejects a DAMN file not selected in activeFileMap");

            fs.activeFileMap.clear();
            fs.activeFileMap.put("broken", "\0");
            fs.activeFileMap.put("media/models_x/test.fbx", file.toString());
            fs.knownExact.add(file.toString());
            check(suppress(fs, input), "stale unrelated map values cannot veto a later exact match");

            Path outside = temp.resolve("other/mod/file.fbx");
            Files.createDirectories(outside.getParent());
            Files.writeString(outside, "outside");
            fs.activate(outside);
            check(!suppress(fs, outside.toString()), "rejects a known file outside DAMN");

            fs.activeFileMap.clear();
            fs.relativeCollision = true;
            check(!suppress(fs, input), "isKnownFile alone cannot authorize a relative-key collision");

            fs.activeFileMap.put("media/models_x/test.fbx", outside.toString());
            fs.knownExact.add(input);
            check(!suppress(fs, input), "rejects a shadowed known DAMN file when another file is active");

            fs.activate(file);
            Files.delete(file);
            check(!suppress(fs, input), "rejects missing files");

            Files.writeString(file, "restored");
            check(!PrefixGuard.shouldSuppress(new MissingInterface(), new Object[] {input}, exception(input)),
                "reflection drift fails closed");

            Path fakeRoot = temp.resolve("local/mods/damnlib");
            Path fakeFile = fakeRoot.resolve("common/media/models_X/fake.fbx");
            Files.createDirectories(fakeFile.getParent());
            Files.writeString(fakeFile, "fake");
            FakeFileSystem fake = new FakeFileSystem(fakeRoot);
            fake.activate(fakeFile);
            check(!suppress(fake, fakeFile.toString()), "rejects non-Workshop DAMN impersonation");

            Path updatedRoot = officialRoot(temp.resolve("updated-location"));
            Path updatedFile = updatedRoot.resolve("common/media/models_X/new-upstream-file.fbx");
            Files.createDirectories(updatedFile.getParent());
            Files.writeString(updatedFile, "new file");
            FakeFileSystem updated = new FakeFileSystem(updatedRoot);
            updated.activate(updatedFile);
            check(suppress(updated, updatedFile.toString()),
                "accepts newly added upstream files without copying them into the patch");
        } finally {
            deleteTree(temp);
        }
    }

    private static Path officialRoot(Path base) throws IOException {
        Path root = base.resolve("steamapps/workshop/content/108600/3171167894/mods/damnlib");
        Files.createDirectories(root);
        return root;
    }

    private static IllegalArgumentException exception(String input) {
        return new IllegalArgumentException("Invalid prefix found for: " + input);
    }

    private static boolean suppress(Object fs, String input) {
        return PrefixGuard.shouldSuppress(fs, new Object[] {input}, exception(input));
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static final class FakeFileSystem {
        public final Map<String, String> activeFileMap = new LinkedHashMap<>();
        public final Set<String> knownExact = new HashSet<>();
        private final String root;
        private boolean relativeCollision;

        FakeFileSystem(Path root) {
            this.root = root.toString();
        }

        public String getModDir(String modId) {
            return "damnlib".equals(modId) ? root : null;
        }

        public boolean isKnownFile(String path) {
            return knownExact.contains(path) || relativeCollision;
        }

        void activate(Path path) {
            activeFileMap.clear();
            activeFileMap.put("media/models_x/test.fbx", path.toString());
            knownExact.add(path.toString());
            relativeCollision = false;
        }
    }

    public static final class MissingInterface {
    }

    private static final class DerivedIllegalArgumentException extends IllegalArgumentException {
        DerivedIllegalArgumentException(String message) {
            super(message);
        }
    }
}
