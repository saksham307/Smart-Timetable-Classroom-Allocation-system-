package com.timetable.servlet;

import com.timetable.util.ApiServlet;
import com.timetable.util.DBUtil;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.sql.Connection;
import java.util.*;

/**
 * /api/subjects  - a subject belongs to a course + year, has a primary faculty, a number of periods per week,
 * and (for practicals) any number of EXTRA faculty who must all be free for the lab session.
 */
@WebServlet({"/api/subjects", "/api/subjects/*"})
public class SubjectServlet extends ApiServlet {

    private static final String SELECT =
            "SELECT s.id, s.name, s.faculty_id AS facultyId, f.name AS facultyName, s.is_lab AS isLab, " +
            "s.course_id AS courseId, c.name AS courseName, s.`year` AS `year`, s.section_id AS sectionId, COALESCE(s.semester, s.`year` * 2) AS semester, s.periods_per_week AS periodsPerWeek, " +
            "(SELECT GROUP_CONCAT(sf.faculty_id ORDER BY sf.faculty_id SEPARATOR ',') FROM subject_faculty sf WHERE sf.subject_id = s.id) AS extraFacultyIds, " +
            "(SELECT GROUP_CONCAT(f2.name ORDER BY f2.id SEPARATOR ', ') FROM subject_faculty sf2 JOIN faculty f2 ON f2.id = sf2.faculty_id WHERE sf2.subject_id = s.id) AS extraFaculty " +
            "FROM subject s JOIN faculty f ON f.id = s.faculty_id LEFT JOIN course c ON c.id = s.course_id";

    @Override
    protected Object get(Req r) throws Exception {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> p = new ArrayList<>();
        String glue = " WHERE ";
        Integer course = r.intParam("courseId"), year = r.intParam("year");
        if (course != null) { sql.append(glue).append("s.course_id = ?"); p.add(course); glue = " AND "; }
        if (year != null) { sql.append(glue).append("s.`year` = ?"); p.add(year); glue = " AND "; }
        // only the subjects that really belong to one section: its own section-specific subjects, or general
        // subjects of the same course, year and semester (the same rule the generator and the booking check use)
        Integer sectionId = r.intParam("sectionId");
        if (sectionId != null) {
            sql.append(glue).append("IF(s.section_id IS NOT NULL, s.section_id = ?, EXISTS (SELECT 1 FROM section sec WHERE sec.id = ? " +
                    "AND s.course_id <=> sec.course_id AND s.`year` <=> sec.`year` " +
                    "AND COALESCE(s.semester, s.`year` * 2) <=> COALESCE(sec.semester, sec.`year` * 2)))");
            p.add(sectionId); p.add(sectionId);
        }
        sql.append(" ORDER BY c.name, s.`year`, s.is_lab, s.name");
        return Db.query(sql.toString(), p.toArray());
    }

    @Override
    protected Object post(Req r) throws Exception { return save(r, null); }

    @Override
    protected Object put(Req r) throws Exception {
        Integer id = r.pathId();
        if (id == null) throw new ApiException(400, "Missing id.");
        return save(r, id);
    }

    private Object save(Req r, Integer id) throws Exception {
        String name = r.needStr("name");
        int facultyId = r.needInt("facultyId");
        boolean lab = r.bool("isLab");
        int courseId = r.needInt("courseId");
        int year = r.needInt("year");
        int ppw = r.needInt("periodsPerWeek");
        String extra = r.str("extraFacultyIds");

        if (year != 2 && year != 3) throw new ApiException(400, "Only Year 2 and Year 3 are enabled right now.");
        if (ppw < 1 || ppw > 6) throw new ApiException(400, "Periods per week must be between 1 and 6.");
        Map<String, Object> course = Db.one("SELECT duration_years AS d FROM course WHERE id = ?", courseId);
        if (course == null) throw new ApiException(400, "Choose a valid course.");
        if (year > Db.i(course.get("d"))) throw new ApiException(400, "That course only runs for " + course.get("d") + " years.");

        List<Integer> extras = new ArrayList<>();
        if (extra != null) for (String s : extra.split(",")) if (!s.trim().isEmpty()) {
            int f = Integer.parseInt(s.trim());
            if (f != facultyId && !extras.contains(f)) extras.add(f);
        }
        if (!lab && !extras.isEmpty()) throw new ApiException(400, "Additional faculty can only be assigned to practical (lab) subjects.");

        try (Connection c = DBUtil.getConnection()) {
            c.setAutoCommit(false);
            try {
                long sid;
                if (id == null) {
                    sid = Db.insert(c, "INSERT INTO subject(name,faculty_id,is_lab,course_id,`year`,periods_per_week) VALUES (?,?,?,?,?,?)",
                            name, facultyId, lab, courseId, year, ppw);
                    r.status = 201;
                } else {
                    sid = id;
                    if (Db.update(c, "UPDATE subject SET name=?, faculty_id=?, is_lab=?, course_id=?, `year`=?, periods_per_week=? WHERE id=?",
                            name, facultyId, lab, courseId, year, ppw, id) == 0 && Db.one(c, "SELECT 1 AS x FROM subject WHERE id = ?", id) == null)
                        throw new ApiException(404, "Subject not found.");
                    Db.update(c, "DELETE FROM subject_faculty WHERE subject_id = ?", id);
                }
                for (int f : extras) Db.update(c, "INSERT INTO subject_faculty(subject_id,faculty_id) VALUES (?,?)", sid, f);
                c.commit();
                return Db.one(SELECT + " WHERE s.id = ?", sid);
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override
    protected Object delete(Req r) throws Exception {
        Integer id = r.pathId();
        if (id == null) throw new ApiException(400, "Missing id.");
        Db.update("DELETE FROM subject WHERE id = ?", id);
        return null;
    }
}
