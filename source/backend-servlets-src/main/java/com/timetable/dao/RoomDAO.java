package com.timetable.dao;

import com.timetable.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RoomDAO {

    public List<Map<String, Object>> findAll() throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, name, type, capacity FROM room ORDER BY id";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                m.put("type", rs.getString("type"));
                m.put("capacity", rs.getInt("capacity"));
                list.add(m);
            }
        }
        return list;
    }

    public int insert(String name, String type, int capacity) throws SQLException {
        String sql = "INSERT INTO room(name, type, capacity) VALUES (?,?,?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, type);
            ps.setInt(3, capacity);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        }
        return -1;
    }

    public void delete(int id) throws SQLException {
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM room WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
