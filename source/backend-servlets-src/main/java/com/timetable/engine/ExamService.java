package com.timetable.engine;

import com.timetable.util.ApiServlet.ApiException;
import com.timetable.util.DBUtil;
import com.timetable.util.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;


public class ExamService {

    public Map<String, Object> generate(int courseId, int year, LocalDate start) throws SQLException {
        synchronized (SchedulingEngine.LOCK) {
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Map<String, Object> course = Db.one(c, "SELECT id, name FROM course WHERE id = ?", courseId);
                    if (course == null) throw new ApiException(404, "Course not found.");
                    List<Map<String, Object>> subjects = Db.query(c,
                            "SELECT id, name, is_lab AS isLab FROM subject WHERE course_id = ? AND `year` = ? ORDER BY is_lab, name", courseId, year);
                    List<Map<String, Object>> sections = Db.query(c,
                            "SELECT id, name, strength FROM section WHERE course_id = ? AND `year` = ? ORDER BY id", courseId, year);
                    if (subjects.isEmpty()) throw new ApiException(400, "This course/year has no subjects yet.");
                    if (sections.isEmpty()) throw new ApiException(400, "This course/year has no sections yet.");

                    Db.update(c, "DELETE FROM exam WHERE course_id = ? AND `year` = ?", courseId, year);   

                    List<Map<String, Object>> rooms = Db.query(c,
                            "SELECT id, name, capacity FROM room ORDER BY FIELD(type,'Lecture','Lab'), capacity DESC, id");

                    LocalDate d = start;
                    int unseatedTotal = 0;
                    List<Map<String, Object>> created = new ArrayList<>();
                    for (Map<String, Object> sub : subjects) {
                        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
                        boolean lab = Db.b(sub.get("isLab"));
                        String st = lab ? "14:00:00" : "10:00:00";
                        String et = lab ? "17:00:00" : "13:00:00";
                        long examId = Db.insert(c, "INSERT INTO exam(subject_id,course_id,`year`,exam_date,start_time,end_time) VALUES (?,?,?,?,?,?)",
                                sub.get("id"), courseId, year, java.sql.Date.valueOf(d), st, et);
                        int unseated = seat(c, (int) examId, d, st, et, sections, rooms);
                        unseatedTotal += unseated;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("subject", sub.get("name"));
                        row.put("date", d.toString());
                        row.put("unseated", unseated);
                        created.add(row);
                        d = d.plusDays(1);
                    }
                    Db.update(c, "INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                            "Exam schedule published: " + course.get("name") + " Year " + year,
                            "Exams for " + course.get("name") + " Year " + year + " run from " + start + ". See the Exams page for dates, halls and seat numbers.",
                            "all", "change", "system");
                    c.commit();

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("exams", created);
                    out.put("unseated", unseatedTotal);
                    out.put("message", unseatedTotal == 0 ? "Exam timetable and seating created." :
                            "Exam timetable created, but " + unseatedTotal + " seat(s) are missing: add more rooms or capacity.");
                    return out;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }
    }

    
    private int seat(Connection c, int examId, LocalDate date, String st, String et,
                     List<Map<String, Object>> sections, List<Map<String, Object>> rooms) throws SQLException {
        
        Set<Integer> busy = new HashSet<>();
        for (Map<String, Object> r : Db.query(c, "SELECT DISTINCT es.room_id AS rid FROM exam_seat es JOIN exam e ON e.id = es.exam_id " +
                "WHERE e.exam_date = ? AND e.start_time < ? AND e.end_time > ? AND e.id <> ?", java.sql.Date.valueOf(date), et, st, examId))
            busy.add(Db.i(r.get("rid")));

        
        List<Deque<String[]>> queues = new ArrayList<>();
        for (Map<String, Object> s : sections) {
            Deque<String[]> q = new ArrayDeque<>();
            int strength = Db.i(s.get("strength"));
            for (int k = 1; k <= strength; k++) q.add(new String[]{String.valueOf(s.get("id")), s.get("name") + "-" + String.format("%03d", k)});
            queues.add(q);
        }
        List<String[]> students = new ArrayList<>();
        boolean any = true;
        while (any) {
            any = false;
            for (Deque<String[]> q : queues) if (!q.isEmpty()) { students.add(q.poll()); any = true; }
        }

        int next = 0;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO exam_seat(exam_id,room_id,seat_no,section_id,roll_no) VALUES (?,?,?,?,?)")) {
            for (Map<String, Object> room : rooms) {
                if (next >= students.size()) break;
                int rid = Db.i(room.get("id"));
                if (busy.contains(rid)) continue;
                int cap = Db.i(room.get("capacity"));
                for (int seatNo = 1; seatNo <= cap && next < students.size(); seatNo++) {
                    String[] stu = students.get(next++);
                    ps.setInt(1, examId);
                    ps.setInt(2, rid);
                    ps.setInt(3, seatNo);
                    ps.setInt(4, Integer.parseInt(stu[0]));
                    ps.setString(5, stu[1]);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        return students.size() - next;
    }

    
    public List<Map<String, Object>> list(Integer courseId, Integer year, Integer sectionId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT e.id AS id, e.exam_date AS examDate, DAYNAME(e.exam_date) AS weekday, " +
                "TIME_FORMAT(e.start_time,'%H:%i') AS startTime, TIME_FORMAT(e.end_time,'%H:%i') AS endTime, s.name AS subject, s.is_lab AS isLab, " +
                "c.name AS courseName, e.course_id AS courseId, e.`year` AS yr, " +
                "(SELECT COUNT(*) FROM exam_seat es WHERE es.exam_id = e.id) AS seats " +
                "FROM exam e JOIN subject s ON s.id = e.subject_id JOIN course c ON c.id = e.course_id WHERE 1=1");
        List<Object> p = new ArrayList<>();
        if (courseId != null) { sql.append(" AND e.course_id = ?"); p.add(courseId); }
        if (year != null) { sql.append(" AND e.`year` = ?"); p.add(year); }
        sql.append(" ORDER BY e.course_id, e.`year`, e.exam_date, e.start_time");
        List<Map<String, Object>> rows = Db.query(sql.toString(), p.toArray());

        
        Map<Integer, List<String>> halls = new HashMap<>();
        String hallSql = "SELECT es.exam_id AS eid, r.name AS room, MIN(es.seat_no) AS mn, MAX(es.seat_no) AS mx, COUNT(*) AS cnt " +
                "FROM exam_seat es JOIN room r ON r.id = es.room_id " + (sectionId != null ? "WHERE es.section_id = ? " : "") +
                "GROUP BY es.exam_id, r.name ORDER BY es.exam_id, r.name";
        List<Map<String, Object>> hs = sectionId != null ? Db.query(hallSql, sectionId) : Db.query(hallSql);
        for (Map<String, Object> h : hs) {
            halls.computeIfAbsent(Db.i(h.get("eid")), k -> new ArrayList<>())
                    .add(h.get("room") + " (seats " + h.get("mn") + "-" + h.get("mx") + ")");
        }
        for (Map<String, Object> r : rows) {
            r.put("year", r.remove("yr"));
            r.put("halls", halls.getOrDefault(Db.i(r.get("id")), new ArrayList<>()));
        }
        return rows;
    }

    public List<Map<String, Object>> seating(int examId) throws SQLException {
        Map<String, Map<String, Object>> byRoom = new LinkedHashMap<>();
        for (Map<String, Object> r : Db.query("SELECT r.name AS room, r.capacity AS capacity, es.seat_no AS seatNo, es.roll_no AS rollNo, sec.name AS section " +
                "FROM exam_seat es JOIN room r ON r.id = es.room_id JOIN section sec ON sec.id = es.section_id " +
                "WHERE es.exam_id = ? ORDER BY r.name, es.seat_no", examId)) {
            String room = Db.s(r.get("room"));
            Map<String, Object> g = byRoom.get(room);
            if (g == null) {
                g = new LinkedHashMap<>();
                g.put("room", room);
                g.put("capacity", r.get("capacity"));
                g.put("seats", new ArrayList<Map<String, Object>>());
                byRoom.put(room, g);
            }
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("seatNo", r.get("seatNo"));
            seat.put("rollNo", r.get("rollNo"));
            seat.put("section", r.get("section"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> seats = (List<Map<String, Object>>) g.get("seats");
            seats.add(seat);
        }
        return new ArrayList<>(byRoom.values());
    }
}
