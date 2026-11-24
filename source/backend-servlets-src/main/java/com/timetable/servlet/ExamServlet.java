package com.timetable.servlet;

import com.timetable.engine.ExamService;
import com.timetable.util.ApiServlet;
import com.timetable.util.Db;

import javax.servlet.annotation.WebServlet;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 *  GET  /api/exams?courseId=&year=&sectionId=      exam timetable (+ halls; for a section: exactly where it sits)
 *  GET  /api/exams/seating?examId=                 hall-wise seating chart
 *  POST /api/exams/generate  {courseId, year, startDate}
 *  DELETE /api/exams?courseId=&year=               clear a course-year's exams
 */
@WebServlet({"/api/exams", "/api/exams/*"})
public class ExamServlet extends ApiServlet {
    private final ExamService svc = new ExamService();

    @Override
    protected Object get(Req r) throws Exception {
        if (r.path().equals("/seating")) {
            Integer id = r.intParam("examId");
            if (id == null) throw new ApiException(400, "examId is required.");
            return svc.seating(id);
        }
        return svc.list(r.intParam("courseId"), r.intParam("year"), r.intParam("sectionId"));
    }

    @Override
    protected Object post(Req r) throws Exception {
        if (!r.path().equals("/generate")) throw new ApiException(404, "Unknown exam action.");
        int year = r.needInt("year");
        if (year != 2 && year != 3) throw new ApiException(400, "Only Year 2 and Year 3 are enabled right now.");
        LocalDate start;
        try {
            start = LocalDate.parse(r.needStr("startDate"));
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "Start date must look like 2026-11-16.");
        }
        r.status = 201;
        return svc.generate(r.needInt("courseId"), year, start);
    }

    @Override
    protected Object delete(Req r) throws Exception {
        Integer c = r.intParam("courseId"), y = r.intParam("year");
        if (c == null || y == null) throw new ApiException(400, "courseId and year are required.");
        Db.update("DELETE FROM exam WHERE course_id = ? AND `year` = ?", c, y);
        return null;
    }
}
