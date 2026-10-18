package com.timetable.servlet;

import com.timetable.dao.SubjectDAO;
import com.timetable.util.JsonUtil;
import com.timetable.util.RequestUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet({"/api/subjects", "/api/subjects/*"})
public class SubjectServlet extends HttpServlet {
    private final SubjectDAO dao = new SubjectDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        try {
            resp.getWriter().write(JsonUtil.toJson(dao.findAll()));
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write(JsonUtil.errorJson(e.getMessage()));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        Map<String, String> body = JsonUtil.parseFlat(RequestUtil.readBody(req));
        try {
            int facultyId = Integer.parseInt(body.get("facultyId"));
            boolean isLab = Boolean.parseBoolean(body.get("isLab"));
            int id = dao.insert(body.get("name"), facultyId, isLab);
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("id", id);
            created.put("name", body.get("name"));
            created.put("facultyId", facultyId);
            created.put("isLab", isLab);
            resp.setStatus(201);
            resp.getWriter().write(JsonUtil.toJson(created));
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write(JsonUtil.errorJson(e.getMessage()));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            int id = Integer.parseInt(req.getPathInfo().substring(1));
            dao.delete(id);
            resp.setStatus(204);
        } catch (Exception e) {
            resp.setStatus(500);
        }
    }
}
