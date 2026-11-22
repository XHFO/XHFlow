package net.xhfo.tvwled;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual main-light transition controller and command contracts; no lamp or network access. */
public final class YeelightTransitionContractTest {
    private static int checks;
    private static final long QUIET = YeelightMainControl.TRANSITION_MS + YeelightMainControl.SETTLE_MS;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Store implements YeelightMainControl.Store {
        String saved = "";
        int writes;
        public boolean save(String value) { saved = value; writes++; return true; }
    }

    private static final class Sent {
        final Integer brightness, ct;
        Sent(Integer brightness, Integer ct) { this.brightness = brightness; this.ct = ct; }
    }

    private static final class Fixture {
        final Store store = new Store();
        final YeelightMainControl control;
        final List<Sent> sent = new ArrayList<>();
        int powers;
        Fixture() { this(50, 4000); }
        Fixture(int level, int ct) {
            control = new YeelightMainControl("", store);
            control.selectAddress("192.168.1.60");
            control.source("30", true, true);
            control.observe(lease(), level, ct, true, 0);
        }
        long lease() { return control.lease(); }
        boolean transition(Integer level, Integer ct, long now) {
            return control.transition(lease(), level, ct, now, () -> true,
                    (brightness, kelvin) -> sent.add(new Sent(brightness, kelvin)));
        }
        void snapshot(int level, int ct, long queryTime) {
            control.observe(lease(), level, ct, true, queryTime);
        }
        void blocked(long now, String why) {
            int before = sent.size();
            check(!transition(90, 5000, now), why + ": a new transition is blocked");
            check(!control.brightness(lease(), 90, now, () -> true,
                    () -> sent.add(new Sent(90, null))), why + ": legacy brightness cannot bypass the transition");
            check(!control.ct(lease(), 5000, now, () -> true,
                    () -> sent.add(new Sent(null, 5000))), why + ": legacy CT cannot bypass the transition");
            check(sent.size() == before, why + ": no transport write occurs");
        }
    }

    private static void completeReadbackRequired() {
        Store store = new Store();
        YeelightMainControl control = new YeelightMainControl("", store);
        control.selectAddress("192.168.1.60"); control.source("30", true, true);
        final int[] sends = {0};
        check(!control.transition(control.lease(), 80, 2700, 0, () -> true,
                (b, c) -> sends[0]++), "a transition cannot start before the first complete readback");
        control.observe(control.lease(), 50, null, false, 1);
        control.observe(control.lease(), null, 4000, false, 2);
        check(!control.transition(control.lease(), 80, 2700, 3, () -> true,
                (b, c) -> sends[0]++), "two unrelated notifications do not substitute for a complete readback");
        control.observe(control.lease(), 50, 4000, true, 10);
        check(!control.transition(control.lease(), 80, 2700, 11, () -> false,
                (b, c) -> sends[0]++), "a disconnected or stale monitor vetoes the transition");
        check(sends[0] == 0, "readiness failures never invoke the sender");
    }

    private static void bothDirectionsAndStableDeadline() {
        check(YeelightMainControl.TRANSITION_MS == 5000, "front-light transitions take five seconds");
        for (int[] direction : new int[][]{{80, 15, 4000, 2700}, {15, 80, 2700, 4000}}) {
            Fixture f = new Fixture(direction[0], direction[2]);
            long start = 100;
            check(f.transition(direction[1], direction[3], start), "a mode change starts one native transition");
            check(f.sent.size() == 1 && f.sent.get(0).brightness == direction[1]
                    && f.sent.get(0).ct == direction[3], "brightness and CT are sent together as one plan");
            check("transitioning".equals(f.control.state(f.lease(), true)), "the UI distinguishes an own fade from manual verification");
            int intermediate = (direction[0] + direction[1]) / 2;
            f.control.observe(f.lease(), intermediate, 3300, false, start + 500);
            f.snapshot(intermediate, 3300, start + 1500);
            check(!f.control.manual(), "the old 1200ms settling window cannot misclassify a five-second intermediate");
            for (long now : new long[]{start + 100, start + 1700, start + 3000, start + 4999, start + QUIET - 1}) {
                check(!f.transition(direction[1], direction[3], now), "a repeated same-target tick does not restart the active fade");
                check(!f.transition(95, 6000, now), "new dynamic targets wait for the active fade instead of interrupting it");
            }
            check(f.sent.size() == 1, "repeated and changing targets produce no extra commands during the fade");
            f.snapshot(direction[1], direction[3], start + YeelightMainControl.TRANSITION_MS);
            f.blocked(start + YeelightMainControl.TRANSITION_MS + 1, "a target echo still needs a stable complete readback");
            f.snapshot(direction[1], direction[3], start + QUIET - 1);
            check("transitioning".equals(f.control.state(f.lease(), true)), "an early complete target reply cannot end the verification window");
            f.snapshot(direction[1], direction[3], start + QUIET);
            check(!f.control.manual() && "automatic".equals(f.control.state(f.lease(), true)),
                    "the original deadline remains fixed despite repeated ticks");
            check(f.transition(direction[1], direction[3], start + QUIET + 1), "an already reached target is accepted");
            check(f.sent.size() == 1 && "automatic".equals(f.control.state(f.lease(), true)),
                    "the same settled target neither sends nor begins another fade");
            check(f.transition(95, 6000, start + QUIET + 2), "the latest desired mode can start a new smooth transition after confirmation");
            check(f.sent.size() == 2 && f.sent.get(1).brightness == 95 && f.sent.get(1).ct == 6000,
                    "resuming uses the latest target without a sudden intermediate command");
        }
    }

