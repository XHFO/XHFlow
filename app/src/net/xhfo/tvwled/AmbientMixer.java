package net.xhfo.tvwled;

/**
 * Collapses the flat per-LED RGB frame into the single colour the Yeelight bar can show.
 *
 * <p>{@link EdgeColorEngine#smooth} returns {@code [r,g,b, r,g,b, …]} indexed by WLED LED number,
 * not packed {@code 0xRRGGBB} integers. Reading it as packed colours silently produced near-black
 * values, so all of that arithmetic lives here and is covered by a host contract.
 */
final class AmbientMixer {
    private AmbientMixer() {}

    /** Mean of the given LED numbers, clamped into 0xRRGGBB. Empty selection becomes black. */
    static int average(int[] flatRgb, int[] ledIndices) {
        if (flatRgb == null || ledIndices == null) return 0;
        int red = 0, green = 0, blue = 0, count = 0;
        for (int index : ledIndices) {
            int base = index * 3;
            if (index < 0 || base + 2 >= flatRgb.length) continue;
            red += flatRgb[base];
            green += flatRgb[base + 1];
            blue += flatRgb[base + 2];
            count++;
        }
        if (count == 0) return 0;
        return (clamp(red / count) << 16) | (clamp(green / count) << 8) | clamp(blue / count);
    }

    /**
     * Brightness percentage for the front light: the darker of the peak and mean LED level, scaled
     * into the configured ceiling so the lamp never outruns the strip.
     */
    static int mainLightPercent(int[] flatRgb, int maxPercent) {
        int ceiling = Math.max(1, Math.min(100, maxPercent));
        if (flatRgb == null || flatRgb.length < 3) return 1;
        int peak = 0, total = 0, lit = 0;
        for (int i = 0; i + 2 < flatRgb.length; i += 3) {
            int brightest = Math.max(flatRgb[i], Math.max(flatRgb[i + 1], flatRgb[i + 2]));
            peak = Math.max(peak, brightest);
            total += brightest;
            lit++;
        }
        if (lit == 0) return 1;
        int level = (int) Math.round(Math.min(peak, total / lit) / 255.0 * ceiling);
        return Math.max(1, Math.min(ceiling, level));
    }

    private static int clamp(int value) { return value < 0 ? 0 : value > 255 ? 255 : value; }
}
