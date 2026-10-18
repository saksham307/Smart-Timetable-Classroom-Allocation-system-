package com.timetable.dao;

import com.timetable.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SubjectDAO {

    public List<Map<String, Object>> findAll() throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, name, faculty_id, is_lab FROM subject ORDER BY id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                m.put("facultyId", rs.getInt("faculty_id"));
                m.put("isLab", rs.getBoolean("is_lab"));
                list.add(m);
            }
        }
        return list;
    }

    public int insert(String name, int facultyId, boolean isLab) throws SQLException {
        String sql = "INSERT INTO subject(name, faculty_id, is_lab) VALUES (?,?,?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setInt(2, facultyId);
            ps.setBoolean(3, isLab);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        }
        return -1;
    }

    public void delete(int id) throws SQLException {
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM subject WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
