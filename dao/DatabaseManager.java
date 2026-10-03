package com.smartgrid.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Singleton database connection manager.
 * The "Connect / Disconnect" button in the UI calls connect() and disconnect().
 */
public class DatabaseManager {

    // ── Change these to match your MySQL setup ──────────────────────────────
    private static final String HOST     = "localhost";
    private static final String PORT     = "3306";
    private static final String DATABASE = "smartgrid_db";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "Shivank123"; // <-- change this
    // ────────────────────────────────────────────────────────────────────────

    private static final String URL = "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {}

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }

    /**
     * Opens the database connection.
     * @return true if connected successfully
     */
    public boolean connect() {
        try {
            if (connection != null && !connection.isClosed()) return true;
            connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
            System.out.println("[DB] Connected to smartgrid_db");
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Connection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Closes the database connection.
     */
    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                connection = null;
                System.out.println("[DB] Disconnected.");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Disconnect error: " + e.getMessage());
        }
    }

    /**
     * Returns the live connection. Callers must NOT close it.
     */
    public Connection getConnection() {
        return connection;
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }
}
