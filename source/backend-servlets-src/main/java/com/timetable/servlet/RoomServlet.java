package com.timetable.servlet;

import com.timetable.dao.RoomDAO;
import com.timetable.util.JsonUtil;
import com.timetable.util.RequestUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet({"/api/rooms", "/api/rooms/*"})
public class RoomServlet extends HttpServlet {
    private final RoomDAO dao = new RoomDAO();

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
            int capacity = Integer.parseInt(body.get("capacity"));
            int id = dao.insert(body.get("name"), body.get("type"), capacity);
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("id", id);
            created.put("name", body.get("name"));
            created.put("type", body.get("type"));
            created.put("capacity", capacity);
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
