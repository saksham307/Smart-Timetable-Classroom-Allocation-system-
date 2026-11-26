package com.timetable.servlet;

import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.util.*;


@WebServlet({"/api/rooms", "/api/rooms/*", "/api/faculty", "/api/faculty/*",
             "/api/courses", "/api/courses/*", "/api/sections", "/api/sections/*"})
public class MasterServlet extends ApiServlet {

    
    private static class F {
        final String key, col, type;
        F(String key, String col, String type) { this.key = key; this.col = col; this.type = type; }
    }

    private static class Res {
        String table, select, orderBy;
        F[] fields;
        String[][] filters = {};          
        Res(String table, String select, String orderBy, F... fields) {
            this.table = table; this.select = select; this.orderBy = orderBy; this.fields = fields;
        }
    }

    private static final Map<String, Res> RES = new HashMap<>();

    static {
        RES.put("/api/rooms", new Res("room", "SELECT t.id, t.name, t.type, t.capacity FROM room t", "t.name",
                new F("name", "name", "s"), new F("type", "type", "s"), new F("capacity", "capacity", "i")));
        RES.put("/api/faculty", new Res("faculty", "SELECT t.id, t.name FROM faculty t", "t.name",
                new F("name", "name", "s")));
        RES.put("/api/courses", new Res("course", "SELECT t.id, t.name, t.duration_years AS durationYears FROM course t", "t.name",
                new F("name", "name", "s"), new F("durationYears", "duration_years", "i")));
        Res sec = new Res("section",
                "SELECT t.id, t.name, t.course_id AS courseId, c.name AS courseName, t.`year` AS `year`, COALESCE(t.semester, t.`year` * 2) AS semester, t.strength " +
                "FROM section t LEFT JOIN course c ON c.id = t.course_id", "c.name, t.`year`, t.name",
                new F("name", "name", "s"), new F("courseId", "course_id", "i"), new F("year", "`year`", "i"), new F("strength", "strength", "i"));
        sec.filters = new String[][]{{"courseId", "t.course_id"}, {"year", "t.`year`"}};
        RES.put("/api/sections", sec);
    }

    private Res res(Req r) {
        Res x = RES.get(r.raw.getServletPath());
        if (x == null) throw new ApiException(404, "Unknown resource.");
        return x;
    }

    @Override
    protected Object get(Req r) throws Exception {
        Res x = res(r);
        StringBuilder sql = new StringBuilder(x.select);
        List<Object> p = new ArrayList<>();
        String glue = " WHERE ";
        for (String[] f : x.filters) {
            Integer v = r.intParam(f[0]);
            if (v != null) { sql.append(glue).append(f[1]).append(" = ?"); p.add(v); glue = " AND "; }
        }
        sql.append(" ORDER BY ").append(x.orderBy);
        return Db.query(sql.toString(), p.toArray());
    }

    @Override
    protected Object post(Req r) throws Exception {
        Res x = res(r);
        Map<String, Object> values = collect(r, x, true);
        validate(r, x, values);
        StringBuilder cols = new StringBuilder(), qs = new StringBuilder();
        List<Object> p = new ArrayList<>();
        for (F f : x.fields) {
            cols.append(cols.length() > 0 ? "," : "").append(f.col);
            qs.append(qs.length() > 0 ? "," : "").append("?");
            p.add(values.get(f.key));
        }
        long id = Db.insert("INSERT INTO " + x.table + "(" + cols + ") VALUES (" + qs + ")", p.toArray());
        r.status = 201;
        return Db.one(x.select + " WHERE t.id = ?", id);
    }

    @Override
    protected Object put(Req r) throws Exception {
        Res x = res(r);
        Integer id = r.pathId();
        if (id == null) throw new ApiException(400, "Missing id.");
        Map<String, Object> values = collect(r, x, false);
        // merge with the stored row so partial updates still validate correctly
        Map<String, Object> current = Db.one(x.select + " WHERE t.id = ?", id);
        if (current == null) throw new ApiException(404, "Record not found.");
        Map<String, Object> merged = new LinkedHashMap<>();
        for (F f : x.fields) merged.put(f.key, values.containsKey(f.key) ? values.get(f.key) : current.get(f.key));
        validate(r, x, merged);
        StringBuilder set = new StringBuilder();
        List<Object> p = new ArrayList<>();
        for (F f : x.fields) {
            if (!values.containsKey(f.key)) continue;
            set.append(set.length() > 0 ? "," : "").append(f.col).append("=?");
            p.add(values.get(f.key));
        }
        if (set.length() == 0) return current;
        p.add(id);
        Db.update("UPDATE " + x.table + " t SET " + set.toString().replace(",", ",") + " WHERE t.id = ?", p.toArray());
        return Db.one(x.select + " WHERE t.id = ?", id);
    }

    @Override
    protected Object delete(Req r) throws Exception {
        Res x = res(r);
        Integer id = r.pathId();
        if (id == null) throw new ApiException(400, "Missing id.");
        if (x.table.equals("course")) {
            Map<String, Object> use = Db.one("SELECT (SELECT COUNT(*) FROM section WHERE course_id = ?) AS secs, (SELECT COUNT(*) FROM subject WHERE course_id = ?) AS subs", id, id);
            if (Db.i(use.get("secs")) + Db.i(use.get("subs")) > 0)
                throw new ApiException(409, "This course still has " + use.get("secs") + " section(s) and " + use.get("subs") + " subject(s). Remove or move them first.");
        }
        Db.update("DELETE FROM " + x.table + " WHERE id = ?", id);
        return null;
    }

    /** read the writable fields out of the JSON body with type checks */
    private Map<String, Object> collect(Req r, Res x, boolean all) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (F f : x.fields) {
            if (!all && !r.body().containsKey(f.key)) continue;
            switch (f.type) {
                case "s":
                    out.put(f.key, r.needStr(f.key));
                    break;
                case "i":
                    out.put(f.key, r.needInt(f.key));
                    break;
                default:
                    out.put(f.key, r.intOrNull(f.key));
            }
        }
        return out;
    }

    private void validate(Req r, Res x, Map<String, Object> v) throws Exception {
        switch (x.table) {
            case "room":
                if (!"Lecture".equals(v.get("type")) && !"Lab".equals(v.get("type")))
                    throw new ApiException(400, "Room type must be 'Lecture' or 'Lab'.");
                if (Db.i(v.get("capacity")) < 1) throw new ApiException(400, "Capacity must be at least 1.");
                break;
            case "course":
                int d = Db.i(v.get("durationYears"));
                if (d < 1 || d > 6) throw new ApiException(400, "Course duration must be between 1 and 6 years.");
                break;
            case "section":
                int yr = Db.i(v.get("year"));
                if (yr != 2 && yr != 3) throw new ApiException(400, "Only Year 2 and Year 3 timetables are enabled right now.");
                Map<String, Object> c = Db.one("SELECT duration_years AS d FROM course WHERE id = ?", v.get("courseId"));
                if (c == null) throw new ApiException(400, "Choose a valid course.");
                if (yr > Db.i(c.get("d"))) throw new ApiException(400, "That course only runs for " + c.get("d") + " years.");
                if (Db.i(v.get("strength")) < 1) throw new ApiException(400, "Section strength must be at least 1.");
                break;
            default:
        }
    }
}
