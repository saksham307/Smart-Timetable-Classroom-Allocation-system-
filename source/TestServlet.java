package com.timetable.servlet;

import com.timetable.engine.BankersAlgorithm;
import com.timetable.engine.ConflictException;
import com.timetable.engine.GraphUtil;
import com.timetable.engine.SchedulingEngine;
import com.timetable.util.ApiServlet;
import com.timetable.util.DBUtil;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

/**
 * GET /api/tests/run - the "Testing and Evaluation" goal of the proposal as a live page:
 * conflict-detection accuracy, mutual exclusion under concurrent admins, DB-constraint safety net,
 * Banker's algorithm, deadlock detection, scheduling completeness / response time, data integrity.
 * Temporary rows are named TST-... and always removed again.
 */
@WebServlet("/api/tests/run")
public class TestServlet extends ApiServlet {

    private final SchedulingEngine engine = new SchedulingEngine();

    private interface T { String run() throws Exception; }   // returns detail text, throws AssertionError on failure

    private final List<Map<String, Object>> results = new ArrayList<>();

    private void test(String category, String name, T t) {
        Map<String, Object> m = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        try {
            String detail = t.run();
            m.put("passed", true);
            m.put("detail", detail);
        } catch (AssertionError e) {
            m.put("passed", false);
            m.put("detail", e.getMessage());
        } catch (Throwable e) {
            m.put("passed", false);
            m.put("detail", "Error: " + e);
        }
        m.put("category", category);
        m.put("name", name);
        m.put("ms", (System.nanoTime() - t0) / 1_000_000.0);
        results.add(m);
    }

    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    @Override
    protected Object get(Req r) throws Exception {
        synchronized (results) {
            results.clear();
            test("OS - Banker's", "Textbook safety check gives safe sequence <P1,P3,P4,P0,P2>", this::bankersSafety);
            test("OS - Banker's", "Request P1[1,0,2] granted; P4[3,3,0] must wait; P0[0,2,0] denied as UNSAFE", this::bankersRequests);
            test("OS - Deadlock", "Wait-for graph: 3-cycle detected, acyclic graph reports none", this::deadlockGraph);
            test("DBMS - Conflicts", "Conflict detection accuracy (room / faculty / section / unavailable / clean case)", this::conflictAccuracy);
            test("DBMS - Rules", "Fixed breaks stop two-period labs from straddling a break", this::breakRule);
            test("OS - Mutual exclusion", "8 admins book the SAME room+period at once with the lock: exactly 1 wins", this::lockTest);
            test("DBMS - Constraints", "Same race WITHOUT the lock: MySQL UNIQUE keys still keep exactly 1 row", this::noLockTest);
            test("Scheduling", "Dry-run generation of every section: all required periods placed", this::completeness);
            test("Scheduling", "Conflict-check throughput (500 checks)", this::throughput);
            test("DBMS - Integrity", "Audit of the live timetable (no double booking, no class in a break, labs = 2 linked rows)", this::audit);
        }
        int passed = 0;
        for (Map<String, Object> m : results) if ((Boolean) m.get("passed")) passed++;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tests", new ArrayList<>(results));
        out.put("passed", passed);
        out.put("total", results.size());
        out.put("accuracy", results.isEmpty() ? 0 : Math.round(1000.0 * passed / results.size()) / 10.0);
        return out;
    }

    // ------------------------------------------------------------------ OS tests
    private static final int[][] MAX = {{7, 5, 3}, {3, 2, 2}, {9, 0, 2}, {2, 2, 2}, {4, 3, 3}};
    private static final int[][] ALLOC = {{0, 1, 0}, {2, 0, 0}, {3, 0, 2}, {2, 1, 1}, {0, 0, 2}};

    private String bankersSafety() {
        BankersAlgorithm.SafetyResult s = new BankersAlgorithm(new int[]{3, 3, 2}, MAX, ALLOC, true).isSafe();
        check(s.safe, "expected a SAFE state");
        check(s.sequence.equals(Arrays.asList(1, 3, 4, 0, 2)), "sequence was " + s.sequence);
        return "Safe sequence " + s.sequence;
    }

