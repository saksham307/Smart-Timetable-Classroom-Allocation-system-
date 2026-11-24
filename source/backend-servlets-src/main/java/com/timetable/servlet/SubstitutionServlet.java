package com.timetable.servlet;

import com.timetable.engine.SchedulingEngine;
import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Faculty substitution for ONE date.
 *  GET  /api/substitutions[?from=&facultyId=&sectionId=]
 *  GET  /api/substitutions/available?entryId=&date=    teachers who are actually free then
 *  POST /api/substitutions   {entryId, date, substituteFacultyId, reason}
 *  DELETE /api/substitutions/{id}
 */
@WebServlet({"/api/substitutions", "/api/substitutions/*"})
public class SubstitutionServlet extends ApiServlet {

    private static final String SELECT =
        "SELECT sb.id AS id, sb.sub_date AS subDate, ts.day AS day, TIME_FORMAT(ts.start_time,'%H:%i') AS startTime, " +
        "TIME_FORMAT(ts.end_time,'%H:%i') AS endTime, s.name AS subject, sec.name AS section, sec.id AS sectionId, r.name AS room, " +
        "fo.name AS originalFaculty, fo.id AS originalFacultyId, fs.name AS substitute, sb.substitute_faculty_id AS substituteFacultyId, sb.reason AS reason " +
        "FROM substitution sb JOIN timetable_entry te ON te.id = sb.entry_id JOIN subject s ON s.id = te.subject_id " +
        "JOIN section sec ON sec.id = te.section_id JOIN room r ON r.id = te.room_id JOIN time_slot ts ON ts.id = te.time_slot_id " +
        "JOIN faculty fo ON fo.id = te.faculty_id JOIN faculty fs ON fs.id = sb.substitute_faculty_id";

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(400, "Date must look like 2026-10-14.");
        }
    }

    private static String dayOf(LocalDate d) {
        int v = d.getDayOfWeek().getValue();
        if (v > 5) throw new ApiException(400, "That date is a weekend; there are no classes to substitute.");
        return SchedulingEngine.DAYS[v - 1];
    }

    @Override
    protected Object get(Req r) throws Exception {
        if (r.path().equals("/available")) return available(r);
        LocalDate from = r.param("from") != null ? date(r.param("from")) : LocalDate.now().minusDays(7);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE sb.sub_date >= ?");
        List<Object> p = new ArrayList<>();
        p.add(java.sql.Date.valueOf(from));
        Integer fid = r.intParam("facultyId"), sid = r.intParam("sectionId");
        if (fid != null) { sql.append(" AND (te.faculty_id = ? OR sb.substitute_faculty_id = ?)"); p.add(fid); p.add(fid); }
        if (sid != null) { sql.append(" AND te.section_id = ?"); p.add(sid); }
        sql.append(" ORDER BY sb.sub_date, ts.start_time");
        return Db.query(sql.toString(), p.toArray());
    }

    private Object available(Req r) throws Exception {
        Integer entryId = r.intParam("entryId");
        LocalDate d = date(r.param("date"));
        if (entryId == null) throw new ApiException(400, "entryId is required.");
        Map<String, Object> e = Db.one("SELECT te.id AS id, te.time_slot_id AS slotId, te.faculty_id AS fid, te.subject_id AS sid, ts.day AS day " +
                "FROM timetable_entry te JOIN time_slot ts ON ts.id = te.time_slot_id WHERE te.id = ?", entryId);
        if (e == null) throw new ApiException(404, "Class not found.");
        if (!dayOf(d).equals(e.get("day"))) throw new ApiException(400, "That class is held on " + e.get("day") + ", but " + d + " is a " + dayOf(d) + ".");
        Set<Integer> busy = busy(e, d);
        busy.add(Db.i(e.get("fid")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : Db.query("SELECT id, name FROM faculty ORDER BY name"))
            if (!busy.contains(Db.i(f.get("id")))) out.add(f);
        return out;
    }

    /** faculty who can NOT take this class on that date */
    private Set<Integer> busy(Map<String, Object> e, LocalDate d) throws Exception {
        Set<Integer> busy = new HashSet<>();
        Object slot = e.get("slotId");
        for (Map<String, Object> x : Db.query("SELECT faculty_id AS f FROM timetable_entry WHERE time_slot_id = ?", slot)) busy.add(Db.i(x.get("f")));
        for (Map<String, Object> x : Db.query("SELECT sf.faculty_id AS f FROM timetable_entry te JOIN subject_faculty sf ON sf.subject_id = te.subject_id WHERE te.time_slot_id = ?", slot)) busy.add(Db.i(x.get("f")));
        for (Map<String, Object> x : Db.query("SELECT faculty_id AS f FROM faculty_unavailable WHERE time_slot_id = ?", slot)) busy.add(Db.i(x.get("f")));
        for (Map<String, Object> x : Db.query("SELECT sb.substitute_faculty_id AS f FROM substitution sb JOIN timetable_entry te ON te.id = sb.entry_id " +
                "WHERE sb.sub_date = ? AND te.time_slot_id = ? AND sb.entry_id <> ?", java.sql.Date.valueOf(d), slot, e.get("id"))) busy.add(Db.i(x.get("f")));
        return busy;
    }

    @Override
    protected Object post(Req r) throws Exception {
        int entryId = r.needInt("entryId");
        int subId = r.needInt("substituteFacultyId");
        LocalDate d = date(r.needStr("date"));
        String reason = r.str("reason");
        Map<String, Object> e = Db.one("SELECT te.id AS id, te.time_slot_id AS slotId, te.faculty_id AS fid, te.subject_id AS sid, ts.day AS day " +
                "FROM timetable_entry te JOIN time_slot ts ON ts.id = te.time_slot_id WHERE te.id = ?", entryId);
        if (e == null) throw new ApiException(404, "Class not found.");
        if (!dayOf(d).equals(e.get("day"))) throw new ApiException(400, "That class is held on " + e.get("day") + ", but " + d + " is a " + dayOf(d) + ".");
        if (subId == Db.i(e.get("fid"))) throw new ApiException(400, "The substitute must be a different teacher.");
        if (busy(e, d).contains(subId)) throw new ApiException(409, "That teacher is not free in this period (already teaching, unavailable, or substituting elsewhere).");
        long id = Db.insert("INSERT INTO substitution(entry_id,sub_date,substitute_faculty_id,reason) VALUES (?,?,?,?)",
                entryId, java.sql.Date.valueOf(d), subId, reason);
        Map<String, Object> row = Db.one(SELECT + " WHERE sb.id = ?", id);
        Db.update("INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                "Substitution on " + d + ": " + row.get("subject") + " (" + row.get("section") + ")",
                row.get("startTime") + "-" + row.get("endTime") + " in " + row.get("room") + ": " + row.get("substitute") + " will take the class instead of " +
                        row.get("originalFaculty") + (reason == null ? "." : " (" + reason + ")."),
                "all", "change", "system");
        r.status = 201;
        return row;
    }

    @Override
    protected Object delete(Req r) throws Exception {
        Integer id = r.pathId();
        if (id == null) throw new ApiException(400, "Missing id.");
        Db.update("DELETE FROM substitution WHERE id = ?", id);
        return null;
    }
}
