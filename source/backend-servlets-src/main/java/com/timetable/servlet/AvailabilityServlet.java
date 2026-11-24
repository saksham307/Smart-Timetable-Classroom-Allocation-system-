package com.timetable.servlet;

import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.util.*;

/**
 * Faculty availability ("Set availability" in the architecture diagram).
 * A period marked unavailable is never used by the generator or by manual booking.
 *  GET  /api/availability?facultyId=
 *  POST /api/availability {facultyId, timeslotId, unavailable}
 */
@WebServlet("/api/availability")
public class AvailabilityServlet extends ApiServlet {

    private void authorise(Req r, int facultyId) throws Exception {
        Map<String, Object> u = r.user();
        if (u != null && "faculty".equals(u.get("role"))) {
            Map<String, Object> me = Db.one("SELECT id FROM faculty WHERE name = ? ORDER BY id LIMIT 1", u.get("name"));
            if (me == null || Db.i(me.get("id")) != facultyId) throw new ApiException(403, "You can only change your own availability.");
        }
    }

    @Override
    protected Object get(Req r) throws Exception {
        Integer fid = r.intParam("facultyId");
        if (fid == null) throw new ApiException(400, "facultyId is required.");
        return Db.query("SELECT time_slot_id AS timeslotId FROM faculty_unavailable WHERE faculty_id = ?", fid);
    }

    @Override
    protected Object post(Req r) throws Exception {
        int fid = r.needInt("facultyId"), slot = r.needInt("timeslotId");
        authorise(r, fid);
        Map<String, Object> ts = Db.one("SELECT is_break AS brk FROM time_slot WHERE id = ?", slot);
        if (ts == null) throw new ApiException(404, "Period not found.");
        if (Db.b(ts.get("brk"))) throw new ApiException(400, "Breaks can't be marked.");
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.bool("unavailable")) {
            Db.update("INSERT IGNORE INTO faculty_unavailable(faculty_id,time_slot_id) VALUES (?,?)", fid, slot);
            Map<String, Object> has = Db.one("SELECT COUNT(*) AS n FROM timetable_entry te WHERE te.time_slot_id = ? AND (te.faculty_id = ? OR EXISTS " +
                    "(SELECT 1 FROM subject_faculty sf WHERE sf.subject_id = te.subject_id AND sf.faculty_id = ?))", slot, fid, fid);
            if (Db.i(has.get("n")) > 0)
                out.put("warning", "You already have a class in this period. It stays scheduled; ask the admin for a substitution.");
        } else {
            Db.update("DELETE FROM faculty_unavailable WHERE faculty_id = ? AND time_slot_id = ?", fid, slot);
        }
        out.put("ok", true);
        return out;
    }
}
