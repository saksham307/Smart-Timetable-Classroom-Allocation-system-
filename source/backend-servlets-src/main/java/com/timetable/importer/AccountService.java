package com.timetable.importer;

import com.timetable.util.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Creates the logins that make the Faculty and Student pages work for imported data:
 *  - one teacher login per faculty member (linked by faculty_id, so no exact-name matching is needed),
 *  - one class login per section (linked by section_id) that shows that section's timetable.
 * Idempotent: only teachers / sections that do not have a login yet get one.
 */
public final class AccountService {
    private AccountService() {}

    public static final String FACULTY_PASSWORD = "faculty123";
    public static final String STUDENT_PASSWORD = "student123";

    public static Map<String, Object> createAll(Connection c) throws SQLException {
        Set<String> used = new HashSet<>();
        for (Map<String, Object> r : Db.query(c, "SELECT id FROM users")) used.add(Db.s(r.get("id")).toLowerCase());
        List<Map<String, Object>> created = new ArrayList<>();
        int fac = 0, stu = 0;

        for (Map<String, Object> f : Db.query(c, "SELECT f.id, f.name FROM faculty f WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.faculty_id = f.id) ORDER BY f.id")) {
            String name = Db.s(f.get("name"));
            String id = unique(teacherLogin(name), used);
            Db.update(c, "INSERT INTO users(id,name,password,role,faculty_id) VALUES (?,?,?,'faculty',?)", id, name, FACULTY_PASSWORD, f.get("id"));
            created.add(row("Teacher", id, name, "", FACULTY_PASSWORD));
            fac++;
        }
        for (Map<String, Object> s : Db.query(c, "SELECT s.id, s.name FROM section s WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.section_id = s.id AND u.role = 'student') ORDER BY s.id")) {
            String sec = Db.s(s.get("name"));
            String id = unique(sectionLogin(sec), used);
            Db.update(c, "INSERT INTO users(id,name,password,role,section_id) VALUES (?,?,?,'student',?)", id, "Student (" + sec + ")", STUDENT_PASSWORD, s.get("id"));
            created.add(row("Student", id, "Student (" + sec + ")", sec, STUDENT_PASSWORD));
            stu++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teacherLogins", fac);
        out.put("sectionLogins", stu);
        out.put("created", created);
        return out;
    }

    /** All teacher and class logins (admin page / CSV download). */
    public static List<Map<String, Object>> list(Connection c) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : Db.query(c, "SELECT u.id, u.name, u.role, u.password, s.name AS section FROM users u LEFT JOIN section s ON s.id = u.section_id " +
                "WHERE u.role <> 'admin' ORDER BY u.role, u.name"))
            out.add(row(Db.s(u.get("role")).equals("faculty") ? "Teacher" : "Student", Db.s(u.get("id")), Db.s(u.get("name")),
                    u.get("section") == null ? "" : Db.s(u.get("section")), Db.s(u.get("password"))));
        return out;
    }

    private static Map<String, Object> row(String role, String id, String name, String section, String password) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role); m.put("loginId", id); m.put("name", name); m.put("section", section); m.put("password", password);
        return m;
    }

    /** "Dr. Vikash Tripathi" -> "vikash.tripathi" */
    static String teacherLogin(String name) {
        String n = name.replaceAll("^(?i)(dr|mr|ms|mrs|prof)[.]?[ ]*", "").toLowerCase().replaceAll("[^a-z ]", " ").trim();
        String[] p = n.split("\\s+");
        String id = p.length == 1 ? p[0] : p[0] + "." + p[p.length - 1];
        if (id.isEmpty()) id = "teacher";
        return id.length() > 18 ? id.substring(0, 18) : id;
    }

    /** "5-AI-DS1" -> "5-ai-ds1" */
    static String sectionLogin(String section) {
        String id = section.toLowerCase().replaceAll("[^a-z0-9-]", "");
        if (id.isEmpty()) id = "class";
        return id.length() > 18 ? id.substring(0, 18) : id;
    }

    private static String unique(String base, Set<String> used) {
        String id = base;
        for (int i = 2; used.contains(id.toLowerCase()); i++) id = base + i;
        used.add(id.toLowerCase());
        return id;
    }
}
