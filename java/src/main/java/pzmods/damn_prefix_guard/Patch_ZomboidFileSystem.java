package pzmods.damn_prefix_guard;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(
    className = "zombie.ZomboidFileSystem",
    methodName = "validatePrefix",
    strictMatch = true
)
public final class Patch_ZomboidFileSystem {
    private Patch_ZomboidFileSystem() {
    }

    @Patch.OnExit(onThrowable = IllegalArgumentException.class)
    public static void exit(
        @Patch.This Object self,
        @Patch.AllArguments Object[] arguments,
        @Patch.Thrown(readOnly = false) Throwable thrown
    ) {
        Throwable original = thrown;
        try {
            if (PrefixGuard.shouldSuppress(self, arguments, thrown)) {
                thrown = null;
            }
        } catch (Throwable ignored) {
            // Advice must never replace the game's original failure.
            thrown = original;
        }
    }
}
