package net.xhfo.tvwled;

import org.json.JSONObject;

/** Host-only ownership contracts; no lamp, network, Android service or shared build directory. */
public final class YeelightMainControlContractTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Store implements YeelightMainControl.Store {
        String disk = "", last = "";
        boolean succeeds = true, throwsError;
        int saves;
        @Override public boolean save(String value) {
            saves++; last = value;
            if (throwsError) throw new IllegalStateException("synthetic disk failure");
            if (succeeds) disk = value;
            return succeeds;
        }
    }
    private static final class Fixture {
        final Store store;
        final YeelightMainControl control;
        int sends;
        Fixture() { this(new Store(), ""); }
        Fixture(Store store, String saved) {
            this.store = store;
            control = new YeelightMainControl(saved, store);
        }
        long lease() { return control.lease(); }
        void prepare() {
            control.selectAddress("192.168.1.60");
            control.source("30", true, true);
            control.observe(lease(), 50, 4000, true, 0);
        }
        boolean brightness(int value, long now) {
            return control.brightness(lease(), value, now, () -> true, () -> sends++);
        }
        boolean ct(int value, long now) {
            return control.ct(lease(), value, now, () -> true, () -> sends++);
        }
        void bothBlocked(long now, String why) {
            int before = sends;
            check(!brightness(60, now), why + ": brightness is not written");
            check(!ct(4500, now), why + ": CT is not written");
            check(sends == before, why + ": no transport action is invoked");
        }
        void manualBrightness() {
            control.observe(lease(), 33, null, false, 100);
            control.observe(lease(), 33, 4000, true, 2000);
            check(control.manual(), "fixture enters manual priority from changed brightness");
        }
    }

    private static void readiness() {
        Fixture f = new Fixture();
        f.control.selectAddress("192.168.1.60");
        check(!f.control.source("30", true, true), "first known source establishes a baseline");
        check("observing".equals(f.control.state(f.lease(), false)), "unread lamp is observing");
        f.bothBlocked(0, "unread lamp");
        f.control.observe(f.lease(), 50, null, false, 10);
        f.bothBlocked(10, "one property is insufficient");
        f.control.observe(f.lease(), null, 4000, false, 11);
        f.bothBlocked(11, "two independent notifications still need one complete snapshot");
        check("observing".equals(f.control.state(f.lease(), true)), "notifications cannot impersonate a full readback");
        f.control.observe(f.lease(), 50, 4000, true, 20);
        check("automatic".equals(f.control.state(f.lease(), true)), "complete initial snapshot permits automatic control");
        check(!f.control.brightness(f.lease(), 60, 21, () -> false, () -> f.sends++),
                "a stale or disconnected monitor vetoes brightness despite a stored baseline");
        check(!f.control.ct(f.lease(), 4500, 21, () -> false, () -> f.sends++),
                "a stale or disconnected monitor vetoes CT");
        check(f.sends == 0, "readiness veto does not invoke transport");
        check(f.brightness(60, 30), "brightness resumes when readback is fresh");
        check(f.ct(4500, 31), "CT resumes when readback is fresh");
        check(f.sends == 2, "only permitted actions are sent");
        check("observing".equals(f.control.state(f.lease(), false)), "stale readback is visible in state");
    }

    private static void ownCommands() {
        Fixture f = new Fixture(); f.prepare();
        final long lease = f.lease();
        check(f.control.brightness(lease, 20, 100, () -> true, () -> {
            f.sends++;
            f.control.observe(lease, 20, null, false, 100);
        }), "own intent is registered before a synchronous fast notification");
        check(!f.control.manual() && "automatic".equals(f.control.state(lease, true)),
                "own sudden brightness echo does not count as manual");
        check(f.brightness(30, 200), "the next automatic brightness is allowed");
        f.control.observe(lease, 20, null, false, 210);
        check("verifying".equals(f.control.state(lease, true)), "a delayed old own echo opens a quiet verification window");
        f.bothBlocked(211, "verification pauses both properties");
        f.control.observe(lease, 30, null, false, 220);
        check("verifying".equals(f.control.state(lease, true)), "a later own notification cannot prematurely clear suspicion");
        f.control.observe(lease, 30, 4000, true, 200 + YeelightMainControl.SETTLE_MS - 1);
        check(!f.control.manual() && "verifying".equals(f.control.state(lease, true)),
                "an early full readback waits for the complete settling period");
        f.control.observe(lease, 30, 4000, true, 200 + YeelightMainControl.SETTLE_MS);
        check(!f.control.manual() && "automatic".equals(f.control.state(lease, true)),
                "a correct settled readback clears the delayed own echo");
        check(f.ct(2700, 2000), "CT transition begins after verification");
        f.control.observe(lease, null, 3500, false, 2100);
        check("verifying".equals(f.control.state(lease, true)), "smooth CT intermediate pauses writes without yielding ownership");
        f.bothBlocked(2200, "CT transition verification");
        f.control.observe(lease, null, 2700, false, 2500);
        check("verifying".equals(f.control.state(lease, true)), "CT target notification alone does not bypass verification");
        f.control.observe(lease, 30, 2700, true, 2000 + YeelightMainControl.SETTLE_MS);
        check(!f.control.manual() && "automatic".equals(f.control.state(lease, true)),
                "500ms smooth CT reaches its target and retains automatic ownership");
        int before = f.store.saves;
        f.control.observe(lease, 30, 2700, true, 10000);
        check(f.store.saves == before, "ordinary own readback does not write the persistent store");
    }

    private static void externalChanges() throws Exception {
        Fixture f = new Fixture(); f.prepare();
        f.control.observe(f.lease(), 33, null, false, 100);
        check(!f.control.manual() && "verifying".equals(f.control.state(f.lease(), true)),
                "changed brightness immediately suspends writes before confirmation");
        f.bothBlocked(110, "external brightness notification");
        f.control.observe(f.lease(), null, 4000, false, 120);
        check("verifying".equals(f.control.state(f.lease(), true)), "unchanged CT cannot erase brightness suspicion");
        f.control.observe(f.lease(), 33, 4000, true, 1000);
        check(f.control.manual(), "full readback confirms manual brightness when no own command is settling");
        check(new JSONObject(f.store.disk).getBoolean("manual"), "manual priority is durably saved");
        check("30".equals(new JSONObject(f.store.disk).getString("source")), "saved manual priority belongs to the active source");
        f.bothBlocked(2000, "confirmed manual brightness");
        int before = f.store.saves;
        f.control.observe(f.lease(), 50, 4000, true, 3000);
        check(f.control.manual() && f.store.saves == before, "later readings cannot reclaim manual ownership or cause repeated writes");

        Fixture ct = new Fixture(); ct.prepare();
        ct.control.observe(ct.lease(), null, 2700, false, 100);
        ct.bothBlocked(110, "external CT notification");
        ct.control.observe(ct.lease(), 50, 2700, true, 1000);
        check(ct.control.manual(), "changing only CT also yields both properties");

        Fixture overlap = new Fixture(); overlap.prepare();
        check(overlap.brightness(20, 100), "own command precedes external adjustment");
        overlap.control.observe(overlap.lease(), 24, null, false, 110);
        overlap.bothBlocked(120, "external change during own settle");
        overlap.control.observe(overlap.lease(), 24, 4000, true, 100 + YeelightMainControl.SETTLE_MS - 1);
        check(!overlap.control.manual(), "an in-flight own command gets its settling allowance");
        overlap.control.observe(overlap.lease(), 24, 4000, true, 100 + YeelightMainControl.SETTLE_MS);
        check(overlap.control.manual(), "persistent external level after settling yields ownership");

        Fixture previous = new Fixture(); previous.prepare();
        check(previous.brightness(20, 100), "automatic control moves away from the original brightness");
        previous.control.observe(previous.lease(), 20, null, false, 110);
        previous.control.observe(previous.lease(), 50, null, false, 120);
        previous.bothBlocked(130, "manual return to an earlier own value");
        previous.control.observe(previous.lease(), 50, 4000, true, 100 + YeelightMainControl.SETTLE_MS);
        check(previous.control.manual(), "a manual value cannot be ignored merely because it was recently owned");
    }

    private static void lateSnapshots() {
        Fixture f = new Fixture(); f.prepare();
        check(f.brightness(20, 100), "automatic command follows the query's sample time");
        // The service supplies receiveTime - queryAge, not the much later arrival time.
        long receivedAt = 3000, queryAge = receivedAt - 90;
        f.control.observe(f.lease(), 50, 4000, true, receivedAt - queryAge);
        check(!f.control.manual(), "late pre-command snapshot cannot confirm a manual override");
        check("verifying".equals(f.control.state(f.lease(), true)),
                "late old values may conservatively pause writes until a fresh query");
        f.bothBlocked(receivedAt, "late pre-command snapshot verification");
        f.control.observe(f.lease(), 20, 4000, true, 90);
        check(!f.control.manual() && "verifying".equals(f.control.state(f.lease(), true)),
                "a stale full snapshot matching the new target cannot prematurely clear suspicion");
        f.control.observe(f.lease(), 20, 4000, true, 100 + YeelightMainControl.SETTLE_MS - 1);
        check(!f.control.manual() && "verifying".equals(f.control.state(f.lease(), true)),
                "query must start after the settling interval, regardless of its later arrival");
        f.control.observe(f.lease(), 20, 4000, true, 1300);
        check(!f.control.manual() && "automatic".equals(f.control.state(f.lease(), true)),
                "a fresh settled target readback resumes automatic ownership");
        check(f.brightness(30, 3100), "automatic control resumes after the fresh settled readback");
    }

    private static void lifecycleAndSources() throws Exception {
        Fixture original = new Fixture(); original.prepare(); original.manualBrightness();
        Fixture restored = new Fixture(new Store(), original.store.disk);
        check(restored.control.manual(), "process recovery restores manual priority");
        long lease = restored.lease();
        restored.control.selectAddress("192.168.1.60");
        check(restored.lease() == lease && restored.control.manual(), "same lamp config reload preserves priority and lease");
        restored.control.selectAddress(null); restored.control.selectAddress("");
        check(restored.lease() == lease && restored.control.manual(), "empty or disabled lamp config does not discard priority");
        check(!restored.control.source("30", true, true), "same source is not a switch");
        check(!restored.control.source(null, false, true), "missing source is not a switch");
        check(!restored.control.source("29", false, true), "unknown source ID is not a switch");
        check(!restored.control.source("29", true, false), "standby source transitions are not a switch");
        check(!restored.control.source("", true, true), "empty source is not a switch");
        check(restored.control.manual() && restored.lease() == lease, "same-source reloads, sleep and unknown readings preserve ownership");
        restored.bothBlocked(10000, "manual priority after recovery");
        check(restored.control.source("29", true, true), "a confirmed awake input change is a genuine switch");
        check(!restored.control.manual() && restored.lease() != lease, "real switch grants a new automatic lease even with the same preset");
        check(!new JSONObject(restored.store.disk).getBoolean("manual"), "source switch durably releases the manual override");
        check("29".equals(new JSONObject(restored.store.disk).getString("source")), "new source is saved");
        restored.bothBlocked(11000, "new source awaits a fresh lamp baseline");
        restored.control.observe(lease, 12, 1700, true, 12000);
        restored.bothBlocked(12001, "stale old-session snapshot cannot initialize the new lease");
        int sent = restored.sends;
        check(!restored.control.brightness(lease, 50, 12002, () -> true, () -> restored.sends++),
                "old-session brightness action is rejected");
        check(!restored.control.ct(lease, 4000, 12002, () -> true, () -> restored.sends++),
                "old-session CT action is rejected");
        check(restored.sends == sent, "old lease performs no transport action");
        restored.control.observe(restored.lease(), 33, 2700, true, 13000);
        check(restored.brightness(80, 13001) && restored.ct(4000, 13002),
                "new source can apply its automatic preset after fresh observation");
        long nextLease = restored.lease();
        check(!restored.control.source("29", true, true) && restored.lease() == nextLease,
                "repeated new-source polls do not restart automatic ownership");

        Fixture lamp = new Fixture(); lamp.prepare(); lamp.manualBrightness();
        long oldLamp = lamp.lease();
        lamp.control.selectAddress("192.168.1.61");
        check(!lamp.control.manual() && lamp.lease() != oldLamp, "a different physical lamp has its own ownership state");
        lamp.control.observe(oldLamp, 25, 2700, true, 3000);
        lamp.bothBlocked(3001, "old lamp notifications cannot initialize a different lamp");
        lamp.control.observe(lamp.lease(), 70, 5000, true, 4000);
        check(lamp.brightness(60, 4001), "new lamp becomes controllable after its own baseline");
    }

    private static void invalidObservations() {
        Fixture f = new Fixture(); f.prepare();
        for (Integer invalid : new Integer[]{null, -1, 0, 101, Integer.MAX_VALUE}) {
            f.control.observe(f.lease(), invalid, 2700, true, 10);
            check(!f.control.manual() && "automatic".equals(f.control.state(f.lease(), true)),
                    "invalid full brightness cannot partially introduce a changed CT");
        }
        for (Integer invalid : new Integer[]{null, -1, 1699, 6501, Integer.MAX_VALUE}) {
            f.control.observe(f.lease(), 33, invalid, true, 20);
            check(!f.control.manual() && "automatic".equals(f.control.state(f.lease(), true)),
                    "invalid full CT cannot partially introduce changed brightness");
        }
        f.control.observe(f.lease(), 0, 6501, false, 30);
        f.control.observe(f.lease(), null, null, false, 40);
        check("automatic".equals(f.control.state(f.lease(), true)), "invalid or empty notifications do not synthesize a change");
        f.control.observe(f.lease() + 1, 1, 1700, true, 50);
        check("automatic".equals(f.control.state(f.lease(), true)), "foreign-lease changes are ignored");
        check(f.brightness(-1, 100), "negative requested brightness is clamped");
        f.control.observe(f.lease(), 1, 4000, true, 100 + YeelightMainControl.SETTLE_MS);
        check(!f.control.manual(), "brightness minimum is registered consistently with transport");
        check(f.ct(10000, 2000), "high requested CT is clamped");
        f.control.observe(f.lease(), 1, 6500, true, 2000 + YeelightMainControl.SETTLE_MS);
        check(!f.control.manual(), "CT maximum is registered consistently with transport");
    }

    private static void persistenceFailures() throws Exception {
        Fixture f = new Fixture(); f.prepare();
        String oldDisk = f.store.disk;
        f.store.succeeds = false;
        f.manualBrightness();
        check(f.control.persistenceFailed() && f.store.disk.equals(oldDisk), "failed commit is exposed without claiming durable state");
        f.bothBlocked(3000, "manual priority remains active despite storage failure");
        int before = f.store.saves;
        f.control.source("30", true, true);
        check(f.store.saves == before + 1 && f.control.persistenceFailed(), "same-source polling retries a failed state commit");
        f.store.succeeds = true;
        f.control.source("30", true, true);
        check(!f.control.persistenceFailed() && new JSONObject(f.store.disk).getBoolean("manual"),
                "a later successful retry makes priority durable");
        before = f.store.saves;
        f.control.source("30", true, true);
        check(f.store.saves == before, "clean same-source polls do not continually commit");
        f.store.throwsError = true;
        check(f.control.source("29", true, true), "source switching remains bounded when storage throws");
        check(f.control.persistenceFailed(), "thrown storage failure is exposed");
        f.store.throwsError = false;
        f.control.source("29", true, true);
        check(!f.control.persistenceFailed() && !new JSONObject(f.store.disk).getBoolean("manual"),
                "clear-state persistence retries after a thrown error");
    }

    private static void savedStateValidation() throws Exception {
        String[] invalid = {"", "{", "[]", "null", "{}",
                "{\"address\":\"8.8.8.8\",\"source\":\"30\",\"manual\":true}",
                "{\"address\":\"192.168.1.60\",\"source\":\"30\",\"manual\":\"true\"}",
                new JSONObject().put("address", "192.168.1.60").put("source", new String(new char[81]).replace('\0', '3'))
                        .put("manual", true).toString()};
        for (String value : invalid) {
            Fixture f = new Fixture(new Store(), value);
            check(!f.control.manual(), "invalid ownership record is not restored");
            f.prepare();
            check("automatic".equals(f.control.state(f.lease(), true)), "invalid old record still permits fresh verified startup");
        }
        Fixture f = new Fixture();
        f.control.source("30", true, true);
        check(f.store.saves == 0, "no lamp-specific record is written before selecting a lamp");
        f.control.selectAddress("192.168.1.60");
        check("30".equals(new JSONObject(f.store.disk).getString("source")), "early source baseline is retained when the lamp is selected");
    }

    public static void main(String[] args) throws Exception {
        readiness();
        ownCommands();
        externalChanges();
        lateSnapshots();
        lifecycleAndSources();
        invalidObservations();
        persistenceFailures();
        savedStateValidation();
        System.out.println("YeelightMainControlContractTest PASS (" + checks + " checks, no network or device access)");
    }
}
