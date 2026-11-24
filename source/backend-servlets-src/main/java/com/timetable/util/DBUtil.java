package com.timetable.util;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * JDBC connection factory.
 * Settings come from WEB-INF/classes/db.properties so the password can be
 * changed in a text editor without recompiling anything.
 */
public class DBUtil {

    private static final Properties P = new Properties();

    static {
        P.setProperty("db.host", "localhost");
        P.setProperty("db.port", "3306");
        P.setProperty("db.name", "timetable_db");
        P.setProperty("db.user", "root");
        P.setProperty("db.password", "your_mysql_password");
        try (InputStream in = DBUtil.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) P.load(in);
        } catch (Exception ignored) { }
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("JDBC driver (mariadb-java-client.jar) not found in WEB-INF/lib.", e);
        }
    }

    private static final String PARAMS = "useSSL=false&allowPublicKeyRetrieval=true";

    private static String server() {
        return "jdbc:mysql://" + P.getProperty("db.host") + ":" + P.getProperty("db.port") + "/";
    }

    public static String dbName() { return P.getProperty("db.name"); }

    /** Connection to the timetable database. */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(server() + dbName() + "?" + PARAMS,
                P.getProperty("db.user"), P.getProperty("db.password"));
    }

    /** Connection to the MySQL server without selecting a database (used to create it on first run). */
    public static Connection getServerConnection() throws SQLException {
        return DriverManager.getConnection(server() + "?" + PARAMS,
                P.getProperty("db.user"), P.getProperty("db.password"));
    }
}
