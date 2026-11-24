package com.timetable.engine;

import com.timetable.util.ApiServlet.ApiException;
import com.timetable.util.DBUtil;
import com.timetable.util.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Re-allocation ("move this class to that room / period") requests.
 *
 *  - A request that can't be granted because the target cell is held by another class WAITS.
 *  - Waiting requests form a WAIT-FOR GRAPH:  class A -> class B  means "A waits for something B holds".
 *  - A cycle in that graph is a DEADLOCK (A waits for B, B waits for A ...). Nothing can ever move
 *    on its own, so we detect the cycle (Tarjan SCC) and report it instead of failing silently.
 *  - Recovery: break "hold and wait" by performing every move of the cycle ATOMICALLY in a single
 *    transaction (entries are first parked on non-allocatable break slots because MySQL checks
 *    UNIQUE keys row by row, not at commit time).
 */
public class MoveService {

    // ---------------------------------------------------------------- helpers
    private static Set<Integer> facultySet(Connection c, int subjectId, int entryFaculty) throws SQLException {
        Set<Integer> s = new HashSet<>(SchedulingEngine.facultyOf(c, subjectId));
        s.add(entryFaculty);
        return s;
    }

    private static Map<String, Object> entry(Connection c, int id) throws SQLException {
        return Db.one(c, "SELECT te.id AS id, te.section_id AS sectionId, te.subject_id AS subjectId, te.faculty_id AS facultyId, " +
                "te.room_id AS roomId, te.time_slot_id AS slotId, te.group_id AS groupId, s.is_lab AS isLab, s.name AS subject, " +
                "sec.name AS section, r.name AS room, r.type AS roomType, ts.day AS day, " +
                "TIME_FORMAT(ts.start_time,'%H:%i') AS st, TIME_FORMAT(ts.end_time,'%H:%i') AS et " +
                "FROM timetable_entry te JOIN subject s ON s.id = te.subject_id JOIN section sec ON sec.id = te.section_id " +
                "JOIN room r ON r.id = te.room_id JOIN time_slot ts ON ts.id = te.time_slot_id WHERE te.id = ?", id);
    }

    private static String label(Map<String, Object> e) {
        return "#" + e.get("id") + " " + e.get("subject") + " (" + e.get("section") + ", " + e.get("room") + " " + e.get("day") + " " + e.get("st") + ")";
    }

