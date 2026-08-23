package zombie.core;

public final class Core {
    private static final Core INSTANCE = new Core();
    private static String version = "42.20.3";

    private Core() {
    }

    public static Core getInstance() {
        return INSTANCE;
    }

    public Object getGameVersion() {
        return version;
    }

    public static void setVersionForTest(String value) {
        version = value;
    }
}
