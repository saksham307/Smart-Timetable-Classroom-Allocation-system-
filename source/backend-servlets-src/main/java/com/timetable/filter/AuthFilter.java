package com.timetable.filter;

import com.timetable.util.JsonUtil;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;


@WebFilter("/api/*")
public class AuthFilter implements Filter {

    @Override public void init(FilterConfig f) { }
    @Override public void destroy() { }

    @Override
    @SuppressWarnings("unchecked")
    public void doFilter(ServletRequest rq, ServletResponse rs, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) rq;
        HttpServletResponse res = (HttpServletResponse) rs;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String method = req.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method) || path.equals("/api/login")) {
            chain.doFilter(rq, rs);
            return;
        }

        HttpSession session = req.getSession(false);
        Map<String, Object> user = session == null ? null : (Map<String, Object>) session.getAttribute("user");
        if (user == null) {
            deny(res, 401, "Please sign in.");
            return;
        }
        String role = String.valueOf(user.get("role"));
        boolean admin = role.equals("admin");

        boolean adminOnlyRead = path.startsWith("/api/analytics") || path.startsWith("/api/os") || path.startsWith("/api/tests");
        boolean write = !method.equalsIgnoreCase("GET");
        boolean facultyAvailability = path.startsWith("/api/availability") && role.equals("faculty");

        if (!admin && !path.equals("/api/logout") && !path.equals("/api/me")) {
            if (adminOnlyRead || (write && !facultyAvailability)) {
                deny(res, 403, "Administrator access is required for this action.");
                return;
            }
        }
        chain.doFilter(rq, rs);
    }

    private void deny(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(JsonUtil.errorJson(msg));
    }
}
