package com.timetable.servlet;

import com.timetable.engine.SchedulingEngine;
import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.util.*;

/** /api/analytics - everything the dashboard needs in one call. */
@WebServlet("/api/analytics")
public class AnalyticsServlet extends ApiServlet {

    @Override
    protected Object get(Req r) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", Db.one("SELECT (SELECT COUNT(*) FROM room) AS rooms, (SELECT COUNT(*) FROM faculty) AS faculty, " +
                "(SELECT COUNT(*) FROM course) AS courses, (SELECT COUNT(*) FROM section) AS sections, " +
                "(SELECT COUNT(*) FROM subject) AS subjects, (SELECT COUNT(*) FROM timetable_entry) AS entries, " +
                "(SELECT COUNT(*) FROM exam) AS exams, (SELECT COUNT(*) FROM notice) AS notices, " +
                "(SELECT COUNT(*) FROM substitution) AS substitutions"));
        int teaching = Db.i(Db.one("SELECT COUNT(*) AS n FROM time_slot WHERE is_break = 0").get("n"));
        out.put("teachingPeriodsPerWeek", teaching);

        out.put("rooms", Db.query("SELECT r.name AS name, r.type AS type, COUNT(DISTINCT te.time_slot_id) AS booked FROM room r " +
                "LEFT JOIN timetable_entry te ON te.room_id = r.id GROUP BY r.id, r.name, r.type ORDER BY r.type, r.name"));

        out.put("faculty", Db.query("SELECT f.name AS name, (" +
                "SELECT COUNT(*) FROM (" +
                "  SELECT DISTINCT CASE " +
                "    WHEN te.joint_id > 0 THEN CONCAT('JOINT:', ts.day, ':', te.joint_id) " +
                "    WHEN te.group_id IS NOT NULL THEN CONCAT('GRP:', te.group_id) " +
                "    ELSE CONCAT('SLOT:', te.time_slot_id) END AS unit_key " +
                "  FROM timetable_entry te " +
                "  JOIN time_slot ts ON ts.id = te.time_slot_id " +
                "  LEFT JOIN subject s ON s.id = te.subject_id " +
                "  WHERE te.faculty_id = f.id " +
                "     OR EXISTS (SELECT 1 FROM subject_faculty sf WHERE sf.subject_id = te.subject_id AND sf.faculty_id = f.id)" +
                ") u) AS periods " +
                "FROM faculty f ORDER BY periods DESC, f.name"));

        out.put("days", Db.query("SELECT ts.day AS day, COUNT(*) AS n FROM timetable_entry te JOIN time_slot ts ON ts.id = te.time_slot_id " +
                "GROUP BY ts.day ORDER BY FIELD(ts.day,'Mon','Tue','Wed','Thu','Fri','Sat')"));

        out.put("sections", Db.query("SELECT sec.name AS name, c.name AS course, sec.`year` AS `year`, COALESCE(sec.semester, sec.`year` * 2) AS semester, " +
                "(SELECT COUNT(*) FROM timetable_entry te WHERE te.section_id = sec.id) AS placed, " +
                "(SELECT COALESCE(SUM(s.periods_per_week * IF(s.is_lab, 2, 1)), 0) FROM subject s WHERE " +
                "IF(s.section_id IS NOT NULL, s.section_id = sec.id, " +
                "s.course_id <=> sec.course_id AND s.`year` <=> sec.`year` AND COALESCE(s.semester, s.`year` * 2) <=> COALESCE(sec.semester, sec.`year` * 2))) AS required " +
                "FROM section sec LEFT JOIN course c ON c.id = sec.course_id ORDER BY c.name, COALESCE(sec.semester, sec.`year` * 2), sec.name"));

        out.put("conflictsByKind", Db.query("SELECT kind, COUNT(*) AS n FROM conflict_log GROUP BY kind ORDER BY n DESC"));
        out.put("recentConflicts", Db.query("SELECT kind, message, created_at AS createdAt FROM conflict_log ORDER BY id DESC LIMIT 8"));

        Map<String, Object> eng = new LinkedHashMap<>();
        eng.put("bookingsGranted", SchedulingEngine.GRANTED.get());
        eng.put("bookingsDenied", SchedulingEngine.DENIED.get());
        eng.put("unsafeGrantsRejected", SchedulingEngine.UNSAFE_REJECTED.get());
        out.put("engine", eng);
        return out;
    }
}