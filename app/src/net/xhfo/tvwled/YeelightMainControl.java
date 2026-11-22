package net.xhfo.tvwled;

import org.json.JSONObject;

/** Ownership of the desk lamp survives capture sessions, sleep and process recovery. */
final class YeelightMainControl {
    static final String PREF_KEY = "yeelight_main_control";
    static final long SETTLE_MS = 1200;
    static final int TRANSITION_MS = 5000;
    interface Store { boolean save(String value); }
    interface TransitionSender { void send(Integer brightness, Integer ct); }

    private final Store store;
    private String address = "", source = "";
    private boolean manual, dirty, fullObserved;
    private long epoch;
    private boolean powerPending;
    private Boolean powerTarget;
    private long powerSettleAt = -1;
    private final Channel brightness = new Channel(), ct = new Channel();

    YeelightMainControl(String saved, Store store) {
        this.store = store;
        try {
            JSONObject value = new JSONObject(saved);
            String lamp = value.getString("address"), input = value.getString("source");
            LayoutConfig.validateAddress(lamp);
            if (input.length() > 80 || !(value.get("manual") instanceof Boolean)) return;
            address = lamp; source = input; manual = value.getBoolean("manual");
        } catch (Exception absentOrInvalid) { /* An old installation has no ownership record. */ }
    }

    synchronized void selectAddress(String value) {
        if (value == null || value.isEmpty() || value.equals(address)) return;
        address = value; manual = false; epoch++; resetReadings(); persist();
    }

    /** Unknown readings and standby transitions never count as a source switch. */
    synchronized boolean source(String input, boolean known, boolean awake) {
        if (!known || !awake || input == null || input.isEmpty()) return false;
        boolean switched = !source.isEmpty() && !source.equals(input);
        if (!source.equals(input)) {
            source = input;
            if (switched) { manual = false; epoch++; resetReadings(); }
            persist();
        } else if (dirty) persist();
        return switched;
    }

    synchronized long lease() { return epoch; }
    synchronized boolean manual() { return manual; }
    synchronized boolean persistenceFailed() { return dirty; }

    synchronized String state(long lease, boolean readable) {
        if (manual) return "manual";
        if (lease != epoch || !readable || !fullObserved) return "observing";
        if (powerPending || brightness.transitionPending || ct.transitionPending) return "transitioning";
        return brightness.suspect || ct.suspect ? "verifying" : "automatic";
    }

    /** Notifications suspend writes immediately; only a settled full readback confirms override. */
    synchronized void observe(long lease, Integer level, Integer kelvin, boolean full, long now) {
        if (lease != epoch || manual) return;
        if (level != null && (level < 1 || level > 100)) level = null;
        if (kelvin != null && (kelvin < 1700 || kelvin > 6500)) kelvin = null;
        if (full && (level == null || kelvin == null)) return;
        if (!fullObserved && !full) return;
        boolean alreadyObserved = fullObserved;
        if (full) fullObserved = true;
        // A power fade may report temporary dimmer values without changing the stored level.
        // Keep its original baseline, while permitting the first brightness/CT plan to start.
        if (powerPending && !brightness.transitionPending && !ct.transitionPending
                && alreadyObserved && now < powerSettleAt) return;
        if (level != null) brightness.observe(level, full, now);
        if (kelvin != null) ct.observe(kelvin, full, now);
        long settleAt = Math.max(Math.max(brightness.settleAt, ct.settleAt), powerPending ? powerSettleAt : -1);
        if (full && (brightness.suspect || ct.suspect)
                && (settleAt < 0 || now >= settleAt)) {
            manual = true;
            persist();
        } else if (full && !brightness.suspect && !ct.suspect && now >= settleAt) {
            brightness.transitionPending = false; ct.transitionPending = false; powerPending = false;
        }
    }

    /** One native fade at a time. New desired levels are supplied again after its readback. */
    synchronized boolean transition(long lease, Integer level, Integer kelvin, long now,
            java.util.function.BooleanSupplier permitted, TransitionSender send) {
        if (!writable(lease, permitted)) return false;
        if (level != null) level = Math.max(1, Math.min(100, level));
        if (kelvin != null) kelvin = Math.max(1700, Math.min(6500, kelvin));
        Integer nextLevel = level != null && level != brightness.expected ? level : null;
        Integer nextCt = kelvin != null && kelvin != ct.expected ? kelvin : null;
        if (nextLevel == null && nextCt == null) return true;
        // Register both properties atomically before either packet can produce a notification.
        if (nextLevel != null) brightness.command(nextLevel, now, TRANSITION_MS);
        if (nextCt != null) ct.command(nextCt, now, TRANSITION_MS);
        send.send(nextLevel, nextCt);
        return true;
    }

    /** Power keeps the existing scene semantics, including when brightness/CT are manual. */
    synchronized boolean power(long lease, boolean on, long now,
            java.util.function.BooleanSupplier permitted, Runnable send) {
        if (lease != epoch || !permitted.getAsBoolean()) return false;
        if (powerPending && powerTarget != null && powerTarget == on) return true;
        powerPending = true; powerTarget = on; powerSettleAt = now + TRANSITION_MS + SETTLE_MS;
        send.run();
        return true;
    }

    synchronized boolean brightness(long lease, int value, long now,
            java.util.function.BooleanSupplier permitted, Runnable send) {
        return write(brightness, lease, Math.max(1, Math.min(100, value)), now, permitted, send);
    }

    synchronized boolean ct(long lease, int value, long now,
            java.util.function.BooleanSupplier permitted, Runnable send) {
        return write(ct, lease, Math.max(1700, Math.min(6500, value)), now, permitted, send);
    }

    private boolean write(Channel channel, long lease, int value, long now,
            java.util.function.BooleanSupplier permitted, Runnable send) {
        if (!writable(lease, permitted)) return false;
        // Register before sending: a fast property notification can arrive on another thread.
        channel.command(value, now, 0);
        send.run();
        return true;
    }

    private boolean writable(long lease, java.util.function.BooleanSupplier permitted) {
        return lease == epoch && !manual && !brightness.suspect && !ct.suspect
                && !brightness.transitionPending && !ct.transitionPending && fullObserved
                && permitted.getAsBoolean();
    }

    private void resetReadings() {
        fullObserved = false; brightness.reset(); ct.reset();
        powerPending = false; powerTarget = null; powerSettleAt = -1;
    }

    private void persist() {
        if (address.isEmpty()) return;
        try {
            dirty = !store.save(new JSONObject().put("address", address).put("source", source)
                    .put("manual", manual).toString());
        } catch (Exception failure) { dirty = true; }
    }

    private static final class Channel {
        boolean known, suspect, transitionPending;
        int expected;
        long settleAt = -1;
        void reset() { known = false; suspect = false; transitionPending = false; settleAt = -1; }
        void command(int value, long now, int duration) {
            expected = value; settleAt = now + duration + SETTLE_MS;
            transitionPending = duration > 0;
        }
        void observe(int value, boolean full, long now) {
            if (!known) { known = true; expected = value; suspect = false; return; }
            if (value == expected) {
                // A late own notification cannot undo an unverified external change. Wait for
                // a complete read after our last command has settled before resuming writes.
                if (!suspect || (full && (settleAt < 0 || now >= settleAt))) suspect = false;
                return;
            }
            // Never accept a new baseline here: it may be a physical dial or another controller.
            suspect = true;
        }
    }
}
