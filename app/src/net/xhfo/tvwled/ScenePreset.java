package net.xhfo.tvwled;

/**
 * What each smart preset drives. A source binding maps a display input to one of these, so the
 * desk lamp can come on for a PC input without lighting the strip or the back LED.
 */
final class ScenePreset {
    static final String[] IDS = {"cinema", "game", "office", "night", "off"};
    static final String[] LABELS = {"观影", "游戏", "办公", "夜间", "全关"};

    private ScenePreset() {}

    static boolean known(String id) {
        for (String candidate : IDS) if (candidate.equals(id)) return true;
        return false;
    }

    /** Null means "no binding": keep the current behaviour of driving the strip. */
    static boolean strip(String preset) {
        return preset == null || "cinema".equals(preset) || "game".equals(preset);
    }

    static boolean ambient(String preset) {
        return preset == null || "cinema".equals(preset) || "game".equals(preset);
    }

    /** The bar's front light is the desk lamp: some presets want it, some want the room dark. */
    static boolean front(String preset) {
        return "office".equals(preset) || "night".equals(preset) || "game".equals(preset);
    }

    static int frontBrightness(String preset) {
        if ("office".equals(preset)) return 80;
        if ("night".equals(preset)) return 15;
        if ("game".equals(preset)) return 30;
        return 1;
    }

    static int frontCt(String preset) {
        if ("night".equals(preset)) return 2700;
        if ("office".equals(preset) || "game".equals(preset)) return 4000;
        return 4000;
    }
}