    private static void unchangedFieldsAndAtomicIntent() {
        Fixture f = new Fixture();
        check(f.transition(50, 4000, 10), "an unchanged initial target is a no-op success");
        check(f.sent.isEmpty() && "automatic".equals(f.control.state(f.lease(), true)),
                "an unchanged target does not create a five-second stall");
        check(f.transition(80, 4000, 100), "brightness alone can change");
        check(f.sent.size() == 1 && f.sent.get(0).brightness == 80 && f.sent.get(0).ct == null,
                "unchanged CT is omitted rather than restarting its animation");
        f.snapshot(80, 4000, 100 + QUIET);
        check(f.transition(80, 2700, 100 + QUIET + 1), "CT alone can change");
        check(f.sent.size() == 2 && f.sent.get(1).brightness == null && f.sent.get(1).ct == 2700,
                "unchanged brightness is omitted rather than restarting its animation");

        Fixture instant = new Fixture();
        long lease = instant.lease();
        check(instant.control.transition(lease, 15, 2700, 100, () -> true, (b, c) -> {
            instant.sent.add(new Sent(b, c));
            instant.control.observe(lease, 15, 2700, false, 100);
            check(!instant.control.transition(lease, 80, 4000, 101, () -> true,
                    (nextB, nextC) -> instant.sent.add(new Sent(nextB, nextC))),
                    "a synchronous echo cannot race a second plan before the shared intent is registered");
        }), "both targets are registered before the sender callback can report a fast echo");
        instant.blocked(200, "an immediate target echo does not skip the physical fade");
        instant.snapshot(15, 2700, 100 + QUIET);
        check(!instant.control.manual() && "automatic".equals(instant.control.state(lease, true)),
                "a fast own echo never masquerades as an external CT or brightness change");
    }

    private static void lateSnapshotsAndManualPriority() throws Exception {
        Fixture old = new Fixture();
        check(old.transition(15, 2700, 100), "a fade starts before a delayed snapshot arrives");
        old.snapshot(50, 4000, 90);
        check(!old.control.manual(), "a query sampled before the write cannot confirm an override even if received late");
        old.snapshot(15, 2700, 100 + QUIET - 1);
        old.blocked(100 + QUIET - 1, "a late target response still uses its query time");
        old.snapshot(15, 2700, 100 + QUIET);
        check(!old.control.manual(), "fresh final readback clears delayed own snapshots");

        for (boolean changeCt : new boolean[]{false, true}) {
            Fixture manual = new Fixture();
            check(manual.transition(15, 2700, 100), "a user can adjust the lamp during an active fade");
            int level = changeCt ? 15 : 42, kelvin = changeCt ? 3500 : 2700;
            manual.control.observe(manual.lease(), changeCt ? null : level, changeCt ? kelvin : null, false, 1500);
            manual.snapshot(level, kelvin, 2000);
            check(!manual.control.manual(), "intermediate manual-looking values wait for the native fade to settle");
            manual.blocked(2100, "an in-progress adjustment is never overwritten with a new plan");
            manual.snapshot(level, kelvin, 100 + QUIET);
            check(manual.control.manual() && "manual".equals(manual.control.state(manual.lease(), true)),
                    "a different brightness or CT after the fade yields both controls");
            check(new JSONObject(manual.store.saved).getBoolean("manual"), "confirmed handover is persisted");
            manual.blocked(10000, "confirmed manual priority");
            YeelightMainControl recovered = new YeelightMainControl(manual.store.saved, value -> true);
            check(recovered.manual(), "an already confirmed handover survives process recovery");
        }
    }

