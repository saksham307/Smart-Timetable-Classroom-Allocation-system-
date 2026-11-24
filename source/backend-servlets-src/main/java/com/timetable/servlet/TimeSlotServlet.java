package com.timetable.servlet;

import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.util.List;
import java.util.Map;

/** /api/timeslots - the weekly grid including the fixed break / lunch rows (isBreak = true). */
@WebServlet("/api/timeslots")
public class TimeSlotServlet extends ApiServlet {
    @Override
    protected Object get(Req r) throws Exception {
        List<Map<String, Object>> rows = Db.query(
                "SELECT id, day, TIME_FORMAT(start_time,'%H:%i') AS st, TIME_FORMAT(end_time,'%H:%i') AS et, is_break AS isBreak, label " +
                "FROM time_slot ORDER BY FIELD(day,'Mon','Tue','Wed','Thu','Fri','Sat'), start_time");
        for (Map<String, Object> m : rows) {
            m.put("time", m.get("st") + "-" + m.get("et"));
            m.remove("st");
            m.remove("et");
        }
        return rows;
    }
}
