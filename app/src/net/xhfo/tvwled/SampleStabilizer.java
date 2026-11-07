package net.xhfo.tvwled;

/**
 * Removes single-frame sampling noise before it reaches the colour engine.
 *
 * <p>The vendor colour service occasionally returns a frame that is far off (a zeroed read, or a
 * value a whole step away) even while the panel shows a still picture. The smoothing in
 * {@link EdgeColorEngine} cannot hide that: it faithfully follows the bad frame, which is the
 * flicker seen on static content. A short median filter rejects those spikes, and a one-level
 * dead band keeps an unchanged picture bit-for-bit stable.
 */
final class SampleStabilizer {
    private final int frames;
    private int[][][] history;
    private int[][] last;
    private int count, cursor, length;
    private final int[] scratch;

    SampleStabilizer(int frames) {
        if (frames < 3) throw new IllegalArgumentException("At least three frames are required");
        this.frames = frames;
        this.scratch = new int[frames];
    }

    int[][] push(int[][] raw) {
        if (raw == null) throw new IllegalArgumentException("Samples are required");
        if (length != raw.length) reset(raw.length);
        int[][] slot = history[cursor];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == null || raw[i].length != 3) throw new IllegalArgumentException("Sample RGB format");
            slot[i][0] = raw[i][0];
            slot[i][1] = raw[i][1];
            slot[i][2] = raw[i][2];
        }
        cursor = (cursor + 1) % frames;
        if (count < frames) count++;
        int[][] out = new int[raw.length][3];
        for (int i = 0; i < raw.length; i++) {
            for (int channel = 0; channel < 3; channel++) {
                int value = median(i, channel);
                if (last != null && Math.abs(value - last[i][channel]) <= 1) value = last[i][channel];
                out[i][channel] = value;
            }
        }
        last = out;
        return out;
    }

    private int median(int point, int channel) {
        for (int i = 0; i < count; i++) scratch[i] = history[i][point][channel];
        // Insertion sort on at most a handful of frames.
        for (int i = 1; i < count; i++) {
            int value = scratch[i], j = i - 1;
            while (j >= 0 && scratch[j] > value) { scratch[j + 1] = scratch[j]; j--; }
            scratch[j + 1] = value;
        }
        return scratch[count / 2];
    }

    private void reset(int points) {
        history = new int[frames][points][3];
        last = null;
        count = 0;
        cursor = 0;
        length = points;
    }
}