    private static void sourceLeasesAndReconnects() {
        Fixture f = new Fixture();
        long lease = f.lease();
        check(f.transition(15, 2700, 100), "a pending transition belongs to one input lease");
        check(!f.control.source("30", true, true), "same input polling is not a new mode transition");
        check(!f.control.source(null, false, true), "unknown input does not cancel the transition");
        check(!f.control.source("29", true, false), "standby does not fabricate a source switch");
        f.control.selectAddress("192.168.1.60");
        check(f.lease() == lease, "same-address reconnect and reload retain the transition lease");
        check(!f.control.transition(lease, 95, 5000, 200, () -> false,
                (b, c) -> f.sent.add(new Sent(b, c))), "loss of readback cannot issue a replacement target");
        f.snapshot(15, 2700, 100 + QUIET);
        check("automatic".equals(f.control.state(lease, true)), "same-source reconnect does not extend the original fade deadline");
        check(f.transition(80, 4000, 100 + QUIET + 1), "a second fade can be canceled by a real source change");
        check(f.control.source("29", true, true), "a known awake source change begins a different lease");
        check(f.lease() != lease && "observing".equals(f.control.state(f.lease(), true)),
                "a new source discards the old fade and awaits its own baseline");
        int before = f.sent.size();
        f.control.observe(lease, 80, 4000, true, 20000);
        check(!f.control.transition(lease, 90, 5000, 20001, () -> true,
                (b, c) -> f.sent.add(new Sent(b, c))), "the old session cannot resume its old transition");
        check(!f.control.power(lease, true, 20001, () -> true, () -> f.powers++), "the old session cannot restart a power fade");
        check(f.sent.size() == before && f.powers == 0, "stale callbacks perform no command");
        f.snapshot(37, 3300, 20002);
        check(f.transition(80, 4000, 20003), "the actual new mode starts from its fresh lamp state");
    }

    private static void powerFadeAndManualPower() {
        Store store = new Store();
        YeelightMainControl control = new YeelightMainControl("", store);
        control.selectAddress("192.168.1.60"); control.source("30", true, true);
        long lease = control.lease();
        final int[] power = {0}, plans = {0};
        check(control.power(lease, true, 100, () -> true, () -> power[0]++),
                "a scene power fade may start before the first main-light readback");
        control.observe(lease, 10, 4000, true, 200);
        control.observe(lease, 20, 4000, false, 300);
        control.observe(lease, 30, 4000, true, 400);
        check(!control.manual(), "power-on intermediate values are not manual adjustments");
        check(control.power(lease, true, 450, () -> true, () -> power[0]++),
                "a repeated pending power target is accepted without restarting its fade");
        check(power[0] == 1, "repeated pending power targets send no duplicate command");
        check(control.transition(lease, 80, 4000, 500, () -> true, (b, c) -> plans[0]++),
                "the first scene brightness target need not wait for a separate five-second power fade");
        check(power[0] == 1 && plans[0] == 1, "power and scene target each issue exactly one command plan");
        control.observe(lease, 70, 4000, true, 100 + QUIET);
        check(!control.manual(), "an earlier power deadline cannot finalize the later brightness fade");
        control.observe(lease, 80, 4000, true, 500 + QUIET);
        check(!control.manual() && "automatic".equals(control.state(lease, true)),
                "one complete final readback completes the combined power and brightness transition");

        Fixture manual = new Fixture();
        manual.control.observe(manual.lease(), 33, null, false, 10);
        manual.snapshot(33, 4000, 20);
        check(manual.control.manual(), "fixture holds manual brightness and CT priority");
        check(manual.control.power(manual.lease(), false, 100, () -> true, () -> manual.powers++),
                "manual brightness and CT priority still permits the existing scene power policy");
        check(manual.powers == 1 && manual.control.manual(), "a power command never reclaims manual brightness or CT");
        manual.blocked(100 + QUIET, "manual priority after a scene power command");

        Fixture repeated = new Fixture();
        check(!repeated.control.power(repeated.lease(), true, 1, () -> false, () -> repeated.powers++),
                "a disconnected session cannot start a power transition");
        check(repeated.control.power(repeated.lease(), true, 100, () -> true, () -> repeated.powers++),
                "a power-only mode transition begins once");
        for (long now : new long[]{200, 1300, 3000, 100 + QUIET - 1}) {
            check(repeated.control.power(repeated.lease(), true, now, () -> true, () -> repeated.powers++),
                    "same pending power accepts duplicate calls without another command");
        }
        check(repeated.powers == 1, "power duplicates never send or keep extending the native fade");
        repeated.snapshot(50, 4000, 100 + QUIET);
        check("automatic".equals(repeated.control.state(repeated.lease(), true)),
                "a power-only transition confirms at its original deadline despite late repeated ticks");
    }

