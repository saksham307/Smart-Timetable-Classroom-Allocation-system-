package com.timetable.importer;

import com.timetable.importer.TimetableParser.Parsed;
import com.timetable.importer.TimetableParser.Row;
import com.timetable.util.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Turns parsed rows into database rows: sections, teachers (faculty), subjects with their OWN teacher per section,
 * lab co-teachers and joint classes. Import is idempotent: running it again updates what exists and adds what is new.
 */
public final class TimetableImporter {
    private TimetableImporter() {}

    public static class Options {
        public String course = "B.Tech CSE";
        public int semester = 1, year = 1, strength = 60;
        public String saturday = "auto";                      // auto = from the page, yes, no
        public String prefix;                                 // section name prefix, default "<semester>-"
        public boolean addRooms;
        public Map<String, String> rename = new HashMap<>();  // name found in the page -> name to use
    }

    private static String label(Parsed p, Options o, String parsed) {
        String l = o.rename.get(parsed);
        return l == null || l.trim().isEmpty() ? parsed : l.trim();
    }

    private static String finalName(Options o, String label) {
        String prefix = o.prefix != null ? o.prefix : o.semester + "-";
        return label.startsWith(prefix) ? label : prefix + label;
    }

    private static boolean works(Parsed p, Options o, String parsed) {
        if ("yes".equalsIgnoreCase(o.saturday)) return true;
        if ("no".equalsIgnoreCase(o.saturday)) return false;
        return p.saturdaySections.contains(parsed);
    }

    private static String jointName(Options o, Parsed p, Row r) {
        String[] k = r.jointKey.split("\\|");
        return "S" + o.semester + "-" + k[0] + "-" + label(p, o, k[1]);
    }

    // ------------------------------------------------------------------------------------------ preview

    public static Map<String, Object> preview(Connection c, Parsed p, Options o) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(p.warnings);
        Map<String, Integer> existingFac = facultyIndex(c);

        Map<String, Integer> secPeriods = new LinkedHashMap<>(), secSubjects = new LinkedHashMap<>();
        Set<String> newTeachers = new LinkedHashSet<>(), oldTeachers = new LinkedHashSet<>();
        Map<String, Integer> load = new HashMap<>();
        Map<String, String> displayName = new HashMap<>();
        Set<String> countedJoint = new HashSet<>();
        Map<String, Set<String>> joint = new LinkedHashMap<>();
        for (Row r : p.rows) {
            secPeriods.merge(r.section, r.ppw * (r.lab ? 2 : 1), Integer::sum);
            secSubjects.merge(r.section, 1, Integer::sum);
            List<String> ts = new ArrayList<>();
            ts.add(r.teacher); ts.addAll(r.extras);
            for (String t : ts) {
                String k = TimetableParser.teacherKey(t);
                displayName.putIfAbsent(k, t);
                if (existingFac.containsKey(k)) oldTeachers.add(k); else newTeachers.add(k);
                boolean jointOnce = r.jointKey != null && !countedJoint.add(k + "#" + jointName(o, p, r) + "#" + r.ppw);
                if (!jointOnce) load.merge(k, r.ppw * (r.lab ? 2 : 1), Integer::sum);
            }
            if (r.jointKey != null) joint.computeIfAbsent(jointName(o, p, r), x -> new LinkedHashSet<>()).add(label(p, o, r.section));
        }
        boolean anySat = false;
        for (String s : p.sections) if (works(p, o, s)) anySat = true;
        Map<String, Object> cap = Db.one(c, "SELECT SUM(day <> 'Sat') AS weekdays, COUNT(*) AS allDays FROM time_slot WHERE is_break = 0");
        int available = cap == null || cap.get("allDays") == null || Db.i(cap.get("allDays")) == 0 ? 40
                : (anySat ? Db.i(cap.get("allDays")) : Db.i(cap.get("weekdays")));

        List<Map<String, Object>> secs = new ArrayList<>();
        for (String s : p.sections) {
            Map<String, Object> m = new LinkedHashMap<>();
            String fn = finalName(o, label(p, o, s));
            m.put("found", s); m.put("name", label(p, o, s)); m.put("finalName", fn);
            m.put("subjects", secSubjects.getOrDefault(s, 0)); m.put("periods", secPeriods.getOrDefault(s, 0));
            m.put("saturday", works(p, o, s));
            m.put("exists", Db.one(c, "SELECT id FROM section WHERE name = ?", fn) != null);
            secs.add(m);
            if (secPeriods.getOrDefault(s, 0) > available)
                warnings.add("Section " + fn + " needs " + secPeriods.get(s) + " periods a week but only " + available + " teaching periods exist.");
        }
        for (Map.Entry<String, Integer> e : load.entrySet())
            if (e.getValue() > available * 0.85)
                warnings.add(displayName.get(e.getKey()) + " has " + e.getValue() + " periods a week (" + available + " exist): the timetable may not fit.");