    private String bankersRequests() {
        BankersAlgorithm b = new BankersAlgorithm(new int[]{3, 3, 2}, MAX, ALLOC, true);
        check(b.request(1, new int[]{1, 0, 2}).granted, "P1 request should be granted");
        BankersAlgorithm.RequestResult wait = b.request(4, new int[]{3, 3, 0});
        check(!wait.granted && wait.reason.contains("not available"), "P4 should have to wait");
        BankersAlgorithm.RequestResult unsafe = b.request(0, new int[]{0, 2, 0});
        check(!unsafe.granted && unsafe.reason.contains("UNSAFE"), "P0 should be denied as unsafe");
        return "granted / wait / unsafe-denied, as in the textbook";
    }

    private String deadlockGraph() {
        Map<Integer, List<Integer>> g = new HashMap<>();
        g.put(1, Arrays.asList(2));
        g.put(2, Arrays.asList(3));
        g.put(3, Arrays.asList(1));
        g.put(4, Arrays.asList(1));
        List<List<Integer>> sets = GraphUtil.deadlockedSets(g);
        check(sets.size() == 1 && sets.get(0).equals(Arrays.asList(1, 2, 3)), "cycle set was " + sets);
        Map<Integer, List<Integer>> chain = new HashMap<>();
        chain.put(1, Arrays.asList(2));
        chain.put(2, Arrays.asList(3));
        check(GraphUtil.deadlockedSets(chain).isEmpty(), "a chain must not be reported as deadlock");
        return "cycle {1,2,3} found; chain 1->2->3 is not a deadlock";
    }

    // ------------------------------------------------------------------ fixtures
    private static class Fx {
        long f1, f2, r1, r2, s1, s2, sub1, sub2, sub3;
        int slot;
    }

    private Fx fixtures(Connection c) throws Exception {
        String tag = String.valueOf(System.nanoTime() % 1000000);
        Fx x = new Fx();
        x.f1 = Db.insert(c, "INSERT INTO faculty(name) VALUES (?)", "TST-F1-" + tag);
        x.f2 = Db.insert(c, "INSERT INTO faculty(name) VALUES (?)", "TST-F2-" + tag);
        x.r1 = Db.insert(c, "INSERT INTO room(name,type,capacity) VALUES (?, 'Lecture', 10)", "TST-A" + tag);
        x.r2 = Db.insert(c, "INSERT INTO room(name,type,capacity) VALUES (?, 'Lecture', 10)", "TST-B" + tag);
        x.s1 = Db.insert(c, "INSERT INTO section(name,strength) VALUES (?, 10)", "TST-S1-" + tag);
        x.s2 = Db.insert(c, "INSERT INTO section(name,strength) VALUES (?, 10)", "TST-S2-" + tag);
        x.sub1 = Db.insert(c, "INSERT INTO subject(name,faculty_id,is_lab,periods_per_week) VALUES (?,?,0,1)", "TST-Sub1", x.f1);
        x.sub2 = Db.insert(c, "INSERT INTO subject(name,faculty_id,is_lab,periods_per_week) VALUES (?,?,0,1)", "TST-Sub2", x.f2);
        x.sub3 = Db.insert(c, "INSERT INTO subject(name,faculty_id,is_lab,periods_per_week) VALUES (?,?,0,1)", "TST-Sub3", x.f1);
        x.slot = Db.i(Db.one(c, "SELECT id FROM time_slot WHERE is_break = 0 ORDER BY id LIMIT 1").get("id"));
        return x;
    }

    private void cleanup(Fx x) {
        try (Connection c = DBUtil.getConnection()) {
            Db.update(c, "DELETE FROM section WHERE id IN (?,?)", x.s1, x.s2);      // cascades their entries
            Db.update(c, "DELETE FROM subject WHERE id IN (?,?,?)", x.sub1, x.sub2, x.sub3);
            Db.update(c, "DELETE FROM faculty WHERE id IN (?,?)", x.f1, x.f2);
            Db.update(c, "DELETE FROM room WHERE id IN (?,?)", x.r1, x.r2);
        } catch (Exception ignored) { }
    }