    private static void transitionBounds() {
        Fixture f = new Fixture();
        check(f.transition(0, 9000, 100), "out-of-range desired values are safely clamped");
        check(f.sent.size() == 1 && f.sent.get(0).brightness == 1 && f.sent.get(0).ct == 6500,
                "registered and sent targets use the same protocol bounds");
        f.snapshot(1, 6500, 100 + QUIET);
        check(!f.control.manual() && "automatic".equals(f.control.state(f.lease(), true)),
                "the clamped device reply completes the fade rather than appearing manual");
    }

    private static void sceneBrightnessTargets() {
        for (int picture : new int[]{1, 15, 30, 80, 100}) {
            check(ScenePreset.mainBrightness("game", true, true, picture) == picture,
                    "game enters the actual linked picture target without a preset-30 detour");
            check(ScenePreset.mainBrightness(null, true, true, picture) == picture,
                    "an unbound source uses the linked picture target");
            check(ScenePreset.mainBrightness("cinema", true, true, picture) == picture,
                    "a linked screen scene uses the same picture brightness target");
            check(ScenePreset.mainBrightness("office", true, true, picture) == 80,
                    "office desk illumination keeps its fixed mode level even when linking is configured");
            check(ScenePreset.mainBrightness("night", true, true, picture) == 15,
                    "night desk illumination keeps its fixed mode level even when linking is configured");
        }
        for (String preset : new String[]{null, "game", "cinema"}) {
            check(ScenePreset.mainBrightness(preset, true, false, 80) == null,
                    "a linked screen scene waits for its first valid picture rather than fading to a stale fallback");
        }
        check(ScenePreset.mainBrightness("game", false, false, 1) == 30,
                "game without picture linking uses its fixed scene brightness");
        check(ScenePreset.mainBrightness("office", false, false, 1) == 80,
                "office fixed illumination does not require a screen sample");
        check(ScenePreset.mainBrightness("night", false, false, 1) == 15,
                "night fixed illumination does not require a screen sample");
        check(ScenePreset.mainBrightness("off", true, true, 100) == null,
                "an off scene never schedules a brightness fade");
        check(ScenePreset.mainBrightness("cinema", false, true, 100) == null,
                "an unlinked cinema scene leaves main brightness alone");
    }

    private static void nativeCommandDurations() throws Exception {
        JSONArray brightness = new JSONObject(YeelightAmbient.mainBrightnessCommand(1, 42, "t")).getJSONArray("params");
        check(brightness.getInt(0) == 42 && "smooth".equals(brightness.getString(1))
                && brightness.getInt(2) == 5000, "native front brightness uses a five-second smooth transition");
        check(new JSONObject(YeelightAmbient.mainBrightnessCommand(1, 0, "t")).getJSONArray("params").getInt(0) == 1,
                "smooth brightness retains its one-percent floor");
        check(new JSONObject(YeelightAmbient.mainBrightnessCommand(1, 999, "t")).getJSONArray("params").getInt(0) == 100,
                "smooth brightness retains its hundred-percent ceiling");
        JSONArray ct = new JSONObject(YeelightAmbient.mainCtCommand(2, 2700, "t")).getJSONArray("params");
        check(ct.getInt(0) == 2700 && "smooth".equals(ct.getString(1)) && ct.getInt(2) == 5000,
                "native CT transitions share the five-second scene duration");
        for (boolean on : new boolean[]{false, true}) {
            JSONArray power = new JSONObject(YeelightAmbient.mainPowerCommand(3, on, "t")).getJSONArray("params");
            check((on ? "on" : "off").equals(power.getString(0))
                    && "smooth".equals(power.getString(1)) && power.getInt(2) == 5000,
                    "native front power fades smoothly in both directions");
        }
        for (String command : new String[]{YeelightAmbient.ambientColorCommand(4, 0x123456, "t"),
                YeelightAmbient.ambientBrightnessCommand(5, 50, "t")}) {
            JSONArray params = new JSONObject(command).getJSONArray("params");
            check("sudden".equals(params.getString(1)) && params.getInt(2) == 0,
                    "screen-colour backlight commands retain their frame response");
        }
    }

    public static void main(String[] args) throws Exception {
        completeReadbackRequired(); bothDirectionsAndStableDeadline(); unchangedFieldsAndAtomicIntent();
        lateSnapshotsAndManualPriority(); sourceLeasesAndReconnects(); powerFadeAndManualPower();
        transitionBounds(); sceneBrightnessTargets(); nativeCommandDurations();
        System.out.println("YeelightTransitionContractTest PASS (" + checks + " checks, no network or device access)");
    }
}
