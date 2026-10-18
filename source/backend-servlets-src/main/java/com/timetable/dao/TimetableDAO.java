package com.timetable.dao;

import com.timetable.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TimetableDAO {

    public List<Map<String, Object>> findAll(Integer sectionId, Integer facultyId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT te.id, te.section_id, te.subject_id, te.faculty_id, te.room_id, te.time_slot_id, " +
                "sec.name AS section_name, subj.name AS subject_name, fac.name AS faculty_name, r.name AS room_name, " +
                "ts.day AS slot_day, TIME_FORMAT(ts.start_time,'%H:%i') AS slot_start, TIME_FORMAT(ts.end_time,'%H:%i') AS slot_end " +
                "FROM timetable_entry te " +
                "JOIN section sec ON sec.id = te.section_id " +
                "JOIN subject subj ON subj.id = te.subject_id " +
                "JOIN faculty fac ON fac.id = te.faculty_id " +
                "JOIN room r ON r.id = te.room_id " +
                "JOIN time_slot ts ON ts.id = te.time_slot_id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (sectionId != null) { sql.append(" AND te.section_id = ?"); params.add(sectionId); }
        if (facultyId != null) { sql.append(" AND te.faculty_id = ?"); params.add(facultyId); }

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("sectionId", rs.getInt("section_id"));
                    m.put("subjectId", rs.getInt("subject_id"));
                    m.put("facultyId", rs.getInt("faculty_id"));
                    m.put("roomId", rs.getInt("room_id"));
                    m.put("timeslotId", rs.getInt("time_slot_id"));
                    m.put("section", rs.getString("section_name"));
                    m.put("subject", rs.getString("subject_name"));
                    m.put("faculty", rs.getString("faculty_name"));
                    m.put("room", rs.getString("room_name"));

                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("id", rs.getInt("time_slot_id"));
                    slot.put("day", rs.getString("slot_day"));
                    slot.put("time", rs.getString("slot_start") + "-" + rs.getString("slot_end"));
                    m.put("slot", slot);

                    list.add(m);
                }
            }
        }
        return list;
    }

    public void delete(int id) throws SQLException {
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM timetable_entry WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
