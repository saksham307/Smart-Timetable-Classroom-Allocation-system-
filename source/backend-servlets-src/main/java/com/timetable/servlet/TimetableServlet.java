package com.timetable.servlet;

import com.timetable.dao.TimetableDAO;
import com.timetable.util.JsonUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/api/timetable")
public class TimetableServlet extends HttpServlet {
    private final TimetableDAO dao = new TimetableDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        try {
            Integer sectionId = parseParam(req.getParameter("sectionId"));
            Integer facultyId = parseParam(req.getParameter("facultyId"));
            resp.getWriter().write(JsonUtil.toJson(dao.findAll(sectionId, facultyId)));
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write(JsonUtil.errorJson(e.getMessage()));
        }
    }

    private Integer parseParam(String s) {
        return (s == null || s.isEmpty()) ? null : Integer.parseInt(s);
    }
}
