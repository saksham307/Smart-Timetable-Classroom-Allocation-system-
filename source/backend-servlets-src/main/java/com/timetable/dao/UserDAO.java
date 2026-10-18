package com.timetable.dao;

import com.timetable.util.DBUtil;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class UserDAO {

    public Map<String, Object> findById(String id) throws SQLException {
        String sql = "SELECT id, name, role, section_id FROM users WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("role", rs.getString("role"));
                    int sectionId = rs.getInt("section_id");
                    m.put("sectionId", rs.wasNull() ? null : sectionId);
                    return m;
                }
            }
        }
        return null;
    }
}