    private String conflictAccuracy() throws Exception {
        try (Connection c = DBUtil.getConnection()) {
            c.setAutoCommit(false);
            try {
                Fx x = fixtures(c);
                Db.update(c, "INSERT INTO timetable_entry(section_id,subject_id,faculty_id,room_id,time_slot_id) VALUES (?,?,?,?,?)",
                        x.s1, x.sub1, x.f1, x.r1, x.slot);
                int ok = 0, total = 0;
                SchedulingEngine.Conflict cf;

                cf = SchedulingEngine.checkCell(c, (int) x.s2, Arrays.asList((int) x.f2), (int) x.r1, x.slot, null);
                total++; if (cf != null && cf.type.equals("room")) ok++;
                cf = SchedulingEngine.checkCell(c, (int) x.s2, Arrays.asList((int) x.f1), (int) x.r2, x.slot, null);
                total++; if (cf != null && cf.type.equals("faculty")) ok++;
                cf = SchedulingEngine.checkCell(c, (int) x.s1, Arrays.asList((int) x.f2), (int) x.r2, x.slot, null);
                total++; if (cf != null && cf.type.equals("section")) ok++;
                cf = SchedulingEngine.checkCell(c, (int) x.s2, Arrays.asList((int) x.f2), (int) x.r2, x.slot, null);
                total++; if (cf == null) ok++;
                Db.update(c, "INSERT INTO faculty_unavailable(faculty_id,time_slot_id) VALUES (?,?)", x.f2, x.slot);
                cf = SchedulingEngine.checkCell(c, (int) x.s2, Arrays.asList((int) x.f2), (int) x.r2, x.slot, null);
                total++; if (cf != null && cf.type.equals("faculty")) ok++;
                check(ok == total, ok + " of " + total + " scenarios classified correctly");
                return ok + " / " + total + " scenarios classified correctly (100% accuracy)";
            } finally {
                c.rollback();                                   // nothing is kept
            }
        }
    }

    private String breakRule() throws Exception {
        try (Connection c = DBUtil.getConnection()) {
            SchedulingEngine.Model m = SchedulingEngine.load(c);
            int blocked = 0, linked = 0;
            for (SchedulingEngine.Slot s : m.slots) {
                if (s.brk) continue;
                if (s.end.equals("12:05") || s.end.equals("17:55")) { check(s.next == null, s.text() + " must not link to a following period"); blocked++; }
                else { check(s.next != null, s.text() + " should link to the next period"); linked++; }
            }
            return linked + " periods can start a 2-period lab, " + blocked + " cannot (lunch or end of day follows)";
        }
    }

    // ------------------------------------------------------------------ concurrency
    private String lockTest() throws Exception {
        final int N = 8;
        try (Connection c = DBUtil.getConnection()) {
            final Fx x;
            x = fixtures(c);
            try {
                ExecutorService pool = Executors.newFixedThreadPool(N);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> fs = new ArrayList<>();
                for (int i = 0; i < N; i++) {
                    fs.add(pool.submit(() -> {
                        go.await();
                        try {
                            engine.bookEntry((int) x.s1, (int) x.sub1, (int) x.r1, x.slot);
                            return "GRANTED";
                        } catch (ConflictException e) {
                            return "CONFLICT";
                        }
                    }));
                }
                go.countDown();
                int granted = 0, conflict = 0;
                for (Future<String> f : fs) { String s = f.get(30, TimeUnit.SECONDS); if (s.equals("GRANTED")) granted++; else conflict++; }
                pool.shutdown();
                int rows = Db.i(Db.one("SELECT COUNT(*) AS n FROM timetable_entry WHERE section_id = ?", x.s1).get("n"));
                check(granted == 1 && conflict == N - 1 && rows == 1, "granted=" + granted + ", refused=" + conflict + ", rows=" + rows);
                return N + " simultaneous requests -> 1 granted, " + conflict + " refused with a clean conflict message, 1 row stored";
            } finally {
                cleanup(x);
            }
        }
    }

