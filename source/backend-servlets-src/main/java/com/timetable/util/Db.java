package com.timetable.util;

import java.sql.*;
import java.util.*;

/** Thin helper around JDBC PreparedStatement. Column aliases in the SQL become JSON keys. */
public class Db {

    public static List<Map<String, Object>> query(String sql, Object... p) throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            return query(c, sql, p);
        }
    }

    public static List<Map<String, Object>> query(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        }
    }

    public static Map<String, Object> one(Connection c, String sql, Object... p) throws SQLException {
        List<Map<String, Object>> rows = query(c, sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static Map<String, Object> one(String sql, Object... p) throws SQLException {
        List<Map<String, Object>> rows = query(sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** INSERT and return the generated key. */
    public static long insert(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }

    public static long insert(String sql, Object... p) throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            return insert(c, sql, p);
        }
    }

    public static int update(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            return ps.executeUpdate();
        }
    }

    public static int update(String sql, Object... p) throws SQLException {
        try (Connection c = DBUtil.getConnection()) {
            return update(c, sql, p);
        }
    }

    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            if (p[i] == null) ps.setNull(i + 1, Types.NULL);
            else ps.setObject(i + 1, p[i]);
        }
    }

    private static List<Map<String, Object>> read(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) {
                Object v = rs.getObject(i);
                if (v instanceof java.sql.Date || v instanceof java.sql.Time || v instanceof java.sql.Timestamp) {
                    v = v.toString();
                }
                row.put(md.getColumnLabel(i), v);
            }
            out.add(row);
        }
        return out;
    }

   
    public static int i(Object o) { return o == null ? 0 : ((Number) o).intValue(); }

    public static boolean b(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return Boolean.parseBoolean(o.toString());
    }

    public static String s(Object o) { return o == null ? null : o.toString(); }

    public static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) sb.append(k == 0 ? "?" : ",?");
        return sb.toString();
    }
}
