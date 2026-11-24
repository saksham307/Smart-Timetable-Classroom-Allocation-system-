package com.timetable.servlet;

import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

/** /api/login (POST), /api/logout (POST), /api/me (GET). Creates a server-side HttpSession. */
@WebServlet({"/api/login", "/api/logout", "/api/me"})
public class LoginServlet extends ApiServlet {

    @Override
    protected Object post(Req r) throws Exception {
        String path = r.raw.getServletPath();
        if (path.equals("/api/logout")) {
            HttpSession s = r.raw.getSession(false);
            if (s != null) s.invalidate();
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            return ok;
        }
        String id = r.needStr("id");
        String pw = r.str("password");
        Map<String, Object> user = pw == null ? null : Db.one(
                "SELECT id, name, role, section_id AS sectionId, faculty_id AS facultyId FROM users WHERE id = ? AND password = ?", id, pw);
        if (user == null) throw new ApiException(401, "Invalid ID or password.");
        r.raw.getSession(true).setAttribute("user", user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        return out;
    }

    @Override
    protected Object get(Req r) {
        Map<String, Object> u = r.user();
        if (u == null) throw new ApiException(401, "Please sign in.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", u);
        return out;
    }
}
