package com.timetable.listener;

import com.timetable.util.DBUtil;
import com.timetable.util.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs every time the app starts. Idempotent: it only creates / adds what is missing,
 * so a brand-new MySQL, the old schema from earlier versions, and an already-upgraded
 * database all end up with exactly the same final structure.
 *
 *   1. CREATE DATABASE + CREATE TABLE IF NOT EXISTS   (fresh install)
 *   2. ALTER TABLE ... ADD COLUMN when a column is missing   (old install)
 *   3. static data: time slots incl. fixed break/lunch rows, base login accounts
 *   4. demo data: courses, years 2-3, sections, subjects ... only ONCE (flag in app_meta)
 */
public class SchemaMigrator {

    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri"};
    private static final String[][] PERIODS = {
            {"09:00", "10:00"}, {"10:00", "11:00"}, {"11:15", "12:15"},
            {"12:15", "13:15"}, {"14:00", "15:00"}, {"15:00", "16:00"}};
    private static final String[][] BREAKS = {
            {"11:00", "11:15", "Short Break"}, {"13:15", "14:00", "Lunch Break"}};

    public static void run(Consumer<String> log) {
        try (Connection s = DBUtil.getServerConnection(); Statement st = s.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + DBUtil.dbName() + "`");
        } catch (SQLException e) {
            log.accept("[Schema] Could not create/open database: " + e.getMessage());
            return;
        }
        try (Connection c = DBUtil.getConnection()) {
            createTables(c);
            upgradeOldTables(c, log);
            seedStatic(c);
            if (Db.one(c, "SELECT v FROM app_meta WHERE k='seed_v2'") == null) {
                seedDemo(c, log);
                Db.update(c, "INSERT IGNORE INTO app_meta(k,v) VALUES('seed_v2','done')");
            }
            log.accept("[Schema] Database verified / upgraded.");
        } catch (SQLException e) {
            log.accept("[Schema] Migration failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ DDL
    private static void createTables(Connection c) throws SQLException {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS app_meta (k VARCHAR(40) PRIMARY KEY, v VARCHAR(100))",
            "CREATE TABLE IF NOT EXISTS users (id VARCHAR(20) PRIMARY KEY, name VARCHAR(100) NOT NULL, " +
                "password VARCHAR(100) NOT NULL DEFAULT 'changeme', role ENUM('admin','faculty','student') NOT NULL, section_id INT NULL)",
            "CREATE TABLE IF NOT EXISTS room (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(20) NOT NULL UNIQUE, " +
                "type ENUM('Lecture','Lab') NOT NULL, capacity INT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS faculty (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS course (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, " +
                "duration_years INT NOT NULL DEFAULT 4)",
            "CREATE TABLE IF NOT EXISTS section (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(30) NOT NULL UNIQUE, " +
                "course_id INT NULL, `year` INT NULL, strength INT NOT NULL DEFAULT 60, " +
                "CONSTRAINT fk_section_course FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE SET NULL)",
            "CREATE TABLE IF NOT EXISTS subject (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, " +
                "faculty_id INT NOT NULL, is_lab BOOLEAN NOT NULL DEFAULT 0, course_id INT NULL, `year` INT NULL, " +
                "periods_per_week INT NOT NULL DEFAULT 3, " +
                "CONSTRAINT fk_subject_faculty FOREIGN KEY (faculty_id) REFERENCES faculty(id), " +
                "CONSTRAINT fk_subject_course FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE SET NULL)",
            "CREATE TABLE IF NOT EXISTS subject_faculty (subject_id INT NOT NULL, faculty_id INT NOT NULL, " +
                "PRIMARY KEY (subject_id, faculty_id), " +
                "FOREIGN KEY (subject_id) REFERENCES subject(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (faculty_id) REFERENCES faculty(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS time_slot (id INT AUTO_INCREMENT PRIMARY KEY, " +
                "day ENUM('Mon','Tue','Wed','Thu','Fri') NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL, " +
                "is_break BOOLEAN NOT NULL DEFAULT 0, label VARCHAR(40) NULL, UNIQUE KEY uq_day_time (day, start_time))",
            "CREATE TABLE IF NOT EXISTS timetable_entry (id INT AUTO_INCREMENT PRIMARY KEY, section_id INT NOT NULL, " +
                "subject_id INT NOT NULL, faculty_id INT NOT NULL, room_id INT NOT NULL, time_slot_id INT NOT NULL, " +
                "group_id INT NULL, " +
                "FOREIGN KEY (section_id) REFERENCES section(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (subject_id) REFERENCES subject(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (faculty_id) REFERENCES faculty(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (room_id) REFERENCES room(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (time_slot_id) REFERENCES time_slot(id) ON DELETE CASCADE, " +
                "UNIQUE KEY uq_room_slot (room_id, time_slot_id), " +
                "UNIQUE KEY uq_faculty_slot (faculty_id, time_slot_id), " +
                "UNIQUE KEY uq_section_slot (section_id, time_slot_id))",
            "CREATE TABLE IF NOT EXISTS faculty_unavailable (faculty_id INT NOT NULL, time_slot_id INT NOT NULL, " +
                "PRIMARY KEY (faculty_id, time_slot_id), " +
                "FOREIGN KEY (faculty_id) REFERENCES faculty(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (time_slot_id) REFERENCES time_slot(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS substitution (id INT AUTO_INCREMENT PRIMARY KEY, entry_id INT NOT NULL, " +
                "sub_date DATE NOT NULL, substitute_faculty_id INT NOT NULL, reason VARCHAR(200), " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_entry_date (entry_id, sub_date), " +
                "FOREIGN KEY (entry_id) REFERENCES timetable_entry(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (substitute_faculty_id) REFERENCES faculty(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS notice (id INT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(150) NOT NULL, " +
                "body TEXT NOT NULL, audience ENUM('all','faculty','student') NOT NULL DEFAULT 'all', " +
                "kind ENUM('announcement','change') NOT NULL DEFAULT 'announcement', " +
                "created_by VARCHAR(60) NOT NULL DEFAULT 'system', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS exam (id INT AUTO_INCREMENT PRIMARY KEY, subject_id INT NOT NULL, course_id INT NOT NULL, " +
                "`year` INT NOT NULL, exam_date DATE NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL, " +
                "FOREIGN KEY (subject_id) REFERENCES subject(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS exam_seat (id INT AUTO_INCREMENT PRIMARY KEY, exam_id INT NOT NULL, room_id INT NOT NULL, " +
                "seat_no INT NOT NULL, section_id INT NOT NULL, roll_no VARCHAR(40) NOT NULL, " +
                "UNIQUE KEY uq_exam_room_seat (exam_id, room_id, seat_no), UNIQUE KEY uq_exam_roll (exam_id, roll_no), " +
                "FOREIGN KEY (exam_id) REFERENCES exam(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (room_id) REFERENCES room(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (section_id) REFERENCES section(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS move_request (id INT AUTO_INCREMENT PRIMARY KEY, entry_id INT NOT NULL, " +
                "target_room_id INT NOT NULL, target_slot_id INT NOT NULL, " +
                "status ENUM('WAITING','GRANTED','CANCELLED') NOT NULL DEFAULT 'WAITING', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (entry_id) REFERENCES timetable_entry(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (target_room_id) REFERENCES room(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (target_slot_id) REFERENCES time_slot(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS conflict_log (id INT AUTO_INCREMENT PRIMARY KEY, kind VARCHAR(20) NOT NULL, " +
                "message VARCHAR(300) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        };
        try (Statement st = c.createStatement()) {
            for (String sql : ddl) st.execute(sql);
        }
    }

    // ------------------------------------------------------- old -> new columns
    private static boolean hasColumn(Connection c, String table, String col) throws SQLException {
        return Db.i(Db.one(c, "SELECT COUNT(*) AS n FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", table, col).get("n")) > 0;
    }

    private static boolean addColumn(Connection c, String table, String col, String def) throws SQLException {
        if (hasColumn(c, table, col)) return false;
        Db.update(c, "ALTER TABLE " + table + " ADD COLUMN " + def);
        return true;
    }

    private static void tryExec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException ignored) { /* constraint already exists etc. */ }
    }