    /** Entries currently preventing `e` from moving to (room, slot): [{id, reason}] */
    private static List<Map<String, Object>> blockers(Connection c, Map<String, Object> e, int targetRoom, int targetSlot,
                                                      Set<Integer> ignore) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> myFac = facultySet(c, Db.i(e.get("subjectId")), Db.i(e.get("facultyId")));
        for (Map<String, Object> o : Db.query(c, "SELECT id, section_id, room_id, subject_id, faculty_id FROM timetable_entry WHERE time_slot_id = ? AND id <> ?",
                targetSlot, e.get("id"))) {
            int oid = Db.i(o.get("id"));
            if (ignore != null && ignore.contains(oid)) continue;
            String reason = null;
            if (Db.i(o.get("room_id")) == targetRoom) reason = "holds the target room";
            else if (Db.i(o.get("section_id")) == Db.i(e.get("sectionId"))) reason = "same section is busy in that period";
            else {
                Set<Integer> theirs = facultySet(c, Db.i(o.get("subject_id")), Db.i(o.get("faculty_id")));
                for (int f : myFac) if (theirs.contains(f)) { reason = "teaches in that period (shared faculty)"; break; }
            }
            if (reason != null) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("id", oid);
                b.put("reason", reason);
                out.add(b);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- submit
    /** Developer / OS-lab entry point: queue the request and leave any deadlock visible. */
    public Map<String, Object> submit(int entryId, int roomId, int slotId) throws SQLException {
        return submit(entryId, roomId, slotId, false);
    }

    /**
     * Day-to-day entry point ("Move a class"). If the request cannot be granted it waits; if waiting
     * requests now form a circular wait (deadlock) it is detected and resolved automatically with an
     * atomic multi-move, so the administrator never has to know any of this is happening.
     */
    public Map<String, Object> submit(int entryId, int roomId, int slotId, boolean autoResolve) throws SQLException {
        Map<String, Object> row = submitInternal(entryId, roomId, slotId);
        boolean resolved = false;
        if (autoResolve) {
            synchronized (SchedulingEngine.LOCK) {
                if (Boolean.TRUE.equals(analyze().get("deadlocked"))) {
                    @SuppressWarnings("unchecked")
                    List<String> done = (List<String>) resolve().get("resolved");
                    resolved = !done.isEmpty();
                    try (Connection c = DBUtil.getConnection()) {
                        row = requestRow(c, Db.i(row.get("id")));
                    }
                }
            }
        }
        row.put("autoResolved", resolved);
        return row;
    }

    private Map<String, Object> submitInternal(int entryId, int roomId, int slotId) throws SQLException {
        synchronized (SchedulingEngine.LOCK) {
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Map<String, Object> e = entry(c, entryId);
                    if (e == null) throw new ApiException(404, "Class entry not found.");
                    if (Db.b(e.get("isLab"))) throw new ApiException(400, "Practical sessions span two linked periods and can't be moved with this tool.");
                    Map<String, Object> room = Db.one(c, "SELECT id, type FROM room WHERE id = ?", roomId);
                    Map<String, Object> slot = Db.one(c, "SELECT id, is_break AS brk, label FROM time_slot WHERE id = ?", slotId);
                    if (room == null || slot == null) throw new ApiException(404, "Target room or period not found.");
                    if (Db.b(slot.get("brk"))) throw new ApiException(400, Db.s(slot.get("label")) + " is a fixed break; classes can't be moved there.");
                    if (!room.get("type").equals(e.get("roomType"))) throw new ApiException(400, "Target room type differs from the current room type.");
                    if (Db.i(e.get("roomId")) == roomId && Db.i(e.get("slotId")) == slotId) throw new ApiException(400, "The class is already there.");
                    Set<Integer> fac = facultySet(c, Db.i(e.get("subjectId")), Db.i(e.get("facultyId")));
                    for (int f : fac) {
                        if (Db.one(c, "SELECT 1 AS x FROM faculty_unavailable WHERE faculty_id = ? AND time_slot_id = ?", f, slotId) != null)
                            throw new ApiException(400, "A teacher of this class has marked the target period as unavailable.");
                    }
                    Db.update(c, "UPDATE move_request SET status='CANCELLED' WHERE entry_id = ? AND status='WAITING'", entryId);
                    long id = Db.insert(c, "INSERT INTO move_request(entry_id,target_room_id,target_slot_id) VALUES (?,?,?)", entryId, roomId, slotId);
                    retryWaiting(c);
                    c.commit();
                    return requestRow(c, (int) id);
                } catch (SQLException | RuntimeException ex) {
                    c.rollback();
                    throw ex;
                }
            }
        }
    }

    private static Map<String, Object> requestRow(Connection c, int id) throws SQLException {
        Map<String, Object> r = Db.one(c, "SELECT mr.id AS id, mr.entry_id AS entryId, mr.status AS status, r.name AS targetRoom, " +
                "ts.day AS day, TIME_FORMAT(ts.start_time,'%H:%i') AS st, TIME_FORMAT(ts.end_time,'%H:%i') AS et " +
                "FROM move_request mr JOIN room r ON r.id = mr.target_room_id JOIN time_slot ts ON ts.id = mr.target_slot_id WHERE mr.id = ?", id);
        return r;
    }

    /** Wake up waiting requests whose blockers are gone, until nothing changes (like waking blocked processes). */
    private void retryWaiting(Connection c) throws SQLException {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map<String, Object> req : Db.query(c, "SELECT id, entry_id, target_room_id, target_slot_id FROM move_request WHERE status='WAITING' ORDER BY id")) {
                Map<String, Object> e = entry(c, Db.i(req.get("entry_id")));
                if (e == null) continue;
                int tr = Db.i(req.get("target_room_id")), ts = Db.i(req.get("target_slot_id"));
                if (blockers(c, e, tr, ts, null).isEmpty()) {
                    Db.update(c, "UPDATE timetable_entry SET room_id = ?, time_slot_id = ? WHERE id = ?", tr, ts, e.get("id"));
                    Db.update(c, "UPDATE move_request SET status='GRANTED' WHERE id = ?", req.get("id"));
                    announce(c, e, tr, ts);
                    progress = true;
                }
            }
        }
    }

    private void announce(Connection c, Map<String, Object> before, int roomId, int slotId) throws SQLException {
        Map<String, Object> now = entry(c, Db.i(before.get("id")));
        if (now == null) return;
        Db.update(c, "INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                "Class moved: " + now.get("subject") + " (" + now.get("section") + ")",
                now.get("subject") + " for " + now.get("section") + " moved from " + before.get("room") + " " + before.get("day") + " " + before.get("st") +
                        " to " + now.get("room") + " " + now.get("day") + " " + now.get("st") + ".",
                "all", "change", "system");
    }

    // ---------------------------------------------------------------- analysis
    public Map<String, Object> analyze() throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            List<Map<String, Object>> waiting = Db.query(c, "SELECT id, entry_id, target_room_id, target_slot_id FROM move_request WHERE status='WAITING' ORDER BY id");
            Map<Integer, List<Integer>> adj = new LinkedHashMap<>();
            List<Map<String, Object>> requests = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            Map<Integer, String> labels = new HashMap<>();

            for (Map<String, Object> req : waiting) {
                Map<String, Object> e = entry(c, Db.i(req.get("entry_id")));
                if (e == null) continue;
                int eid = Db.i(e.get("id"));
                labels.put(eid, label(e));
                Map<String, Object> room = Db.one(c, "SELECT name FROM room WHERE id = ?", req.get("target_room_id"));
                Map<String, Object> slot = Db.one(c, "SELECT day, TIME_FORMAT(start_time,'%H:%i') AS st FROM time_slot WHERE id = ?", req.get("target_slot_id"));
                List<Map<String, Object>> bl = blockers(c, e, Db.i(req.get("target_room_id")), Db.i(req.get("target_slot_id")), null);
                List<Integer> to = new ArrayList<>();
                List<String> blockedBy = new ArrayList<>();
                for (Map<String, Object> b : bl) {
                    int bid = Db.i(b.get("id"));
                    to.add(bid);
                    Map<String, Object> be = entry(c, bid);
                    labels.put(bid, label(be));
                    blockedBy.add(label(be) + " - " + b.get("reason"));
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", eid);
                    edge.put("to", bid);
                    edge.put("fromLabel", label(e));
                    edge.put("toLabel", label(be));
                    edge.put("reason", b.get("reason"));
                    edges.add(edge);
                }
                adj.put(eid, to);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", req.get("id"));
                row.put("entryId", eid);
                row.put("entry", label(e));
                row.put("wants", room.get("name") + " " + slot.get("day") + " " + slot.get("st"));
                row.put("blockedBy", blockedBy);
                requests.add(row);
            }

            List<Map<String, Object>> deadlocks = new ArrayList<>();
            for (List<Integer> set : GraphUtil.deadlockedSets(adj)) {
                List<Integer> cyc = GraphUtil.sampleCycle(adj, set);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < cyc.size(); k++) sb.append(k > 0 ? "  -->  " : "").append(labels.getOrDefault(cyc.get(k), "#" + cyc.get(k)));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("entries", set);
                d.put("cycle", cyc);
                d.put("description", sb.toString());
                deadlocks.add(d);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("waiting", requests);
            out.put("edges", edges);
            out.put("deadlocks", deadlocks);
            out.put("deadlocked", !deadlocks.isEmpty());
            return out;
        }
    }

    // ---------------------------------------------------------------- recovery
    public Map<String, Object> resolve() throws SQLException {
        synchronized (SchedulingEngine.LOCK) {
            Map<String, Object> analysis = analyze();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deadlocks = (List<Map<String, Object>>) analysis.get("deadlocks");
            List<String> resolved = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            if (deadlocks.isEmpty()) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("resolved", resolved);
                none.put("failed", failed);
                none.put("message", "No deadlock to resolve.");
                return none;
            }
            for (Map<String, Object> d : deadlocks) {
                @SuppressWarnings("unchecked")
                List<Integer> ids = (List<Integer>) d.get("entries");
                String err = atomicMove(ids);
                if (err == null) resolved.add(String.valueOf(d.get("description")));
                else failed.add(d.get("description") + "   [" + err + "]");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("resolved", resolved);
            out.put("failed", failed);
            out.put("message", failed.isEmpty() ? "Deadlock broken: all moves in the cycle were applied atomically." : "Some cycles could not be resolved.");
            return out;
        }
    }

    /** returns null on success, otherwise the reason it can't be done */
    private String atomicMove(List<Integer> entryIds) throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            c.setAutoCommit(false);
            try {
                Set<Integer> moving = new HashSet<>(entryIds);
                Map<Integer, int[]> target = new LinkedHashMap<>();       // entry -> {room, slot, requestId}
                Map<Integer, Map<String, Object>> rows = new HashMap<>();
                for (int id : entryIds) {
                    Map<String, Object> req = Db.one(c, "SELECT id, target_room_id, target_slot_id FROM move_request WHERE entry_id = ? AND status='WAITING' ORDER BY id DESC LIMIT 1", id);
                    if (req == null) { c.rollback(); return "entry #" + id + " has no waiting request"; }
                    target.put(id, new int[]{Db.i(req.get("target_room_id")), Db.i(req.get("target_slot_id")), Db.i(req.get("id"))});
                    rows.put(id, entry(c, id));
                }
                // 1. every target must be free of NON-moving entries
                for (int id : entryIds) {
                    List<Map<String, Object>> bl = blockers(c, rows.get(id), target.get(id)[0], target.get(id)[1], moving);
                    if (!bl.isEmpty()) { c.rollback(); return "target of #" + id + " is also blocked by an outside class (#" + bl.get(0).get("id") + ")"; }
                }
                // 2. the moving entries must not collide with each other in their final positions
                for (int a : entryIds) for (int b : entryIds) {
                    if (a >= b) continue;
                    int[] ta = target.get(a), tb = target.get(b);
                    if (ta[1] != tb[1]) continue;
                    Set<Integer> fa = facultySet(c, Db.i(rows.get(a).get("subjectId")), Db.i(rows.get(a).get("facultyId")));
                    Set<Integer> fb = facultySet(c, Db.i(rows.get(b).get("subjectId")), Db.i(rows.get(b).get("facultyId")));
                    boolean shared = false;
                    for (int f : fa) if (fb.contains(f)) shared = true;
                    if (ta[0] == tb[0] || shared || Db.i(rows.get(a).get("sectionId")) == Db.i(rows.get(b).get("sectionId")))
                        { c.rollback(); return "final positions of #" + a + " and #" + b + " would clash"; }
                }
                // 3. park everything on distinct break slots, then place for real (one transaction)
                List<Map<String, Object>> parks = Db.query(c, "SELECT id FROM time_slot WHERE is_break = 1 ORDER BY id LIMIT " + entryIds.size());
                if (parks.size() < entryIds.size()) { c.rollback(); return "cycle too large to park"; }
                int k = 0;
                for (int id : entryIds) Db.update(c, "UPDATE timetable_entry SET time_slot_id = ? WHERE id = ?", parks.get(k++).get("id"), id);
                for (int id : entryIds) {
                    int[] t = target.get(id);
                    Db.update(c, "UPDATE timetable_entry SET room_id = ?, time_slot_id = ? WHERE id = ?", t[0], t[1], id);
                    Db.update(c, "UPDATE move_request SET status='GRANTED' WHERE id = ?", t[2]);
                }
                Db.update(c, "INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                        "Classes swapped between rooms",
                        entryIds.size() + " classes were swapped between rooms/periods together, because each was waiting for a place another one held.",
                        "all", "change", "system");
                retryWaiting(c);
                c.commit();
                return null;
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    // ---------------------------------------------------------------- misc
    public void cancel(int id) throws SQLException {
        Db.update("UPDATE move_request SET status='CANCELLED' WHERE id = ? AND status='WAITING'", id);
    }

    public List<Map<String, Object>> history() throws SQLException {
        return Db.query("SELECT mr.id AS id, mr.status AS status, s.name AS subject, sec.name AS section, r.name AS targetRoom, " +
                "ts.day AS day, TIME_FORMAT(ts.start_time,'%H:%i') AS st, mr.created_at AS createdAt " +
                "FROM move_request mr JOIN timetable_entry te ON te.id = mr.entry_id JOIN subject s ON s.id = te.subject_id " +
                "JOIN section sec ON sec.id = te.section_id JOIN room r ON r.id = mr.target_room_id " +
                "JOIN time_slot ts ON ts.id = mr.target_slot_id ORDER BY mr.id DESC LIMIT 15");
    }

    /** Builds a ready-made circular wait: two classes in the same period each want the other's room. */
    public Map<String, Object> demo() throws SQLException {
        Map<String, Object> pair;
        try (Connection c = DBUtil.getConnection()) {
            pair = Db.one(c, "SELECT a.id AS aId, b.id AS bId, a.room_id AS aRoom, b.room_id AS bRoom, a.time_slot_id AS slot " +
                    "FROM timetable_entry a JOIN timetable_entry b ON a.time_slot_id = b.time_slot_id AND a.id < b.id AND a.room_id <> b.room_id " +
                    "JOIN subject sa ON sa.id = a.subject_id JOIN subject sb ON sb.id = b.subject_id " +
                    "JOIN room ra ON ra.id = a.room_id JOIN room rb ON rb.id = b.room_id " +
                    "WHERE sa.is_lab = 0 AND sb.is_lab = 0 AND ra.type = 'Lecture' AND rb.type = 'Lecture' ORDER BY a.id LIMIT 1");
        }
        if (pair == null)
            throw new ApiException(400, "Generate timetables for at least two sections first: a deadlock needs two lecture classes in the same period in different rooms.");
        submit(Db.i(pair.get("aId")), Db.i(pair.get("bRoom")), Db.i(pair.get("slot")));
        submit(Db.i(pair.get("bId")), Db.i(pair.get("aRoom")), Db.i(pair.get("slot")));
        return analyze();
    }
}
