package com.timetable.servlet;

import com.timetable.dao.TimetableDAO;
import com.timetable.engine.ConflictException;
import com.timetable.engine.SchedulingEngine;
import com.timetable.util.JsonUtil;
import com.timetable.util.RequestUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet({"/api/timetable/entry", "/api/timetable/entry/*"})
public class TimetableEntryServlet extends HttpServlet {
    private final SchedulingEngine engine = new SchedulingEngine();
    private final TimetableDAO dao = new TimetableDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        Map<String, String> body = JsonUtil.parseFlat(RequestUtil.readBody(req));
        try {
            int sectionId = Integer.parseInt(body.get("sectionId"));
            int subjectId = Integer.parseInt(body.get("subjectId"));
            int facultyId = Integer.parseInt(body.get("facultyId"));
            int roomId = Integer.parseInt(body.get("roomId"));
            int timeslotId = Integer.parseInt(body.get("timeslotId"));

            int newId = engine.bookEntry(sectionId, subjectId, facultyId, roomId, timeslotId);
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("id", newId);
            resp.setStatus(201);
            resp.getWriter().write(JsonUtil.toJson(created));
        } catch (ConflictException ce) {
            resp.setStatus(409);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("conflict", ce.getMessage());
            err.put("type", ce.type);
            err.put("suggestion", ce.suggestion == null ? "" : ce.suggestion);
            resp.getWriter().write(JsonUtil.toJson(err));
        } catch (SQLException e) {
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
