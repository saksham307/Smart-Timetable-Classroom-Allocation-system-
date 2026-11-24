package com.timetable.servlet;

import com.timetable.engine.ConflictException;
import com.timetable.engine.SchedulingEngine;
import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.util.*;

/**
 *  GET    /api/timetable?sectionId=&facultyId=&courseId=&year=   list entries
 *  POST   /api/timetable/entry      manual booking (conflict check + suggested alternative)
 *  DELETE /api/timetable/entry/{id} remove a class (both halves of a practical)
 *  POST   /api/timetable/generate   {sectionId} | {sectionIds:[..]} | {courseId, year}
 *  POST   /api/timetable/move       {entryId, roomId, timeslotId}  move a lecture to another room/period
 */
@WebServlet({"/api/timetable", "/api/timetable/*"})
public class TimetableServlet extends ApiServlet {

    private final SchedulingEngine engine = new SchedulingEngine();
    private final com.timetable.engine.MoveService moves = new com.timetable.engine.MoveService();

    @Override
    protected Object get(Req r) throws Exception {
        StringBuilder sql = new StringBuilder(
            "SELECT te.id AS id, te.section_id AS sectionId, te.subject_id AS subjectId, te.faculty_id AS facultyId, " +
            "te.room_id AS roomId, te.time_slot_id AS timeslotId, te.group_id AS groupId, te.joint_id AS jointId, " +
            "sec.name AS section, sec.course_id AS courseId, sec.`year` AS `year`, subj.name AS subject, subj.is_lab AS isLab, " +
            "CONCAT_WS(', ', fac.name, (SELECT GROUP_CONCAT(f2.name ORDER BY f2.id SEPARATOR ', ') FROM subject_faculty sf " +
            "JOIN faculty f2 ON f2.id = sf.faculty_id WHERE sf.subject_id = te.subject_id)) AS faculty, " +
            "r.name AS room, ts.day AS slotDay, TIME_FORMAT(ts.start_time,'%H:%i') AS slotStart, TIME_FORMAT(ts.end_time,'%H:%i') AS slotEnd " +
            "FROM timetable_entry te JOIN section sec ON sec.id = te.section_id JOIN subject subj ON subj.id = te.subject_id " +
            "JOIN faculty fac ON fac.id = te.faculty_id JOIN room r ON r.id = te.room_id JOIN time_slot ts ON ts.id = te.time_slot_id WHERE 1=1");
        List<Object> p = new ArrayList<>();
        Integer sectionId = r.intParam("sectionId"), facultyId = r.intParam("facultyId"),
                courseId = r.intParam("courseId"), year = r.intParam("year"), roomId = r.intParam("roomId");
        if (sectionId != null) { sql.append(" AND te.section_id = ?"); p.add(sectionId); }
        if (facultyId != null) {
            sql.append(" AND (te.faculty_id = ? OR EXISTS (SELECT 1 FROM subject_faculty x WHERE x.subject_id = te.subject_id AND x.faculty_id = ?))");
            p.add(facultyId);
            p.add(facultyId);
        }
        if (courseId != null) { sql.append(" AND sec.course_id = ?"); p.add(courseId); }
        if (year != null) { sql.append(" AND sec.`year` = ?"); p.add(year); }
        if (roomId != null) { sql.append(" AND te.room_id = ?"); p.add(roomId); }
        sql.append(" ORDER BY FIELD(ts.day,'Mon','Tue','Wed','Thu','Fri','Sat'), ts.start_time, sec.name");

        List<Map<String, Object>> rows = Db.query(sql.toString(), p.toArray());
        for (Map<String, Object> m : rows) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("id", m.get("timeslotId"));
            slot.put("day", m.remove("slotDay"));
            slot.put("time", m.remove("slotStart") + "-" + m.remove("slotEnd"));
            m.put("slot", slot);
        }
        if (facultyId != null || roomId != null) {                 // a joint class is one period for a teacher / room: show it once ("A + B")
            Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
            List<Map<String, Object>> merged = new ArrayList<>();
            for (Map<String, Object> m : rows) {
                int jid = m.get("jointId") == null ? 0 : Db.i(m.get("jointId"));
                if (jid == 0) { merged.add(m); continue; }
                String k = m.get("timeslotId") + ":" + jid;
                Map<String, Object> first = seen.get(k);
                if (first == null) { seen.put(k, m); merged.add(m); }
                else first.put("section", first.get("section") + " + " + m.get("section"));
            }
            return merged;
        }
        return rows;
    }

    @Override
    protected Object post(Req r) throws Exception {
        String path = r.path();
        if (path.equals("/generate")) return generate(r);
        if (path.equals("/entry")) return book(r);
        if (path.equals("/move")) {
            r.status = 201;
            return moves.submit(r.needInt("entryId"), r.needInt("roomId"), r.needInt("timeslotId"), true);
        }
        throw new ApiException(404, "Unknown timetable action.");
    }

    @Override
    protected Object delete(Req r) throws Exception {
        if (!r.path().startsWith("/entry/") || r.pathId() == null) throw new ApiException(404, "Unknown timetable action.");
        engine.removeEntry(r.pathId());
        return null;
    }

    private Object book(Req r) throws Exception {
        try {
            Map<String, Object> out = engine.bookEntry(r.needInt("sectionId"), r.needInt("subjectId"), r.needInt("roomId"), r.needInt("timeslotId"));
            r.status = 201;
            return out;
        } catch (ConflictException ce) {
            throw new ApiException(409, ce.getMessage())
                    .with("conflict", ce.getMessage())
                    .with("type", ce.type)
                    .with("suggestion", ce.suggestion)
                    .with("suggestionSlotId", ce.suggestionSlotId);
        }
    }

    @SuppressWarnings("unchecked")
    private Object generate(Req r) throws Exception {
        List<Integer> ids = new ArrayList<>();
        Object many = r.body().get("sectionIds");
        if (many instanceof List) {
            for (Object o : (List<Object>) many) ids.add(((Number) o).intValue());
        } else if (r.intOrNull("sectionId") != null) {
            ids.add(r.intOrNull("sectionId"));
        } else if (r.intOrNull("courseId") != null) {
            String sql = "SELECT id FROM section WHERE course_id = ?" + (r.intOrNull("year") != null ? " AND `year` = ?" : "") + " ORDER BY id";
            List<Map<String, Object>> rows = r.intOrNull("year") != null
                    ? Db.query(sql, r.intOrNull("courseId"), r.intOrNull("year")) : Db.query(sql, r.intOrNull("courseId"));
            for (Map<String, Object> row : rows) ids.add(Db.i(row.get("id")));
        }
        if (ids.isEmpty()) throw new ApiException(400, "Choose a section (or a course and year) to generate.");
        return engine.generate(ids, r.bool("dryRun"));
    }
}
