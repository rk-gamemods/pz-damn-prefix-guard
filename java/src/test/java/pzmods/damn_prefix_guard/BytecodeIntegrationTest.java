package pzmods.damn_prefix_guard;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import me.zed_0xff.zombie_buddy.PatchEngine;
import net.bytebuddy.agent.ByteBuddyAgent;

public final class BytecodeIntegrationTest {
    private int assertions;

    public static void main(String[] args) throws Exception {
        BytecodeIntegrationTest suite = new BytecodeIntegrationTest();
        suite.run();
        System.out.println("BytecodeIntegrationTest: " + suite.assertions + " assertions passed");
    }

    private void run() throws Exception {
        // The real game target is already loaded when a normal Java mod is
        // approved. Exercise ZombieBuddy's retransformation path, not only its
        // first-load transformer path.
        Class<?> target = Class.forName("zombie.ZomboidFileSystem");
        Instrumentation instrumentation = ByteBuddyAgent.install();
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        var instrumentationField = loader.getDeclaredField("g_instrumentation");
        instrumentationField.setAccessible(true);
        instrumentationField.set(null, instrumentation);

        PatchEngine.applyPatches("pzmods.damn_prefix_guard", getClass().getClassLoader());

        Path temp = Files.createTempDirectory("damn-prefix-bytecode-test-");
        try {
            Path root = temp.resolve("steamapps/workshop/content/108600/3171167894/mods/damnlib");
            Path active = root.resolve("common/media/models_X/integration.fbx");
            Path inactive = root.resolve("common/media/models_X/inactive.fbx");
            Files.createDirectories(active.getParent());
            Files.writeString(active, "active");
            Files.writeString(inactive, "inactive");

            Object instance = target.getConstructor(String.class).newInstance(root.toString());
            Method activate = target.getMethod("activate", String.class);
            Method oneArgument = target.getMethod("validatePrefix", String.class);
            Method changedSignature = target.getMethod("validatePrefix", String.class, int.class);
            activate.invoke(instance, active.toString());

            oneArgument.invoke(instance, active.toString());
            check(true, "exact active file exception was suppressed by transformed bytecode");

            checkThrows(oneArgument, instance, inactive.toString(),
                "inactive file exception remains after transformation");
            checkThrows(changedSignature, instance, active.toString(), 1,
                "future overload is matched by name but rejected by the runtime arity guard");
        } finally {
            deleteTree(temp);
        }
    }

    private void checkThrows(Method method, Object instance, Object... arguments) throws Exception {
        String message = (String) arguments[arguments.length - 1];
        Object[] invocationArguments = new Object[arguments.length - 1];
        System.arraycopy(arguments, 0, invocationArguments, 0, invocationArguments.length);
        try {
            method.invoke(instance, invocationArguments);
            throw new AssertionError(message);
        } catch (InvocationTargetException error) {
            check(error.getCause() != null && error.getCause().getClass() == IllegalArgumentException.class,
                message);
        }
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
