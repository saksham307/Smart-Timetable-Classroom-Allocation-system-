package com.timetable.util;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Every REST endpoint extends this. Subclasses override get/post/put/delete and
 * simply RETURN the object to send as JSON (or throw ApiException for an error).
 * That keeps each servlet short and the error format identical everywhere:
 *    { "error": "message", ...optional extra fields }
 */
public abstract class ApiServlet extends HttpServlet {

    public static class ApiException extends RuntimeException {
        public final int status;
        public final Map<String, Object> extra = new LinkedHashMap<>();

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public ApiException with(String key, Object value) {
            extra.put(key, value);
            return this;
        }
    }

    /** Wrapper around the HTTP request with typed accessors for JSON body / query params. */
    public static class Req {
        public final HttpServletRequest raw;
        public int status = 200;
        private Map<String, Object> body;

        Req(HttpServletRequest raw) { this.raw = raw; }

        public Map<String, Object> body() throws IOException {
            if (body == null) {
                String s = RequestUtil.readBody(raw);
                try {
                    body = s.trim().isEmpty() ? new LinkedHashMap<>() : JsonUtil.parseObject(s);
                } catch (IllegalArgumentException e) {
                    throw new ApiException(400, "Invalid JSON in request: " + e.getMessage());
                }
            }
            return body;
        }

        public String str(String k) throws IOException {
            Object v = body().get(k);
            if (v == null) return null;
            String s = v.toString().trim();
            return s.isEmpty() ? null : s;
        }

        public String needStr(String k) throws IOException {
            String s = str(k);
            if (s == null) throw new ApiException(400, "'" + k + "' is required.");
            return s;
        }

        public Integer intOrNull(String k) throws IOException {
            String s = str(k);
            if (s == null) return null;
            try {
                return (int) Math.round(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "'" + k + "' must be a number.");
            }
        }

        public int needInt(String k) throws IOException {
            Integer v = intOrNull(k);
            if (v == null) throw new ApiException(400, "'" + k + "' is required.");
            return v;
        }

        public boolean bool(String k) throws IOException {
            Object v = body().get(k);
            return v != null && Boolean.parseBoolean(v.toString());
        }

        public String param(String n) {
            String v = raw.getParameter(n);
            return (v == null || v.trim().isEmpty()) ? null : v.trim();
        }

        public Integer intParam(String n) {
            String v = param(n);
            if (v == null) return null;
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new ApiException(400, "'" + n + "' must be a number.");
            }
        }

        public String path() {
            String p = raw.getPathInfo();
            return p == null ? "" : p;
        }

        /** The id in /api/thing/{id}, or null. */
        public Integer pathId() {
            String p = path();
            if (p.length() < 2) return null;
            String last = p.substring(p.lastIndexOf('/') + 1);
            try {
                return Integer.parseInt(last);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> user() {
            HttpSession s = raw.getSession(false);
            return s == null ? null : (Map<String, Object>) s.getAttribute("user");
        }
    }

    protected Object get(Req r) throws Exception { throw new ApiException(405, "GET is not supported here."); }
    protected Object post(Req r) throws Exception { throw new ApiException(405, "POST is not supported here."); }
    protected Object put(Req r) throws Exception { throw new ApiException(405, "PUT is not supported here."); }
    protected Object delete(Req r) throws Exception { throw new ApiException(405, "DELETE is not supported here."); }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException {
        request.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        Req r = new Req(request);
        try {
            Object out;
            switch (request.getMethod()) {
                case "GET": out = get(r); break;
                case "POST": out = post(r); break;
                case "PUT": out = put(r); break;
                case "DELETE": out = delete(r); break;
                case "OPTIONS": return;
                default: throw new ApiException(405, "Method not allowed.");
            }
            if (out == null) {
                resp.setStatus(204);
                return;
            }
            resp.setStatus(r.status);
            resp.getWriter().write(JsonUtil.toJson(out));
        } catch (ApiException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.putAll(e.extra);
            resp.setStatus(e.status);
            resp.getWriter().write(JsonUtil.toJson(m));
        } catch (SQLException e) {
            int status = 500;
            String msg = e.getMessage();
            String state = e.getSQLState() == null ? "" : e.getSQLState();
            if (state.startsWith("23")) {
                status = 409;
                if (e.getErrorCode() == 1062) msg = "That already exists (duplicate value).";
                else if (e.getErrorCode() == 1451) msg = "This record is still in use elsewhere (for example a faculty member assigned to a subject), so it can't be removed. Remove or reassign what depends on it first.";
                else if (e.getErrorCode() == 1452) msg = "A referenced record doesn't exist.";
                else msg = "The database rejected this change (integrity constraint).";
            }
            resp.setStatus(status);
            resp.getWriter().write(JsonUtil.errorJson(msg));
        } catch (Exception e) {
            getServletContext().log("API error", e);
            resp.setStatus(500);
            resp.getWriter().write(JsonUtil.errorJson(String.valueOf(e.getMessage())));
        }
    }
}



for apiservlet.java in util folder.
