package com.timetable.dao;

import com.timetable.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TimeSlotDAO {

    public List<Map<String, Object>> findAll() throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, day, TIME_FORMAT(start_time,'%H:%i') st, TIME_FORMAT(end_time,'%H:%i') et " +
                "FROM time_slot ORDER BY FIELD(day,'Mon','Tue','Wed','Thu','Fri'), start_time";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("day", rs.getString("day"));
                m.put("time", rs.getString("st") + "-" + rs.getString("et"));
                list.add(m);
            }
        }
        return list;
    }
}
