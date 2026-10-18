package com.timetable.engine;

import com.timetable.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class SchedulingEngine {

    private static final Object LOCK = new Object();

    public static class ConflictResult {
        public final String type;
        public final String message;
        public ConflictResult(String type, String message) { this.type = type; this.message = message; }
    }

    /** Checks whether (section, faculty, room) would clash with an existing entry in this timeslot. */
    public ConflictResult findConflict(Connection conn, int sectionId, int facultyId, int roomId,
                                        int timeSlotId, Integer ignoreEntryId) throws SQLException {
        String sql = "SELECT id, room_id, faculty_id, section_id FROM timetable_entry WHERE time_slot_id = ?" +
                (ignoreEntryId != null ? " AND id != ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, timeSlotId);
            if (ignoreEntryId != null) ps.setInt(2, ignoreEntryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt("room_id") == roomId)
                        return new ConflictResult("room", "Room already booked in this slot (entry #" + rs.getInt("id") + ").");
                    if (rs.getInt("faculty_id") == facultyId)
                        return new ConflictResult("faculty", "Faculty already teaching another class in this slot (entry #" + rs.getInt("id") + ").");
                    if (rs.getInt("section_id") == sectionId)
                        return new ConflictResult("section", "Section already has a class in this slot (entry #" + rs.getInt("id") + ").");
                }
            }
        }
        return null;
    }

    private Map<String, Object> suggestAlternative(Connection conn, int sectionId, int facultyId, int roomId) throws SQLException {
        String sql = "SELECT id, day, TIME_FORMAT(start_time,'%H:%i') st, TIME_FORMAT(end_time,'%H:%i') et " +
                "FROM time_slot ORDER BY FIELD(day,'Mon','Tue','Wed','Thu','Fri'), start_time";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int slotId = rs.getInt("id");
                if (findConflict(conn, sectionId, facultyId, roomId, slotId, null) == null) {
                    Map<String, Object> alt = new LinkedHashMap<>();
                    alt.put("day", rs.getString("day"));
                    alt.put("time", rs.getString("st") + "-" + rs.getString("et"));
                    return alt;
                }
            }
        }
        return null;
    }

    /** Manual single-entry booking: check + insert as one atomic, mutually-exclusive unit. */
    public int bookEntry(int sectionId, int subjectId, int facultyId, int roomId, int timeSlotId)
            throws SQLException, ConflictException {
        synchronized (LOCK) {
            try (Connection conn = DBUtil.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    ConflictResult conflict = findConflict(conn, sectionId, facultyId, roomId, timeSlotId, null);
                    if (conflict != null) {
                        Map<String, Object> alt = suggestAlternative(conn, sectionId, facultyId, roomId);
                        conn.rollback();
                        ConflictException ex = new ConflictException(conflict.type, conflict.message);
                        ex.suggestion = alt != null ? (alt.get("day") + " " + alt.get("time")) : "No free slot found.";
                        throw ex;
                    }
                    String sql = "INSERT INTO timetable_entry(section_id, subject_id, faculty_id, room_id, time_slot_id) VALUES (?,?,?,?,?)";
                    int newId;
                    try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, sectionId);
                        ps.setInt(2, subjectId);
                        ps.setInt(3, facultyId);
                        ps.setInt(4, roomId);
                        ps.setInt(5, timeSlotId);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            newId = keys.getInt(1);
                        }
                    }
                    conn.commit();
                    return newId;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }
        }
    }

    /** Clears and regenerates the whole timetable for one section. Labs placed first. */
    public Map<String, Object> generateForSection(int sectionId) throws SQLException {
        synchronized (LOCK) {
            try (Connection conn = DBUtil.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM timetable_entry WHERE section_id=?")) {
                        del.setInt(1, sectionId);
                        del.executeUpdate();
                    }

                    List<Map<String, Object>> subjects = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id, name, faculty_id, is_lab FROM subject ORDER BY is_lab DESC");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("id", rs.getInt("id"));
                            s.put("name", rs.getString("name"));
                            s.put("facultyId", rs.getInt("faculty_id"));
                            s.put("isLab", rs.getBoolean("is_lab"));
                            subjects.add(s);
                        }
                    }

                    Integer lectureRoomId = firstRoomId(conn, "Lecture");
                    Integer labRoomId = firstRoomId(conn, "Lab");

                    List<Integer> slotIds = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id FROM time_slot ORDER BY FIELD(day,'Mon','Tue','Wed','Thu','Fri'), start_time");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) slotIds.add(rs.getInt("id"));
                    }

                    int placed = 0;
                    List<String> failed = new ArrayList<>();

                    for (Map<String, Object> subject : subjects) {
                        boolean isLab = (Boolean) subject.get("isLab");
                        Integer roomId = isLab ? labRoomId : lectureRoomId;
                        int facultyId = (Integer) subject.get("facultyId");
                        boolean done = false;
                        if (roomId != null) {
                            for (int slotId : slotIds) {
                                if (findConflict(conn, sectionId, facultyId, roomId, slotId, null) == null) {
                                    try (PreparedStatement ins = conn.prepareStatement(
                                            "INSERT INTO timetable_entry(section_id, subject_id, faculty_id, room_id, time_slot_id) VALUES (?,?,?,?,?)")) {
                                        ins.setInt(1, sectionId);
                                        ins.setInt(2, (Integer) subject.get("id"));
                                        ins.setInt(3, facultyId);
                                        ins.setInt(4, roomId);
                                        ins.setInt(5, slotId);
                                        ins.executeUpdate();
                                    }
                                    placed++;
                                    done = true;
                                    break;
                                }
                            }
                        }
                        if (!done) failed.add((String) subject.get("name"));
                    }

                    conn.commit();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("placed", placed);
                    result.put("failed", failed);
                    return result;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }
        }
    }

    private Integer firstRoomId(Connection conn, String type) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM room WHERE type=? LIMIT 1")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        }
        return null;
    }
}