    private String noLockTest() throws Exception {
        final int N = 8;
        try (Connection c = DBUtil.getConnection()) {
            final Fx x = fixtures(c);
            try {
                ExecutorService pool = Executors.newFixedThreadPool(N);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> fs = new ArrayList<>();
                for (int i = 0; i < N; i++)
                    fs.add(pool.submit(() -> { go.await(); return engine.bookSingleNoLock((int) x.s1, (int) x.sub1, (int) x.r1, x.slot, 80); }));
                go.countDown();
                int granted = 0, db = 0, other = 0;
                for (Future<String> f : fs) {
                    String s = f.get(30, TimeUnit.SECONDS);
                    if (s.equals("GRANTED")) granted++; else if (s.equals("DB_CONSTRAINT")) db++; else other++;
                }
                pool.shutdown();
                int rows = Db.i(Db.one("SELECT COUNT(*) AS n FROM timetable_entry WHERE section_id = ?", x.s1).get("n"));
                check(rows == 1 && granted == 1, "rows=" + rows + ", granted=" + granted);
                return "Without the lock " + (db + other) + " threads passed the check at the same time (race); MySQL UNIQUE keys rejected " + db +
                        " of them, exactly " + rows + " row stored";
            } finally {
                cleanup(x);
            }
        }
    }

    // ------------------------------------------------------------------ scheduling
    private String completeness() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> s : Db.query("SELECT id FROM section ORDER BY id")) ids.add(Db.i(s.get("id")));
        check(!ids.isEmpty(), "no sections");
        Map<String, Object> res = engine.generate(ids, true);
        int placed = Db.i(res.get("placed")), required = Db.i(res.get("required"));
        @SuppressWarnings("unchecked") List<String> failed = (List<String>) res.get("failed");
        check(failed.isEmpty(), placed + " of " + required + " placed; not placed: " + failed);
        return ids.size() + " sections, " + placed + " / " + required + " periods placed in " + res.get("millis") + " ms (dry run, nothing saved)";
    }

    private String throughput() throws Exception {
        try (Connection c = DBUtil.getConnection()) {
            c.setAutoCommit(false);
            try {
                Fx x = fixtures(c);
                long t0 = System.nanoTime();
                for (int i = 0; i < 500; i++)
                    SchedulingEngine.checkCell(c, (int) x.s1, Arrays.asList((int) x.f1), (int) x.r1, x.slot, null);
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                return "500 conflict checks in " + Math.round(ms) + " ms (" + String.format("%.2f", ms / 500) + " ms each)";
            } finally {
                c.rollback();
            }
        }
    }

    private String audit() throws Exception {
        int dupFaculty = Db.query("SELECT x.f AS f FROM (SELECT faculty_id AS f, time_slot_id AS s FROM timetable_entry UNION ALL " +
                "SELECT sf.faculty_id, te.time_slot_id FROM timetable_entry te JOIN subject_faculty sf ON sf.subject_id = te.subject_id) x " +
                "GROUP BY x.f, x.s HAVING COUNT(*) > 1").size();
        int inBreak = Db.i(Db.one("SELECT COUNT(*) AS n FROM timetable_entry te JOIN time_slot ts ON ts.id = te.time_slot_id WHERE ts.is_break = 1").get("n"));
        int badLabs = Db.query("SELECT group_id FROM timetable_entry WHERE group_id IS NOT NULL GROUP BY group_id HAVING COUNT(*) <> 2").size();
        int wrongRoom = Db.i(Db.one("SELECT COUNT(*) AS n FROM timetable_entry te JOIN subject s ON s.id = te.subject_id JOIN room r ON r.id = te.room_id " +
                "WHERE (s.is_lab = 1 AND r.type <> 'Lab') OR (s.is_lab = 0 AND r.type <> 'Lecture')").get("n"));
        int total = Db.i(Db.one("SELECT COUNT(*) AS n FROM timetable_entry").get("n"));
        check(dupFaculty == 0 && inBreak == 0 && badLabs == 0 && wrongRoom == 0,
                "faculty double-bookings=" + dupFaculty + ", classes in breaks=" + inBreak + ", broken lab pairs=" + badLabs + ", wrong room type=" + wrongRoom);
        return total + " entries audited: 0 faculty double-bookings, 0 classes in breaks, 0 broken lab pairs, 0 wrong room types";
    }
}
