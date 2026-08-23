package zombie;

import java.util.HashMap;

public class ZomboidFileSystem {
    public final HashMap<String, String> activeFileMap = new HashMap<>();
    private final String modDir;
    private boolean known;

    public ZomboidFileSystem(String modDir) {
        this.modDir = modDir;
    }

    public String getModDir(String modId) {
        return "damnlib".equals(modId) ? modDir : null;
    }

    public boolean isKnownFile(String path) {
        return known;
    }

    public void activate(String path) {
        activeFileMap.clear();
        activeFileMap.put("media/models_x/integration.fbx", path);
        known = true;
    }

    public void clearActiveFiles() {
        activeFileMap.clear();
        known = false;
    }

    public void validatePrefix(String input) {
        throw new IllegalArgumentException("Invalid prefix found for: " + input);
    }

    public void validatePrefix(String input, int changedSignature) {
        throw new IllegalArgumentException("Invalid prefix found for: " + input);
    }
}