    private static void upgradeOldTables(Connection c, Consumer<String> log) throws SQLException {
        addColumn(c, "users", "password", "password VARCHAR(100) NOT NULL DEFAULT 'changeme'");

        if (addColumn(c, "section", "course_id", "course_id INT NULL"))
            tryExec(c, "ALTER TABLE section ADD CONSTRAINT fk_section_course FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE SET NULL");
        addColumn(c, "section", "year", "`year` INT NULL");
        addColumn(c, "section", "strength", "strength INT NOT NULL DEFAULT 60");
        tryExec(c, "ALTER TABLE section MODIFY name VARCHAR(30) NOT NULL");

        if (addColumn(c, "subject", "course_id", "course_id INT NULL"))
            tryExec(c, "ALTER TABLE subject ADD CONSTRAINT fk_subject_course FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE SET NULL");
        addColumn(c, "subject", "year", "`year` INT NULL");
        if (addColumn(c, "subject", "periods_per_week", "periods_per_week INT NOT NULL DEFAULT 3"))
            Db.update(c, "UPDATE subject SET periods_per_week = 1 WHERE is_lab = 1");

        addColumn(c, "time_slot", "is_break", "is_break BOOLEAN NOT NULL DEFAULT 0");
        addColumn(c, "time_slot", "label", "label VARCHAR(40) NULL");
        addColumn(c, "timetable_entry", "group_id", "group_id INT NULL");
    }

