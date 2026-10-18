package com.timetable.servlet;

import com.timetable.dao.UserDAO;
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

@WebServlet("/api/login")
public class LoginServlet extends HttpServlet {
    private final UserDAO dao = new UserDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        Map<String, String> body = JsonUtil.parseFlat(RequestUtil.readBody(req));
        try {
            Map<String, Object> user = dao.findById(body.get("id"));
            if (user == null) {
                resp.setStatus(404);
                resp.getWriter().write(JsonUtil.errorJson("No user with that ID. Try admin1, fac1, fac2, or stu1."));
                return;
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("user", user);
            resp.getWriter().write(JsonUtil.toJson(wrapper));
        } catch (SQLException e) {
            resp.setStatus(500);
            resp.getWriter().write(JsonUtil.errorJson(e.getMessage()));
        }
    }
}
