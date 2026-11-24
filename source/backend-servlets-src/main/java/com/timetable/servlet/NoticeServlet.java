package com.timetable.servlet;

import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.util.*;

/** Notice board. System events (timetable generated, substitution, exam schedule ...) post here automatically. */
@WebServlet({"/api/notices", "/api/notices/*"})
public class NoticeServlet extends ApiServlet {

    @Override
    protected Object get(Req r) throws Exception {
        Map<String, Object> u = r.user();
        String role = u == null ? "student" : String.valueOf(u.get("role"));
        Integer limit = r.intParam("limit");
        String where = role.equals("admin") ? "" : " WHERE audience IN ('all','" + role + "')";
        return Db.query("SELECT id, title, body, audience, kind, created_by AS createdBy, created_at AS createdAt FROM notice" + where +
                " ORDER BY created_at DESC, id DESC LIMIT " + (limit == null ? 50 : Math.min(limit, 200)));
    }

    @Override
    protected Object post(Req r) throws Exception {
        String title = r.needStr("title");
        String body = r.needStr("body");
        String aud = r.str("audience") == null ? "all" : r.str("audience");
        if (!Arrays.asList("all", "faculty", "student").contains(aud)) throw new ApiException(400, "Audience must be all, faculty or student.");
        Map<String, Object> u = r.user();
        long id = Db.insert("INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                title, body, aud, "announcement", u == null ? "admin" : u.get("name"));
        r.status = 201;
        return Db.one("SELECT id, title, body, audience, kind, created_by AS createdBy, created_at AS createdAt FROM notice WHERE id = ?", id);
    }

    @Override
    protected Object delete(Req r) throws Exception {
        Integer id = r.pathId();
        if (id == null) throw new ApiException(400, "Missing id.");
        Db.update("DELETE FROM notice WHERE id = ?", id);
        return null;
    }
}