    // ----------------------------------------------------------- static rows
    private static void seedStatic(Connection c) throws SQLException {
        for (String d : DAYS) {
            for (String[] p : PERIODS)
                Db.update(c, "INSERT IGNORE INTO time_slot(day,start_time,end_time) VALUES (?,?,?)", d, p[0], p[1]);
            for (String[] b : BREAKS)
                Db.update(c, "INSERT IGNORE INTO time_slot(day,start_time,end_time,is_break,label) VALUES (?,?,?,1,?)", d, b[0], b[1], b[2]);
        }
        // the four team admins + two original demo logins
        String[][] users = {
                {"admin1", "Mohit Singh", "admin123", "admin"}, {"admin2", "Saksham Pant", "admin123", "admin"},
                {"admin3", "Jatin Singh", "admin123", "admin"}, {"admin4", "Vedika Sharma", "admin123", "admin"},
                {"fac1", "Dr. A. Verma", "faculty123", "faculty"}, {"fac2", "Dr. R. Kapoor", "faculty123", "faculty"}};
        for (String[] u : users)
            Db.update(c, "INSERT IGNORE INTO users(id,name,password,role) VALUES (?,?,?,?)", u[0], u[1], u[2], u[3]);
    }

    // ------------------------------------------------------------- demo data
    private static Integer id(Connection c, String sql, Object... p) throws SQLException {
        Map<String, Object> r = Db.one(c, sql, p);
        return r == null ? null : Db.i(r.get("id"));
    }

    private static Integer ensureFaculty(Connection c, String name) throws SQLException {
        Integer f = id(c, "SELECT id FROM faculty WHERE name = ? ORDER BY id LIMIT 1", name);
        if (f == null) f = (int) Db.insert(c, "INSERT INTO faculty(name) VALUES (?)", name);
        return f;
    }

