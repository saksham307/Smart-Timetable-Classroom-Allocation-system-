package com.timetable.servlet;

import com.timetable.engine.SchedulingEngine;
import com.timetable.util.JsonUtil;
import com.timetable.util.RequestUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

@WebServlet("/api/timetable/generate")
public class GenerateServlet extends HttpServlet {
    private final SchedulingEngine engine = new SchedulingEngine();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        Map<String, String> body = JsonUtil.parseFlat(RequestUtil.readBody(req));
        try {
            int sectionId = Integer.parseInt(body.get("sectionId"));
            Map<String, Object> result = engine.generateForSection(sectionId);
            resp.getWriter().write(JsonUtil.toJson(result));
        } catch (SQLException e) {
            resp.setStatus(500);
            resp.getWriter().write(JsonUtil.errorJson(e.getMessage()));
        }
    }
}
