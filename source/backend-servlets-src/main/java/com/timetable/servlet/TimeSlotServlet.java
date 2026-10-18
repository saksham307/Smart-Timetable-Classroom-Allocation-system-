package com.timetable.servlet;

import com.timetable.dao.TimeSlotDAO;
import com.timetable.util.JsonUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/api/timeslots")
public class TimeSlotServlet extends HttpServlet {
    private final TimeSlotDAO dao = new TimeSlotDAO();

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
}