    private static void seedDemo(Connection c, Consumer<String> log) throws SQLException {
        // 1. courses
        Object[][] courses = {{"B.Tech CSE", 4}, {"BCA", 3}, {"B.Sc (Computer Science)", 3}};
        for (Object[] co : courses)
            Db.update(c, "INSERT IGNORE INTO course(name,duration_years) VALUES (?,?)", co[0], co[1]);
        Integer btech = id(c, "SELECT id FROM course WHERE name = ?", "B.Tech CSE");

        // 2. data from older versions had no course/year: attach it to B.Tech CSE
        if (btech != null) {
            for (Map<String, Object> s : Db.query(c, "SELECT id, name FROM section WHERE course_id IS NULL")) {
                String n = Db.s(s.get("name"));
                int yr = n.contains("2") && !n.contains("3") ? 2 : 3;
                Db.update(c, "UPDATE section SET course_id = ?, `year` = ? WHERE id = ?", btech, yr, s.get("id"));
            }
            Db.update(c, "UPDATE subject SET course_id = ?, `year` = 3 WHERE course_id IS NULL", btech);
        }

        // 3. rooms and faculty
        Object[][] rooms = {{"R101", "Lecture", 60}, {"R102", "Lecture", 60}, {"R103", "Lecture", 60},
                {"R104", "Lecture", 70}, {"R105", "Lecture", 70}, {"R106", "Lecture", 80},
                {"Lab-1", "Lab", 30}, {"Lab-2", "Lab", 30}, {"Lab-3", "Lab", 30}};
        for (Object[] r : rooms)
            Db.update(c, "INSERT IGNORE INTO room(name,type,capacity) VALUES (?,?,?)", r[0], r[1], r[2]);
        String[] fac = {"Dr. A. Verma", "Dr. R. Kapoor", "Prof. S. Nair", "Dr. P. Sharma", "Prof. M. Gupta", "Dr. N. Joshi",
                "Prof. K. Rawat", "Dr. S. Bisht", "Prof. A. Negi", "Dr. V. Thapliyal", "Prof. R. Bhatt", "Dr. L. Pandey", "Prof. D. Rana"};
        for (String f : fac) ensureFaculty(c, f);

        // 4. sections (years 2 and 3 only, as requested)
        Object[][] sections = {
                {"CSE-2A", "B.Tech CSE", 2, 60}, {"CSE-2B", "B.Tech CSE", 2, 60},
                {"CSE-3A", "B.Tech CSE", 3, 60}, {"CSE-3B", "B.Tech CSE", 3, 60},
                {"BCA-2A", "BCA", 2, 50}, {"BCA-3A", "BCA", 3, 50},
                {"BSC-2A", "B.Sc (Computer Science)", 2, 40}, {"BSC-3A", "B.Sc (Computer Science)", 3, 40}};
        for (Object[] s : sections) {
            Integer cid = id(c, "SELECT id FROM course WHERE name = ?", s[1]);
            Db.update(c, "INSERT IGNORE INTO section(name,course_id,`year`,strength) VALUES (?,?,?,?)", s[0], cid, s[2], s[3]);
        }

        // 5. subjects:  name | faculty | lab | course | year | periods per week | extra faculty (practicals)
        String[][] subjects = {
                {"Data Structures", "Dr. P. Sharma", "0", "B.Tech CSE", "2", "3", ""},
                {"Discrete Mathematics", "Prof. M. Gupta", "0", "B.Tech CSE", "2", "3", ""},
                {"Computer Organization", "Dr. N. Joshi", "0", "B.Tech CSE", "2", "3", ""},
                {"Object Oriented Programming", "Prof. K. Rawat", "0", "B.Tech CSE", "2", "3", ""},
                {"Data Structures Lab", "Dr. P. Sharma", "1", "B.Tech CSE", "2", "1", "Prof. K. Rawat"},
                {"Database Systems", "Dr. A. Verma", "0", "B.Tech CSE", "3", "3", ""},
                {"Operating Systems", "Dr. R. Kapoor", "0", "B.Tech CSE", "3", "3", ""},
                {"Computer Networks", "Prof. S. Nair", "0", "B.Tech CSE", "3", "3", ""},
                {"Theory of Computation", "Dr. S. Bisht", "0", "B.Tech CSE", "3", "3", ""},
                {"DBMS Lab", "Dr. A. Verma", "1", "B.Tech CSE", "3", "1", "Dr. S. Bisht"},
                {"Programming in Java", "Prof. A. Negi", "0", "BCA", "2", "3", ""},
                {"Web Technologies", "Dr. V. Thapliyal", "0", "BCA", "2", "3", ""},
                {"Statistics", "Prof. R. Bhatt", "0", "BCA", "2", "3", ""},
                {"Java Lab", "Prof. A. Negi", "1", "BCA", "2", "1", "Dr. V. Thapliyal"},
                {"Software Engineering", "Dr. L. Pandey", "0", "BCA", "3", "3", ""},
                {"Cloud Computing", "Prof. D. Rana", "0", "BCA", "3", "3", ""},
                {"Mobile App Development", "Dr. V. Thapliyal", "0", "BCA", "3", "3", ""},
                {"Mobile App Lab", "Dr. V. Thapliyal", "1", "BCA", "3", "1", "Prof. D. Rana"},
                {"Python Programming", "Dr. P. Sharma", "0", "B.Sc (Computer Science)", "2", "3", ""},
                {"Linear Algebra", "Prof. M. Gupta", "0", "B.Sc (Computer Science)", "2", "3", ""},
                {"Digital Electronics", "Dr. N. Joshi", "0", "B.Sc (Computer Science)", "2", "3", ""},
                {"Python Lab", "Dr. P. Sharma", "1", "B.Sc (Computer Science)", "2", "1", "Prof. K. Rawat"},
                {"Machine Learning", "Dr. R. Kapoor", "0", "B.Sc (Computer Science)", "3", "3", ""},
                {"Data Mining", "Dr. S. Bisht", "0", "B.Sc (Computer Science)", "3", "3", ""},
                {"Computer Graphics", "Prof. K. Rawat", "0", "B.Sc (Computer Science)", "3", "3", ""},
                {"ML Lab", "Dr. R. Kapoor", "1", "B.Sc (Computer Science)", "3", "1", "Prof. S. Nair"}};
        for (String[] s : subjects) {
            Integer cid = id(c, "SELECT id FROM course WHERE name = ?", s[3]);
            Integer sid = id(c, "SELECT id FROM subject WHERE name = ? AND course_id = ? AND `year` = ?", s[0], cid, Integer.parseInt(s[4]));
            if (sid == null) {
                int fid = ensureFaculty(c, s[1]);
                sid = (int) Db.insert(c, "INSERT INTO subject(name,faculty_id,is_lab,course_id,`year`,periods_per_week) VALUES (?,?,?,?,?,?)",
                        s[0], fid, s[2].equals("1"), cid, Integer.parseInt(s[4]), Integer.parseInt(s[5]));
            }
            if (!s[6].isEmpty()) {
                for (String ex : s[6].split(",")) {
                    Db.update(c, "INSERT IGNORE INTO subject_faculty(subject_id,faculty_id) VALUES (?,?)", sid, ensureFaculty(c, ex.trim()));
                }
            }
        }

        // 6. extra demo logins
        Integer cse3a = id(c, "SELECT id FROM section WHERE name = 'CSE-3A'");
        Integer cse2a = id(c, "SELECT id FROM section WHERE name = 'CSE-2A'");
        Integer bca3a = id(c, "SELECT id FROM section WHERE name = 'BCA-3A'");
        Db.update(c, "INSERT IGNORE INTO users(id,name,password,role,section_id) VALUES ('stu1','Student (CSE-3A)','student123','student',?)", cse3a);
        Db.update(c, "INSERT IGNORE INTO users(id,name,password,role,section_id) VALUES ('stu2','Student (CSE-2A)','student123','student',?)", cse2a);
        Db.update(c, "INSERT IGNORE INTO users(id,name,password,role,section_id) VALUES ('stu3','Student (BCA-3A)','student123','student',?)", bca3a);
        Db.update(c, "INSERT IGNORE INTO users(id,name,password,role) VALUES ('fac3','Dr. P. Sharma','faculty123','faculty')");

        Db.update(c, "INSERT INTO notice(title,body,audience,kind,created_by) VALUES (?,?,?,?,?)",
                "Welcome to the Timetable & Classroom Allocation System",
                "Timetables for Year 2 and Year 3 of B.Tech CSE, BCA and B.Sc (CS) are managed here. Check this board for timetable changes, substitutions and exam schedules.",
                "all", "announcement", "system");
        log.accept("[Schema] Demo data seeded (courses, sections for years 2-3, subjects, rooms, faculty).");
    }
}
