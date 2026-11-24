package com.timetable.engine;

import com.timetable.util.ApiServlet.ApiException;
import com.timetable.util.DBUtil;
import com.timetable.util.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class SchedulingEngine {

    public static final Object LOCK = new Object();

    public static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    public static final AtomicLong GRANTED = new AtomicLong();
    public static final AtomicLong DENIED = new AtomicLong();
    public static final AtomicLong UNSAFE_REJECTED = new AtomicLong();

    public static class Slot {
        public int id, dayIdx, order;
        public String day, start, end, label;
        public boolean brk;
        public Slot next;

        public String text() { return day + " " + start + "-" + end; }
    }

    public static class Room {
        public int id, capacity;
        public String name, type;
    }

    public static class Subj {
        public int id, ppw, facultyId;
        public String name;
        public boolean lab;
        public Integer courseId, year, semester;
        public Integer sectionId;
        public String jointGroup;
        public List<Integer> faculty = new ArrayList<>();
    }

    public static class Sec {
        public int id, strength;
        public String name;
        public Integer courseId, year, semester;
        public boolean saturday;
    }

    public static class Model {
        public List<Slot> slots = new ArrayList<>();
        public Map<Integer, Slot> slotById = new HashMap<>();
        public List<Room> rooms = new ArrayList<>();
        public Map<Integer, Subj> subjects = new LinkedHashMap<>();
        public Map<Integer, Sec> sections = new LinkedHashMap<>();
        public Map<Integer, String> facultyName = new HashMap<>();
        public Set<String> occ = new HashSet<>();
    }

    static class Placement {
        int sectionId, subjectId, facultyId, roomId, slotId;
        Integer groupId;
        int jointId;
    }

    static class Cand {
        Slot a, b;
        Room room;
        int score;
    }

    static class Session {
        Sec sec;
        Subj sub;
        int k;
        Cand cand;
        int state;

        List<String> period(int p) { return cellsFor(sec, sub, cand.room, p == 1 ? cand.a : cand.b); }

        List<String> claim() {
            List<String> l = new ArrayList<>(period(1));
            l.addAll(period(2));
            return l;
        }

        String label() { return sec.name + "/" + sub.name + (sub.ppw > 1 ? "#" + k : ""); }
    }

    static List<String> cellsFor(Sec sec, Subj sub, Room r, Slot t) {
        List<String> l = new ArrayList<>();
        l.add("R" + r.id + ":" + t.id);
        l.add("S" + sec.id + ":" + t.id);
        for (int f : sub.faculty) l.add("F" + f + ":" + t.id);
        return l;
    }

    private static boolean isCec(Subj s) {
        if (s == null || s.name == null) return false;
        String u = s.name.toUpperCase(Locale.ROOT);
        return u.contains("CEC") || u.contains("CAREER EXCELLENCE");
    }

    private static Set<Integer> cecTeachers(Subj sub) {
        Set<Integer> fids = new LinkedHashSet<>(sub.faculty);
        if (sub.facultyId > 0) fids.add(sub.facultyId);
        return fids;
    }

    private static boolean facHasCecOnDay(Map<String, Integer> facCecDay, Subj sub, String day) {
        for (int f : cecTeachers(sub)) {
            if (facCecDay.getOrDefault(f + ":" + day, 0) >= 1) return true;
        }
        return false;
    }

    private static void markFacCecOnDay(Map<String, Integer> facCecDay, Subj sub, String day) {
        for (int f : cecTeachers(sub)) {
            facCecDay.merge(f + ":" + day, 1, Integer::sum);
        }
    }

    private static List<String> cellsForTwo(Sec sec, Subj sub, Room r, Slot a, Slot b) {
        List<String> l = new ArrayList<>(cellsFor(sec, sub, r, a));
        l.addAll(cellsFor(sec, sub, r, b));
        return l;
    }

    static boolean matches(Subj s, Sec sec) {
        if (s.sectionId != null) return s.sectionId == sec.id;
        return Objects.equals(s.courseId, sec.courseId) && Objects.equals(s.year, sec.year) && Objects.equals(s.semester, sec.semester);
    }

    private static Integer nint(Object o) { return o == null ? null : Db.i(o); }

    static Integer effSem(Integer semester, Integer year) { return semester != null ? semester : (year != null ? Integer.valueOf(year * 2) : null); }

    public static Model load(Connection c) throws SQLException {
        Model m = new Model();

        Slot prev = null;
        int order = 0;
        Slot chain = null;
        for (Map<String, Object> r : Db.query(c,
                "SELECT id, day, TIME_FORMAT(start_time,'%H:%i') AS st, TIME_FORMAT(end_time,'%H:%i') AS et, is_break AS brk, label " +
                "FROM time_slot ORDER BY FIELD(day,'Mon','Tue','Wed','Thu','Fri','Sat'), start_time")) {
            Slot s = new Slot();
            s.id = Db.i(r.get("id"));
            s.day = Db.s(r.get("day"));
            s.start = Db.s(r.get("st"));
            s.end = Db.s(r.get("et"));
            s.brk = Db.b(r.get("brk"));
            s.label = Db.s(r.get("label"));
            s.order = order++;
            s.dayIdx = Arrays.asList(DAYS).indexOf(s.day);
            if (prev != null && !prev.day.equals(s.day)) { prev = null; chain = null; }
            if (s.brk) {
                if (!"Short Break".equals(s.label)) chain = null;
            } else {
                if (chain != null) chain.next = s;
                chain = s;
            }
            prev = s;
            m.slots.add(s);
            m.slotById.put(s.id, s);
        }

        for (Map<String, Object> r : Db.query(c, "SELECT id, name FROM faculty"))
            m.facultyName.put(Db.i(r.get("id")), Db.s(r.get("name")));

        for (Map<String, Object> r : Db.query(c, "SELECT id, name, type, capacity FROM room ORDER BY id")) {
            Room x = new Room();
            x.id = Db.i(r.get("id"));
            x.name = Db.s(r.get("name"));
            x.type = Db.s(r.get("type"));
            x.capacity = Db.i(r.get("capacity"));
            m.rooms.add(x);
        }

        for (Map<String, Object> r : Db.query(c,
                "SELECT id, name, faculty_id, is_lab, course_id, section_id, semester, joint_group, `year` AS yr, periods_per_week FROM subject ORDER BY id")) {
            Subj s = new Subj();
            s.id = Db.i(r.get("id"));
            s.name = Db.s(r.get("name"));
            s.facultyId = Db.i(r.get("faculty_id"));
            s.lab = Db.b(r.get("is_lab"));
            s.courseId = nint(r.get("course_id"));
            s.sectionId = nint(r.get("section_id"));
            s.jointGroup = r.get("joint_group") == null ? null : r.get("joint_group").toString();
            s.year = nint(r.get("yr"));
            s.semester = effSem(nint(r.get("semester")), s.year);
            s.ppw = Math.max(1, Db.i(r.get("periods_per_week")));
            s.faculty.add(s.facultyId);
            m.subjects.put(s.id, s);
        }
        for (Map<String, Object> r : Db.query(c, "SELECT subject_id, faculty_id FROM subject_faculty ORDER BY faculty_id")) {
            Subj s = m.subjects.get(Db.i(r.get("subject_id")));
            int f = Db.i(r.get("faculty_id"));
            if (s != null && !s.faculty.contains(f)) s.faculty.add(f);
        }

        for (Map<String, Object> r : Db.query(c, "SELECT id, name, course_id, semester, works_saturday AS sat, `year` AS yr, strength FROM section ORDER BY id")) {
            Sec s = new Sec();
            s.id = Db.i(r.get("id"));
            s.name = Db.s(r.get("name"));
            s.courseId = nint(r.get("course_id"));
            s.year = nint(r.get("yr"));
            s.semester = effSem(nint(r.get("semester")), s.year);
            s.saturday = Db.b(r.get("sat"));
            s.strength = Db.i(r.get("strength"));
            m.sections.put(s.id, s);
        }

        for (Map<String, Object> r : Db.query(c, "SELECT section_id, subject_id, faculty_id, room_id, time_slot_id FROM timetable_entry")) {
            int slot = Db.i(r.get("time_slot_id"));
            m.occ.add("R" + Db.i(r.get("room_id")) + ":" + slot);
            m.occ.add("S" + Db.i(r.get("section_id")) + ":" + slot);
            m.occ.add("F" + Db.i(r.get("faculty_id")) + ":" + slot);
            Subj s = m.subjects.get(Db.i(r.get("subject_id")));
            if (s != null) for (int f : s.faculty) m.occ.add("F" + f + ":" + slot);
        }
        for (Map<String, Object> r : Db.query(c, "SELECT faculty_id, time_slot_id FROM faculty_unavailable"))
            m.occ.add("F" + Db.i(r.get("faculty_id")) + ":" + Db.i(r.get("time_slot_id")));
        return m;
    }

    public Map<String, Object> generate(List<Integer> sectionIds, boolean dryRun) throws SQLException {
        long t0 = System.nanoTime();
        synchronized (LOCK) {
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Set<Integer> ids = new LinkedHashSet<>(sectionIds);
                    for (Map<String, Object> r : Db.query(c, "SELECT DISTINCT s2.section_id AS sid FROM subject s1 JOIN subject s2 ON s2.joint_group = s1.joint_group " +
                            "WHERE s1.joint_group IS NOT NULL AND s1.section_id IN (" + Db.placeholders(Math.max(1, ids.size())) + ")", ids.toArray()))
                        if (r.get("sid") != null) ids.add(Db.i(r.get("sid")));
                    int partners = ids.size() - new HashSet<>(sectionIds).size();
                    sectionIds = new ArrayList<>(ids);
                    for (int sid : sectionIds) Db.update(c, "DELETE FROM timetable_entry WHERE section_id = ?", sid);
                    Model m = load(c);

                    List<Sec> secs = new ArrayList<>();
                    for (int sid : sectionIds) if (m.sections.containsKey(sid)) secs.add(m.sections.get(sid));
                    secs.sort(Comparator.comparingInt(s -> s.id));

                    List<Placement> plan = new ArrayList<>();
                    List<String> failed = new ArrayList<>();
                    List<String> trace = new ArrayList<>();
                    Map<String, Integer> dayLoad = new HashMap<>();
                    Map<String, Set<String>> subjDays = new HashMap<>();
                    Map<String, Integer> facCecDay = new HashMap<>();
                    int[] group = {nextGroupId(c)};
                    int[] jointSeq = {Db.i(Db.one(c, "SELECT COALESCE(MAX(joint_id),0) AS j FROM timetable_entry").get("j")) + 1};
                    if (partners > 0) trace.add("Joint classes: " + partners + " more section(s) share a joint class with the chosen one, so they are generated together.");

                    trace.add("Resource-allocation manager started for " + secs.size() + " section(s); " +
                            m.rooms.size() + " rooms, " + countTeaching(m) + " teaching periods per week, breaks are never allocated.");
                    Map<String, Object> pre = capacityPrecheck(m, secs, trace);
                    planLabs(m, secs, plan, failed, trace, dayLoad, subjDays, group);
                    planLectures(m, secs, plan, failed, trace, dayLoad, subjDays, facCecDay, jointSeq, group);
                    persist(c, plan);

                    if (dryRun) c.rollback(); else c.commit();

                    Map<String, Object> out = new LinkedHashMap<>();
                    List<Map<String, Object>> perSection = new ArrayList<>();
                    int required = 0;
                    for (Sec s : secs) {
                        int req = 0, got = 0;
                        for (Subj sub : m.subjects.values())
                            if (matches(sub, s)) req += isCec(sub) ? 4 : (sub.ppw * (sub.lab ? 2 : 1));
                        for (Placement p : plan) if (p.sectionId == s.id) got++;
                        required += req;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("sectionId", s.id);
                        row.put("section", s.name);
                        row.put("required", req);
                        row.put("placed", got);
                        perSection.add(row);
                    }
                    out.put("placed", plan.size());
                    out.put("required", required);
                    out.put("failed", failed);
                    out.put("sections", perSection);
                    out.put("trace", trace.size() > 400 ? trace.subList(0, 400) : trace);
                    out.put("millis", (System.nanoTime() - t0) / 1_000_000);
                    out.put("dryRun", dryRun);
                    out.put("bankersPrecheck", pre);

                    if (!dryRun && !secs.isEmpty()) {
                        StringBuilder names = new StringBuilder();
                        for (Sec s : secs) names.append(names.length() > 0 ? ", " : "").append(s.name);
                        Db.update(c, "INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                                "Timetable updated: " + names,
                                "The class timetable for " + names + " has been generated / refreshed. Please check your schedule.",
                                "all", "change", "system");
                        c.commit();
                    }
                    return out;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }
    }

    private static int countTeaching(Model m) {
        int n = 0;
        for (Slot s : m.slots) if (!s.brk) n++;
        return n;
    }

    private static int nextGroupId(Connection c) throws SQLException {
        return Db.i(Db.one(c, "SELECT COALESCE(MAX(group_id),0) AS g FROM timetable_entry").get("g")) + 1;
    }

    private void persist(Connection c, List<Placement> plan) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO timetable_entry(section_id,subject_id,faculty_id,room_id,time_slot_id,group_id,joint_id) VALUES (?,?,?,?,?,?,?)")) {
            for (Placement p : plan) {
                ps.setInt(1, p.sectionId);
                ps.setInt(2, p.subjectId);
                ps.setInt(3, p.facultyId);
                ps.setInt(4, p.roomId);
                ps.setInt(5, p.slotId);
                if (p.groupId == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, p.groupId);
                ps.setInt(7, p.jointId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static boolean anyBusy(List<String> cells, Set<String> a, Set<String> b) {
        for (String x : cells) if (a.contains(x) || b.contains(x)) return true;
        return false;
    }

    private Map<String, Object> capacityPrecheck(Model m, List<Sec> secs, List<String> trace) {
        int teaching = countTeaching(m);
        int lectureRooms = 0, labRooms = 0;
        Map<Integer, Room> roomById = new HashMap<>();
        for (Room r : m.rooms) {
            roomById.put(r.id, r);
            if (r.type.equals("Lab")) labRooms++; else lectureRooms++;
        }
        int usedLecture = 0, usedLab = 0;
        Map<Integer, Integer> usedFac = new HashMap<>();
        for (String key : m.occ) {
            int id = Integer.parseInt(key.substring(1, key.indexOf(':')));
            if (key.charAt(0) == 'R') {
                if (roomById.get(id) != null && roomById.get(id).type.equals("Lab")) usedLab++; else usedLecture++;
            } else if (key.charAt(0) == 'F') {
                usedFac.merge(id, 1, Integer::sum);
            }
        }
        List<Integer> facIds = new ArrayList<>();
        for (Sec sec : secs) for (Subj s : m.subjects.values())
            if (matches(s, sec)) for (int f : s.faculty) if (!facIds.contains(f)) facIds.add(f);
        int r = 2 + facIds.size(), n = secs.size();
        int[] avail = new int[r];
        String[] rname = new String[r];
        avail[0] = lectureRooms * teaching - usedLecture;
        rname[0] = "lecture-room periods";
        avail[1] = labRooms * teaching - usedLab;
        rname[1] = "lab-room periods";
        for (int k = 0; k < facIds.size(); k++) {
            avail[2 + k] = teaching - usedFac.getOrDefault(facIds.get(k), 0);
            rname[2 + k] = m.facultyName.getOrDefault(facIds.get(k), "faculty " + facIds.get(k)) + "'s free periods";
        }
        int[][] max = new int[n][r], alloc = new int[n][r];
        for (int i = 0; i < n; i++) {
            for (Subj s : m.subjects.values()) {
                if (!matches(s, secs.get(i))) continue;
                int periods = isCec(s) ? 4 : (s.ppw * (s.lab ? 2 : 1));
                max[i][s.lab ? 1 : 0] += periods;
                for (int f : s.faculty) max[i][2 + facIds.indexOf(f)] += periods;
            }
        }
        BankersAlgorithm.SafetyResult sr = new BankersAlgorithm(avail, max, alloc, false).isSafe();
        List<String> shortages = new ArrayList<>();
        for (int j = 0; j < r; j++) {
            int need = 0;
            for (int i = 0; i < n; i++) need += max[i][j];
            if (need > avail[j]) shortages.add(rname[j] + ": needed " + need + ", only " + Math.max(avail[j], 0) + " free");
        }
        List<String> seq = new ArrayList<>();
        for (int i : sr.sequence) seq.add(secs.get(i).name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("safe", sr.safe);
        out.put("sequence", seq);
        out.put("shortages", shortages);
        out.put("resourceTypes", r);
        if (secs.isEmpty()) return out;
        trace.add("BANKER'S PRE-CHECK  " + n + " sections x " + r + " resource types (rooms + each teacher's free periods): " +
                (sr.safe ? "SAFE, safe sequence <" + String.join(", ", seq) + ">" : "UNSAFE - " + String.join("; ", shortages)));
        if (!sr.safe) trace.add("         The engine will still place as much as it can, but the shortage above means some classes cannot fit.");
        return out;
    }

    private void planLabs(Model m, List<Sec> secs, List<Placement> plan, List<String> failed, List<String> trace,
                          Map<String, Integer> dayLoad, Map<String, Set<String>> subjDays, int[] group) {
        Map<Integer, Deque<Session>> perSec = new LinkedHashMap<>();
        for (Sec sec : secs) {
            Deque<Session> q = new ArrayDeque<>();
            for (Subj sub : m.subjects.values()) {
                if (sub.lab && matches(sub, sec)) {
                    for (int k = 1; k <= sub.ppw; k++) {
                        Session s = new Session();
                        s.sec = sec;
                        s.sub = sub;
                        s.k = k;
                        q.add(s);
                    }
                }
            }
            perSec.put(sec.id, q);
        }
        List<Session> order = new ArrayList<>();
        boolean any = true;
        while (any) {
            any = false;
            for (Deque<Session> q : perSec.values()) {
                if (!q.isEmpty()) { order.add(q.poll()); any = true; }
            }
        }
        if (order.isEmpty()) return;

        trace.add("PHASE 1  Practical sessions go first (priority scheduling). " + order.size() +
                " session(s); each needs ONE room + ALL its faculty for TWO consecutive periods.");
        trace.add("         Requests are interleaved period-by-period (like concurrent admins) and every grant is checked with Banker's algorithm.");

        List<Session> inProgress = new ArrayList<>();
        Set<String> held = new HashSet<>();
        Map<String, Integer> labLoad = new HashMap<>();

        for (Session s : order) {
            List<Cand> cands = labCandidates(m, s, held, dayLoad, subjDays, labLoad);
            boolean granted = false;
            int rejected = 0;
            for (Cand cd : cands) {
                s.cand = cd;
                List<Session> procs = new ArrayList<>(inProgress);
                procs.add(s);
                BankersAlgorithm.RequestResult rr = bankersRequest(s, procs, m.occ, held, 1);
                if (rr.granted) {
                    s.state = 1;
                    inProgress.add(s);
                    held.addAll(s.period(1));
                    labLoad.merge(cd.a.day, 1, Integer::sum);
                    dayLoad.merge(s.sec.id + ":" + cd.a.day, 2, Integer::sum);
                    subjDays.computeIfAbsent(s.sec.id + ":" + s.sub.id, k -> new HashSet<>()).add(cd.a.day);
                    if (rejected > 0)
                        trace.add("        (" + rejected + " earlier slot choice(s) for " + s.label() + " rejected as UNSAFE)");
                    trace.add("GRANT   " + s.label() + " period 1 -> " + cd.room.name + " @ " + cd.a.text() +
                            "   Banker's: SAFE, safe sequence " + names(rr.safety.sequence, procs));
                    granted = true;
                    break;
                } else {
                    rejected++;
                    UNSAFE_REJECTED.incrementAndGet();
                    if (rejected <= 2)
                        trace.add("REJECT  " + s.label() + " period 1 -> " + cd.room.name + " @ " + cd.a.text() +
                                "   Banker's: UNSAFE - it would leave an in-progress session unable to get its second period");
                }
            }
            if (!granted) {
                s.state = -1;
                failed.add(s.sec.name + ": " + s.sub.name + " practical (no free consecutive 2-period block with room and all faculty)");
                trace.add("FAIL    " + s.label() + " could not be placed safely");
            }
        }

        for (Session s : order) {
            if (s.state != 1) continue;
            BankersAlgorithm.RequestResult rr = bankersRequest(s, inProgress, m.occ, held, 2);
            if (!rr.granted) {
                failed.add(s.sec.name + ": " + s.sub.name + " practical (second period denied)");
                held.removeAll(s.period(1));
                inProgress.remove(s);
                continue;
            }
            trace.add("GRANT   " + s.label() + " period 2 -> " + s.cand.room.name + " @ " + s.cand.b.text() + "   session complete");
            s.state = 2;
            inProgress.remove(s);
            held.removeAll(s.period(1));
            m.occ.addAll(s.claim());
            int gid = group[0]++;
            for (int p = 1; p <= 2; p++) {
                Placement pl = new Placement();
                pl.sectionId = s.sec.id;
                pl.subjectId = s.sub.id;
                pl.facultyId = s.sub.facultyId;
                pl.roomId = s.cand.room.id;
                pl.slotId = (p == 1 ? s.cand.a : s.cand.b).id;
                pl.groupId = gid;
                plan.add(pl);
            }
        }
    }

    private List<Cand> labCandidates(Model m, Session s, Set<String> held, Map<String, Integer> dayLoad,
                                     Map<String, Set<String>> subjDays, Map<String, Integer> labLoad) {
        List<Cand> out = new ArrayList<>();
        Set<String> used = subjDays.getOrDefault(s.sec.id + ":" + s.sub.id, Collections.emptySet());
        for (Slot a : m.slots) {
            if (a.brk || a.next == null) continue;
            if (!s.sec.saturday && a.day.equals("Sat")) continue;
            Slot b = a.next;
            for (Room r : m.rooms) {
                if (!r.type.equals("Lab")) continue;
                List<String> cells = new ArrayList<>(cellsFor(s.sec, s.sub, r, a));
                cells.addAll(cellsFor(s.sec, s.sub, r, b));
                if (anyBusy(cells, m.occ, held)) continue;
                Cand cd = new Cand();
                cd.a = a;
                cd.b = b;
                cd.room = r;
                cd.score = dayLoad.getOrDefault(s.sec.id + ":" + a.day, 0) * 100 + labLoad.getOrDefault(a.day, 0) * 150
                        + a.order + (used.contains(a.day) ? 10000 : 0);
                out.add(cd);
            }
        }
        out.sort(Comparator.comparingInt((Cand x) -> x.score).thenComparingInt(x -> x.room.id));
        return out;
    }

    private BankersAlgorithm.RequestResult bankersRequest(Session req, List<Session> procs, Set<String> occ,
                                                          Set<String> held, int period) {
        LinkedHashMap<String, Integer> idx = new LinkedHashMap<>();
        for (Session p : procs) for (String cell : p.claim()) idx.putIfAbsent(cell, idx.size());
        int n = procs.size(), r = idx.size();
        int[][] max = new int[n][r], alloc = new int[n][r];
        int[] avail = new int[r];
        int reqIdx = -1;
        for (int i = 0; i < n; i++) {
            Session p = procs.get(i);
            if (p == req) reqIdx = i;
            for (String cell : p.claim()) max[i][idx.get(cell)] = 1;
            if (p.state == 1) for (String cell : p.period(1)) alloc[i][idx.get(cell)] = 1;
        }
        for (Map.Entry<String, Integer> e : idx.entrySet())
            avail[e.getValue()] = (occ.contains(e.getKey()) || held.contains(e.getKey())) ? 0 : 1;
        int[] vec = new int[r];
        for (String cell : req.period(period)) vec[idx.get(cell)] = 1;
        BankersAlgorithm b = new BankersAlgorithm(avail, max, alloc, false);
        return b.request(reqIdx, vec);
    }

    private static String names(List<Integer> seq, List<Session> procs) {
        StringBuilder sb = new StringBuilder("<");
        for (int k = 0; k < seq.size(); k++) sb.append(k > 0 ? ", " : "").append(procs.get(seq.get(k)).label());
        return sb.append(">").toString();
    }

    private void planLectures(Model m, List<Sec> secs, List<Placement> plan, List<String> failed, List<String> trace,
                              Map<String, Integer> dayLoad, Map<String, Set<String>> subjDays, Map<String, Integer> facCecDay,
                              int[] jointSeq, int[] group) {
        Map<Integer, List<Subj>> lecs = new LinkedHashMap<>();
        int maxPpw = 0;
        for (Sec sec : secs) {
            List<Subj> l = new ArrayList<>();
            for (Subj s : m.subjects.values()) if (!s.lab && matches(s, sec)) { l.add(s); maxPpw = Math.max(maxPpw, s.ppw); }
            lecs.put(sec.id, l);
        }
        trace.add("PHASE 2  Lecture periods, one at a time, round-robin over sections so each section gets a fair share and its week is spread over all days.");
        Map<Integer, Integer> placedBySec = new HashMap<>();
        List<Object[]> missing = new ArrayList<>();
        Map<Integer, Room> homeRoom = new HashMap<>();
        Set<String> doneJoint = new HashSet<>();
        int effectiveMaxRound = Math.max(maxPpw, 2);
        for (int round = 1; round <= effectiveMaxRound; round++) {
            for (Sec sec : secs) {
                for (Subj sub : lecs.get(sec.id)) {
                    if (isCec(sub)) {
                        int targetSessions = 2;
                        if (round > targetSessions) continue;
                        if (sub.jointGroup != null) {
                            String jk = "CEC#" + sub.jointGroup + "#" + round;
                            if (!doneJoint.add(jk)) continue;
                            List<Sec> msec = new ArrayList<>(); List<Subj> msub = new ArrayList<>();
                            for (Sec s2 : secs) for (Subj b2 : lecs.get(s2.id))
                                if (sub.jointGroup.equals(b2.jointGroup) && isCec(b2)) { msec.add(s2); msub.add(b2); }
                            Cand jb = bestJointTwoPeriod(m, msec, msub, dayLoad, subjDays, facCecDay);
                            if (jb == null) {
                                for (int i = 0; i < msec.size(); i++)
                                    failed.add(msec.get(i).name + ": " + msub.get(i).name + " (joint CEC 2-hr session " + round + " of " + targetSessions + ")");
                                continue;
                            }
                            int jid = jointSeq[0]++;
                            for (int i = 0; i < msec.size(); i++) {
                                Sec s2 = msec.get(i); Subj b2 = msub.get(i);
                                int gid = group[0]++;
                                m.occ.addAll(cellsForTwo(s2, b2, jb.room, jb.a, jb.b));
                                for (Slot sl : Arrays.asList(jb.a, jb.b)) {
                                    Placement pl = new Placement();
                                    pl.sectionId = s2.id; pl.subjectId = b2.id; pl.facultyId = b2.facultyId;
                                    pl.roomId = jb.room.id; pl.slotId = sl.id; pl.groupId = gid; pl.jointId = jid;
                                    plan.add(pl);
                                }
                                dayLoad.merge(s2.id + ":" + jb.a.day, 2, Integer::sum);
                                subjDays.computeIfAbsent(s2.id + ":" + b2.id, k -> new HashSet<>()).add(jb.a.day);
                                markFacCecOnDay(facCecDay, b2, jb.a.day);
                                placedBySec.merge(s2.id, 2, Integer::sum);
                            }
                            continue;
                        }
                        Cand best = bestCecTwoPeriod(m, sec, sub, dayLoad, subjDays, facCecDay, homeRoom);
                        if (best == null) {
                            failed.add(sec.name + ": " + sub.name + " (CEC 2-hr session " + round + " of " + targetSessions + ")");
                            continue;
                        }
                        int gid = group[0]++;
                        m.occ.addAll(cellsForTwo(sec, sub, best.room, best.a, best.b));
                        homeRoom.putIfAbsent(sec.id, best.room);
                        for (Slot sl : Arrays.asList(best.a, best.b)) {
                            Placement pl = new Placement();
                            pl.sectionId = sec.id; pl.subjectId = sub.id; pl.facultyId = sub.facultyId;
                            pl.roomId = best.room.id; pl.slotId = sl.id; pl.groupId = gid;
                            plan.add(pl);
                        }
                        dayLoad.merge(sec.id + ":" + best.a.day, 2, Integer::sum);
                        subjDays.computeIfAbsent(sec.id + ":" + sub.id, k -> new HashSet<>()).add(best.a.day);
                        markFacCecOnDay(facCecDay, sub, best.a.day);
                        placedBySec.merge(sec.id, 2, Integer::sum);
                        continue;
                    }
                    if (sub.ppw < round) continue;
                    if (sub.jointGroup != null) {
                        String jk = sub.jointGroup + "#" + round;
                        if (!doneJoint.add(jk)) continue;
                        List<Sec> msec = new ArrayList<>(); List<Subj> msub = new ArrayList<>();
                        for (Sec s2 : secs) for (Subj b2 : lecs.get(s2.id))
                            if (sub.jointGroup.equals(b2.jointGroup) && b2.ppw >= round) { msec.add(s2); msub.add(b2); }
                        Cand jb = bestJoint(m, msec, msub, dayLoad, subjDays);
                        if (jb == null) {
                            for (int i = 0; i < msec.size(); i++)
                                failed.add(msec.get(i).name + ": " + msub.get(i).name + " (joint lecture " + round + " of " + msub.get(i).ppw + ")");
                            continue;
                        }
                        int jid = jointSeq[0]++;
                        for (int i = 0; i < msec.size(); i++) {
                            Sec s2 = msec.get(i); Subj b2 = msub.get(i);
                            m.occ.addAll(cellsFor(s2, b2, jb.room, jb.a));
                            Placement pl = new Placement();
                            pl.sectionId = s2.id; pl.subjectId = b2.id; pl.facultyId = b2.facultyId;
                            pl.roomId = jb.room.id; pl.slotId = jb.a.id; pl.jointId = jid;
                            plan.add(pl);
                            dayLoad.merge(s2.id + ":" + jb.a.day, 1, Integer::sum);
                            subjDays.computeIfAbsent(s2.id + ":" + b2.id, k -> new HashSet<>()).add(jb.a.day);
                            placedBySec.merge(s2.id, 1, Integer::sum);
                        }
                        continue;
                    }
                    Cand best = bestLecture(m, sec, sub, dayLoad, subjDays, homeRoom);
                    if (best == null) {
                        String msg = sec.name + ": " + sub.name + " (lecture " + round + " of " + sub.ppw + ")";
                        failed.add(msg);
                        missing.add(new Object[]{sec, sub, msg});
                        continue;
                    }
                    m.occ.addAll(cellsFor(sec, sub, best.room, best.a));
                    homeRoom.putIfAbsent(sec.id, best.room);
                    Placement pl = new Placement();
                    pl.sectionId = sec.id;
                    pl.subjectId = sub.id;
                    pl.facultyId = sub.facultyId;
                    pl.roomId = best.room.id;
                    pl.slotId = best.a.id;
                    plan.add(pl);
                    dayLoad.merge(sec.id + ":" + best.a.day, 1, Integer::sum);
                    subjDays.computeIfAbsent(sec.id + ":" + sub.id, k -> new HashSet<>()).add(best.a.day);
                    placedBySec.merge(sec.id, 1, Integer::sum);
                }
            }
        }
        for (int pass = 0; pass < 4 && !missing.isEmpty(); pass++) {
            boolean progress = false;
            for (Iterator<Object[]> it = missing.iterator(); it.hasNext(); ) {
                Object[] mm = it.next();
                Sec sec = (Sec) mm[0]; Subj sub = (Subj) mm[1];
                if (repairLecture(m, sec, sub, plan, dayLoad, subjDays, homeRoom)) {
                    failed.remove((String) mm[2]);
                    trace.add("PHASE 3  repaired " + mm[2] + " by moving the lecture that blocked it to another free period.");
                    placedBySec.merge(sec.id, 1, Integer::sum);
                    it.remove();
                    progress = true;
                }
            }
            if (!progress) break;
        }
        for (Sec sec : secs)
            trace.add("        " + sec.name + ": " + placedBySec.getOrDefault(sec.id, 0) + " lecture periods placed");
    }

    private Room lectureRoomAt(Model m, Sec sec, Subj sub, Slot t, Map<Integer, Room> homeRoom) {
        Room home = homeRoom.get(sec.id);
        if (home != null && !anyBusy(cellsFor(sec, sub, home, t), m.occ, Collections.emptySet())) return home;
        for (int pass = 0; pass < 2; pass++)
            for (Room r : m.rooms) {
                if (!r.type.equals("Lecture")) continue;
                if (pass == 0 && r.capacity < sec.strength) continue;
                if (!anyBusy(cellsFor(sec, sub, r, t), m.occ, Collections.emptySet())) return r;
            }
        return null;
    }

    private boolean repairLecture(Model m, Sec sec, Subj sub, List<Placement> plan, Map<String, Integer> dayLoad,
                                  Map<String, Set<String>> subjDays, Map<Integer, Room> homeRoom) {
        for (Slot t : m.slots) {
            if (t.brk) continue;
            if (!sec.saturday && t.day.equals("Sat")) continue;
            Placement blocker = null;
            int blockers = 0;
            for (Placement p : plan) {
                if (p.slotId != t.id || p.groupId != null || p.jointId != 0) continue;
                Subj ps = m.subjects.get(p.subjectId);
                if (ps == null || ps.lab) continue;
                if (p.sectionId == sec.id || sub.faculty.contains(p.facultyId)) { blocker = p; blockers++; }
            }
            if (blockers != 1) continue;
            Subj subP = m.subjects.get(blocker.subjectId);
            Sec secP = m.sections.get(blocker.sectionId);
            Room roomP = null;
            for (Room r : m.rooms) if (r.id == blocker.roomId) roomP = r;
            if (subP == null || secP == null || roomP == null) continue;

            List<String> cellsP = cellsFor(secP, subP, roomP, t);
            m.occ.removeAll(cellsP);
            Room room = lectureRoomAt(m, sec, sub, t, homeRoom);
            if (room == null) { m.occ.addAll(cellsP); continue; }
            List<String> cellsNew = cellsFor(sec, sub, room, t);
            if (anyBusy(cellsNew, m.occ, Collections.emptySet())) { m.occ.addAll(cellsP); continue; }
            m.occ.addAll(cellsNew);
            Cand c2 = bestLecture(m, secP, subP, dayLoad, subjDays, homeRoom);
            if (c2 == null) { m.occ.removeAll(cellsNew); m.occ.addAll(cellsP); continue; }

            m.occ.addAll(cellsFor(secP, subP, c2.room, c2.a));
            blocker.roomId = c2.room.id;
            blocker.slotId = c2.a.id;
            dayLoad.merge(secP.id + ":" + t.day, -1, Integer::sum);
            dayLoad.merge(secP.id + ":" + c2.a.day, 1, Integer::sum);

            Placement pl = new Placement();
            pl.sectionId = sec.id; pl.subjectId = sub.id; pl.facultyId = sub.facultyId; pl.roomId = room.id; pl.slotId = t.id;
            plan.add(pl);
            dayLoad.merge(sec.id + ":" + t.day, 1, Integer::sum);
            subjDays.computeIfAbsent(sec.id + ":" + sub.id, k -> new HashSet<>()).add(t.day);
            homeRoom.putIfAbsent(sec.id, room);
            return true;
        }
        return false;
    }

    private Cand bestCecTwoPeriod(Model m, Sec sec, Subj sub, Map<String, Integer> dayLoad,
                                  Map<String, Set<String>> subjDays, Map<String, Integer> facCecDay,
                                  Map<Integer, Room> homeRoom) {
        Set<String> used = subjDays.getOrDefault(sec.id + ":" + sub.id, Collections.emptySet());
        Cand best = null;
        for (Slot a : m.slots) {
            if (a.brk || a.next == null) continue;
            if (!sec.saturday && a.day.equals("Sat")) continue;
            if (facHasCecOnDay(facCecDay, sub, a.day)) continue;
            Slot b = a.next;
            Room chosen = null;
            for (int pass = 0; pass < 2 && chosen == null; pass++) {
                for (Room r : m.rooms) {
                    if (!r.type.equals("Lecture")) continue;
                    if (pass == 0 && r.capacity < sec.strength) continue;
                    if (!anyBusy(cellsForTwo(sec, sub, r, a, b), m.occ, Collections.emptySet())) { chosen = r; break; }
                }
            }
            if (chosen == null) continue;
            int score = dayLoad.getOrDefault(sec.id + ":" + a.day, 0) * 100 + a.order + (used.contains(a.day) ? 10000 : 0);
            if (best == null || score < best.score) { best = new Cand(); best.a = a; best.b = b; best.room = chosen; best.score = score; }
        }
        return best;
    }

    private Cand bestJointTwoPeriod(Model m, List<Sec> msec, List<Subj> msub, Map<String, Integer> dayLoad,
                                    Map<String, Set<String>> subjDays, Map<String, Integer> facCecDay) {
        int need = 0;
        boolean allSat = true;
        for (Sec s2 : msec) { need += s2.strength; if (!s2.saturday) allSat = false; }
        Cand best = null;
        for (Slot a : m.slots) {
            if (a.brk || a.next == null) continue;
            if (!allSat && a.day.equals("Sat")) continue;
            Slot b = a.next;
            boolean blockedByTeacherDay = false;
            for (Subj b2 : msub) {
                if (facHasCecOnDay(facCecDay, b2, a.day)) { blockedByTeacherDay = true; break; }
            }
            if (blockedByTeacherDay) continue;
            List<String> nonRoomCells = new ArrayList<>();
            for (int i = 0; i < msec.size(); i++) {
                Sec s2 = msec.get(i);
                Subj b2 = msub.get(i);
                for (Slot sl : Arrays.asList(a, b)) {
                    String sc = "S" + s2.id + ":" + sl.id;
                    if (!nonRoomCells.contains(sc)) nonRoomCells.add(sc);
                    for (int f : cecTeachers(b2)) {
                        String fc = "F" + f + ":" + sl.id;
                        if (!nonRoomCells.contains(fc)) nonRoomCells.add(fc);
                    }
                }
            }
            if (anyBusy(nonRoomCells, m.occ, Collections.emptySet())) continue;
            Room chosen = null;
            for (Room r : m.rooms) {
                if (!r.type.equals("Lecture") || r.capacity < need) continue;
                if (m.occ.contains("R" + r.id + ":" + a.id) || m.occ.contains("R" + r.id + ":" + b.id)) continue;
                if (chosen == null || r.capacity < chosen.capacity) chosen = r;
            }
            if (chosen == null) continue;
            int score = a.order;
            for (int i = 0; i < msec.size(); i++) {
                score += dayLoad.getOrDefault(msec.get(i).id + ":" + a.day, 0) * 100;
                if (subjDays.getOrDefault(msec.get(i).id + ":" + msub.get(i).id, Collections.emptySet()).contains(a.day)) score += 10000;
            }
            if (best == null || score < best.score) { best = new Cand(); best.a = a; best.b = b; best.room = chosen; best.score = score; }
        }
        return best;
    }

    private Cand bestJoint(Model m, List<Sec> msec, List<Subj> msub, Map<String, Integer> dayLoad, Map<String, Set<String>> subjDays) {
        int need = 0;
        boolean allSat = true;
        for (Sec s2 : msec) { need += s2.strength; if (!s2.saturday) allSat = false; }
        Cand best = null;
        for (Slot t : m.slots) {
            if (t.brk) continue;
            if (!allSat && t.day.equals("Sat")) continue;
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < msec.size(); i++) {
                cells.add("S" + msec.get(i).id + ":" + t.id);
                for (int f : msub.get(i).faculty) { String fc = "F" + f + ":" + t.id; if (!cells.contains(fc)) cells.add(fc); }
            }
            if (anyBusy(cells, m.occ, Collections.emptySet())) continue;
            Room chosen = null;
            for (Room r : m.rooms) {
                if (!r.type.equals("Lecture") || r.capacity < need) continue;
                if (m.occ.contains("R" + r.id + ":" + t.id)) continue;
                if (chosen == null || r.capacity < chosen.capacity) chosen = r;
            }
            if (chosen == null) continue;
            int score = t.order;
            for (int i = 0; i < msec.size(); i++) {
                score += dayLoad.getOrDefault(msec.get(i).id + ":" + t.day, 0) * 100;
                if (subjDays.getOrDefault(msec.get(i).id + ":" + msub.get(i).id, Collections.emptySet()).contains(t.day)) score += 10000;
            }
            if (best == null || score < best.score) { best = new Cand(); best.a = t; best.room = chosen; best.score = score; }
        }
        return best;
    }

    private Cand bestLecture(Model m, Sec sec, Subj sub, Map<String, Integer> dayLoad, Map<String, Set<String>> subjDays,
                             Map<Integer, Room> homeRoom) {
        Set<String> used = subjDays.getOrDefault(sec.id + ":" + sub.id, Collections.emptySet());
        Cand best = null;
        for (Slot t : m.slots) {
            if (t.brk) continue;
            if (!sec.saturday && t.day.equals("Sat")) continue;
            Room chosen = null;
            Room home = homeRoom.get(sec.id);
            if (home != null && !anyBusy(cellsFor(sec, sub, home, t), m.occ, Collections.emptySet())) chosen = home;
            for (int pass = 0; pass < 2 && chosen == null; pass++) {
                for (Room r : m.rooms) {
                    if (!r.type.equals("Lecture")) continue;
                    if (pass == 0 && r.capacity < sec.strength) continue;
                    if (!anyBusy(cellsFor(sec, sub, r, t), m.occ, Collections.emptySet())) { chosen = r; break; }
                }
            }
            if (chosen == null) continue;
            int score = dayLoad.getOrDefault(sec.id + ":" + t.day, 0) * 100 + t.order + (used.contains(t.day) ? 10000 : 0)
                    + (home != null && chosen != home ? 5 : 0);
            if (best == null || score < best.score) {
                best = new Cand();
                best.a = t;
                best.room = chosen;
                best.score = score;
            }
        }
        return best;
    }

    public static class Conflict {
        public final String type, message;
        public Conflict(String type, String message) { this.type = type; this.message = message; }
    }

    public static List<Integer> facultyOf(Connection c, int subjectId) throws SQLException {
        List<Integer> l = new ArrayList<>();
        Map<String, Object> s = Db.one(c, "SELECT faculty_id FROM subject WHERE id = ?", subjectId);
        if (s == null) return l;
        l.add(Db.i(s.get("faculty_id")));
        for (Map<String, Object> r : Db.query(c, "SELECT faculty_id FROM subject_faculty WHERE subject_id = ? ORDER BY faculty_id", subjectId)) {
            int f = Db.i(r.get("faculty_id"));
            if (!l.contains(f)) l.add(f);
        }
        return l;
    }

    public static Conflict checkCell(Connection c, int sectionId, List<Integer> facultyIds, int roomId, int slotId,
                                     Integer ignoreEntryId) throws SQLException {
        String ign = ignoreEntryId == null ? "" : " AND te.id <> " + ignoreEntryId.intValue();
        String inF = Db.placeholders(facultyIds.size());
        Map<String, Object> row;

        row = Db.one(c, "SELECT ts.day AS d, s.name AS sn, s.works_saturday AS w FROM time_slot ts, section s WHERE ts.id = ? AND s.id = ?", slotId, sectionId);
        if (row != null && "Sat".equals(Db.s(row.get("d"))) && !Db.b(row.get("w")))
            return new Conflict("section", "Saturday is off for section " + row.get("sn") + ".");

        if (!facultyIds.isEmpty()) {
            List<Object> p = new ArrayList<>();
            p.add(slotId);
            p.addAll(facultyIds);
            row = Db.one(c, "SELECT f.name AS name FROM faculty_unavailable fu JOIN faculty f ON f.id = fu.faculty_id " +
                    "WHERE fu.time_slot_id = ? AND fu.faculty_id IN (" + inF + ") LIMIT 1", p.toArray());
            if (row != null) return new Conflict("faculty", row.get("name") + " has marked this period as unavailable.");
        }

        row = Db.one(c, "SELECT te.id AS id, r.name AS name FROM timetable_entry te JOIN room r ON r.id = te.room_id " +
                "WHERE te.time_slot_id = ? AND te.room_id = ?" + ign + " LIMIT 1", slotId, roomId);
        if (row != null) return new Conflict("room", "Room " + row.get("name") + " is already booked in this period (entry #" + row.get("id") + ").");

        if (!facultyIds.isEmpty()) {
            List<Object> p = new ArrayList<>();
            p.add(slotId);
            p.addAll(facultyIds);
            row = Db.one(c, "SELECT te.id AS id, f.name AS name FROM timetable_entry te JOIN faculty f ON " +
                    "(f.id = te.faculty_id OR f.id IN (SELECT sf.faculty_id FROM subject_faculty sf WHERE sf.subject_id = te.subject_id)) " +
                    "WHERE te.time_slot_id = ? AND f.id IN (" + inF + ")" + ign + " LIMIT 1", p.toArray());
            if (row != null) return new Conflict("faculty", row.get("name") + " is already teaching another class in this period (entry #" + row.get("id") + ").");
        }

        row = Db.one(c, "SELECT te.id AS id, s.name AS name FROM timetable_entry te JOIN section s ON s.id = te.section_id " +
                "WHERE te.time_slot_id = ? AND te.section_id = ?" + ign + " LIMIT 1", slotId, sectionId);
        if (row != null) return new Conflict("section", "Section " + row.get("name") + " already has a class in this period (entry #" + row.get("id") + ").");
        return null;
    }

    private Slot suggest(Connection c, int sectionId, List<Integer> fac, int roomId, boolean lab) throws SQLException {
        Model m = load(c);
        for (Slot a : m.slots) {
            if (a.brk) continue;
            if (lab && a.next == null) continue;
            if (checkCell(c, sectionId, fac, roomId, a.id, null) != null) continue;
            if (lab && checkCell(c, sectionId, fac, roomId, a.next.id, null) != null) continue;
            return a;
        }
        return null;
    }

    public Map<String, Object> bookEntry(int sectionId, int subjectId, int roomId, int slotId) throws SQLException, ConflictException {
        ConflictException refused = null;
        Map<String, Object> result = null;
        synchronized (LOCK) {
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Map<String, Object> sec = Db.one(c, "SELECT id, name, course_id AS courseId, semester, strength, `year` AS yr FROM section WHERE id = ?", sectionId);
                    Map<String, Object> sub = Db.one(c, "SELECT id, name, faculty_id AS facultyId, is_lab AS isLab, course_id AS courseId, section_id AS sectionId, semester, periods_per_week AS ppw, `year` AS yr FROM subject WHERE id = ?", subjectId);
                    Map<String, Object> room = Db.one(c, "SELECT id, name, type, capacity FROM room WHERE id = ?", roomId);
                    Map<String, Object> slot = Db.one(c, "SELECT id, day, TIME_FORMAT(start_time,'%H:%i') AS st, TIME_FORMAT(end_time,'%H:%i') AS et, is_break AS brk, label FROM time_slot WHERE id = ?", slotId);
                    if (sec == null || sub == null || room == null || slot == null)
                        throw new ApiException(404, "Section, subject, room or period not found.");
                    if (Db.b(slot.get("brk")))
                        throw new ApiException(400, Db.s(slot.get("label")) + " is a fixed break and can't be booked.");
                    boolean belongs = nint(sub.get("sectionId")) != null
                            ? nint(sub.get("sectionId")) == sectionId
                            : Objects.equals(nint(sec.get("courseId")), nint(sub.get("courseId"))) && Objects.equals(nint(sec.get("yr")), nint(sub.get("yr")))
                              && Objects.equals(effSem(nint(sec.get("semester")), nint(sec.get("yr"))), effSem(nint(sub.get("semester")), nint(sub.get("yr"))));
                    if (!belongs)
                        throw new ApiException(400, "Subject '" + sub.get("name") + "' doesn't belong to the course/year of section " + sec.get("name") + ".");
                    boolean lab = Db.b(sub.get("isLab"));
                    if (lab != room.get("type").equals("Lab"))
                        throw new ApiException(400, lab ? "Practical subjects need a Lab room." : "Lectures need a Lecture room, not a lab.");

                    if (Db.i(room.get("capacity")) < Db.i(sec.get("strength")))
                        throw new ApiException(400, "Room " + room.get("name") + " seats " + room.get("capacity") + " but section " + sec.get("name") + " has " + sec.get("strength") + " students.");


                    List<Integer> slotIds = new ArrayList<>();
                    slotIds.add(slotId);
                    if (lab) {
                        Map<String, Object> nxt = Db.one(c, "SELECT id FROM time_slot WHERE day = ? AND start_time = ? AND is_break = 0",
                                slot.get("day"), slot.get("et") + ":00");
                        if (nxt == null) {
                            Map<String, Object> sb = Db.one(c, "SELECT TIME_FORMAT(end_time,'%H:%i') AS e FROM time_slot WHERE day = ? AND start_time = ? AND is_break = 1 AND label = 'Short Break'",
                                    slot.get("day"), slot.get("et") + ":00");
                            if (sb != null)
                                nxt = Db.one(c, "SELECT id FROM time_slot WHERE day = ? AND start_time = ? AND is_break = 0", slot.get("day"), sb.get("e") + ":00");
                        }
                        if (nxt == null)
                            throw new ApiException(400, "A practical needs two consecutive periods, but there is no teaching period right after " +
                                    slot.get("day") + " " + slot.get("st") + "-" + slot.get("et") + " (lunch or end of day follows).");
                        slotIds.add(Db.i(nxt.get("id")));
                    }

                    List<Integer> fac = facultyOf(c, subjectId);
                    for (int sid : slotIds) {
                        Conflict cf = checkCell(c, sectionId, fac, roomId, sid, null);
                        if (cf != null) {
                            ConflictException ce = new ConflictException(cf.type, cf.message);
                            Slot alt = suggest(c, sectionId, fac, roomId, lab);
                            ce.suggestion = alt == null ? "No free period found for this room / faculty / section." : alt.text();
                            ce.suggestionSlotId = alt == null ? null : alt.id;
                            refused = ce;
                            break;
                        }
                    }
                    if (refused != null) {
                        c.rollback();
                    } else {
                        Integer gid = lab ? nextGroupId(c) : null;
                        List<Integer> ids = new ArrayList<>();
                        for (int sid : slotIds)
                            ids.add((int) Db.insert(c, "INSERT INTO timetable_entry(section_id,subject_id,faculty_id,room_id,time_slot_id,group_id) VALUES (?,?,?,?,?,?)",
                                    sectionId, subjectId, sub.get("facultyId"), roomId, sid, gid));
                        c.commit();
                        result = new LinkedHashMap<>();
                        result.put("id", ids.get(0));
                        result.put("ids", ids);
                        result.put("periods", slotIds.size());
                    }
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }
        if (refused != null) {
            DENIED.incrementAndGet();
            logConflict(refused.type, refused.getMessage());
            throw refused;
        }
        GRANTED.incrementAndGet();
        return result;
    }

    public String bookSingleNoLock(int sectionId, int subjectId, int roomId, int slotId, long pauseMs) {
        try (Connection c = DBUtil.getConnection()) {
            List<Integer> fac = facultyOf(c, subjectId);
            Conflict cf = checkCell(c, sectionId, fac, roomId, slotId, null);
            if (cf != null) return "CONFLICT";
            Thread.sleep(pauseMs);
            Map<String, Object> sub = Db.one(c, "SELECT faculty_id AS f FROM subject WHERE id = ?", subjectId);
            Db.insert(c, "INSERT INTO timetable_entry(section_id,subject_id,faculty_id,room_id,time_slot_id) VALUES (?,?,?,?,?)",
                    sectionId, subjectId, sub.get("f"), roomId, slotId);
            return "GRANTED";
        } catch (SQLException e) {
            return (e.getSQLState() != null && e.getSQLState().startsWith("23")) ? "DB_CONSTRAINT" : "ERROR:" + e.getMessage();
        } catch (Exception e) {
            return "ERROR:" + e.getMessage();
        }
    }

    public void removeEntry(int id) throws SQLException {
        synchronized (LOCK) {
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Map<String, Object> e = Db.one(c, "SELECT group_id FROM timetable_entry WHERE id = ?", id);
                    if (e == null) throw new ApiException(404, "Entry not found.");
                    if (e.get("group_id") != null)
                        Db.update(c, "DELETE FROM timetable_entry WHERE group_id = ?", e.get("group_id"));
                    else
                        Db.update(c, "DELETE FROM timetable_entry WHERE id = ?", id);
                    c.commit();
                } catch (SQLException | RuntimeException ex) {
                    c.rollback();
                    throw ex;
                }
            }
        }
    }

    public static void logConflict(String kind, String message) {
        try {
            Db.insert("INSERT INTO conflict_log(kind,message) VALUES (?,?)", kind, message.length() > 290 ? message.substring(0, 290) : message);
        } catch (SQLException ignored) { }
    }
}