        List<Map<String, Object>> jg = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : joint.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", e.getKey()); m.put("sections", new ArrayList<>(e.getValue()));
            jg.add(m);
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        for (Row r : p.rows) {
            if (sample.size() >= 60) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", finalName(o, label(p, o, r.section))); m.put("subject", r.subject);
            m.put("type", r.lab ? "Practical" : "Lecture"); m.put("periods", r.ppw);
            m.put("teacher", r.teacher + (r.extras.isEmpty() ? "" : " + " + String.join(", ", r.extras)));
            m.put("joint", r.jointKey != null);
            sample.add(m);
        }
        out.put("source", p.source);
        out.put("detectedSemester", p.detectedSemester);
        out.put("sections", secs);
        out.put("rows", p.rows.size());
        out.put("sampleRows", sample);
        out.put("teachersNew", newTeachers.size());
        out.put("teachersExisting", oldTeachers.size());
        out.put("jointGroups", jg);
        out.put("teachingPeriodsPerWeek", available);
        out.put("warnings", warnings);
        return out;
    }

    // ------------------------------------------------------------------------------------------ commit

    public static Map<String, Object> commit(Connection c, Parsed p, Options o) throws SQLException {
        int secNew = 0, secUpd = 0, subNew = 0, subUpd = 0, facNew = 0, jointRows = 0;
        Set<String> joints = new LinkedHashSet<>();
        if (o.addRooms) ensureRooms(c);

        Db.update(c, "INSERT IGNORE INTO course(name,duration_years) VALUES (?,4)", o.course);
        int courseId = Db.i(Db.one(c, "SELECT id FROM course WHERE name = ?", o.course).get("id"));

        Map<String, Integer> secIds = new HashMap<>();
        for (String s : p.sections) {
            String fn = finalName(o, label(p, o, s));
            boolean sat = works(p, o, s);
            Map<String, Object> ex = Db.one(c, "SELECT id FROM section WHERE name = ?", fn);
            if (ex == null) {
                secIds.put(s, (int) Db.insert(c, "INSERT INTO section(name,course_id,`year`,semester,works_saturday,strength) VALUES (?,?,?,?,?,?)",
                        fn, courseId, o.year, o.semester, sat ? 1 : 0, o.strength));
                secNew++;
            } else {
                secIds.put(s, Db.i(ex.get("id")));
                Db.update(c, "UPDATE section SET course_id = ?, `year` = ?, semester = ?, works_saturday = ?, strength = ? WHERE id = ?",
                        courseId, o.year, o.semester, sat ? 1 : 0, o.strength, secIds.get(s));
                secUpd++;
            }
        }
        Map<String, Integer> fac = facultyIndex(c);
        for (Row r : p.rows) {
            Integer sectionId = secIds.get(r.section);
            if (sectionId == null) continue;
            int fid = faculty(c, fac, r.teacher); if (fid < 0) { facNew++; fid = fac.get(TimetableParser.teacherKey(r.teacher)); }
            String joint = r.jointKey != null ? jointName(o, p, r) : null;
            Map<String, Object> ex = Db.one(c, "SELECT id FROM subject WHERE name = ? AND section_id = ?", r.subject, sectionId);
            long sid;
            if (ex == null) {
                sid = Db.insert(c, "INSERT INTO subject(name,faculty_id,is_lab,course_id,section_id,`year`,semester,periods_per_week,joint_group) VALUES (?,?,?,?,?,?,?,?,?)",
                        r.subject, fid, r.lab, courseId, sectionId, o.year, o.semester, r.ppw, joint);
                subNew++;
            } else {
                sid = Db.i(ex.get("id"));
                Db.update(c, "UPDATE subject SET faculty_id = ?, is_lab = ?, course_id = ?, `year` = ?, semester = ?, periods_per_week = ?, joint_group = ? WHERE id = ?",
                        fid, r.lab, courseId, o.year, o.semester, r.ppw, joint, sid);
                subUpd++;
            }
            Db.update(c, "DELETE FROM subject_faculty WHERE subject_id = ?", sid);
            for (String x : r.extras) {
                int before = fac.size();
                int xf = faculty(c, fac, x); if (xf < 0) { facNew++; xf = fac.get(TimetableParser.teacherKey(x)); }
                if (xf != fid) Db.update(c, "INSERT IGNORE INTO subject_faculty(subject_id,faculty_id) VALUES (?,?)", sid, xf);
            }
            if (joint != null) { joints.add(joint); jointRows++; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionsCreated", secNew); out.put("sectionsUpdated", secUpd);
        out.put("subjectsCreated", subNew); out.put("subjectsUpdated", subUpd);
        out.put("teachersCreated", facNew); out.put("jointGroups", joints.size()); out.put("jointSubjects", jointRows);
        out.put("roomsEnsured", o.addRooms);
        return out;
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static Map<String, Integer> facultyIndex(Connection c) throws SQLException {
        Map<String, Integer> m = new HashMap<>();
        for (Map<String, Object> r : Db.query(c, "SELECT id, name FROM faculty ORDER BY id"))
            m.putIfAbsent(TimetableParser.teacherKey(Db.s(r.get("name"))), Db.i(r.get("id")));
        return m;
    }

    /** Returns the teacher's id, or -1 when a new teacher was created (and added to the index). */
    private static int faculty(Connection c, Map<String, Integer> idx, String name) throws SQLException {
        Integer id = idx.get(TimetableParser.teacherKey(name));
        if (id != null) return id;
        idx.put(TimetableParser.teacherKey(name), (int) Db.insert(c, "INSERT INTO faculty(name) VALUES (?)", name));
        return -1;
    }

    /** Standard rooms: lecture theatres, classrooms, big halls for joint classes and labs. Editable on the Rooms page. */
    public static void ensureRooms(Connection c) throws SQLException {
        String up = "INSERT INTO room(name,type,capacity) VALUES (?,?,?) ON DUPLICATE KEY UPDATE capacity = GREATEST(capacity, VALUES(capacity))";
        for (int i = 1; i <= 15; i++) {
            Db.update(c, up, "LT-" + i, "Lecture", 130);        // seats two joint sections of 60
            Db.update(c, up, "CR-" + i, "Lecture", 70);
        }
        for (int i = 1; i <= 3; i++) Db.update(c, up, "Hall-" + i, "Lecture", 200);
        Db.update(c, up, "Audi", "Lecture", 700);
        for (int i = 1; i <= 24; i++) Db.update(c, up, "Lab-" + i, "Lab", 70);
    }
}
