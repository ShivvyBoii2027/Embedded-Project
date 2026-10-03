package com.smartgrid.dao;

import com.smartgrid.model.IncidentLog;
import com.smartgrid.model.IncidentLog.IncidentType;
import com.smartgrid.model.IncidentLog.Severity;
import com.smartgrid.model.SensorReading;
import com.smartgrid.model.SensorReading.DensityLevel;
import com.smartgrid.model.SensorReading.LightState;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for sensor_readings and incident_log tables.
 * Also provides the navigation methods used by the nav buttons.
 */
public class TrafficDAO {

    private final DatabaseManager db = DatabaseManager.getInstance();

    // ─────────────────────────────────────────────────────────
    //  SENSOR READINGS
    // ─────────────────────────────────────────────────────────

    /** Insert a new sensor reading from an embedded node thread */
    public void insertReading(SensorReading r) {
        String sql = "INSERT INTO sensor_readings " +
                     "(node_id, vehicles_per_min, density_level, light_state, avg_wait_sec) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, r.getNodeId());
            ps.setInt   (2, r.getVehiclesPerMin());
            ps.setString(3, r.getDensityLevel().name());
            ps.setString(4, r.getLightState().name());
            ps.setDouble(5, r.getAvgWaitSec());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DAO] insertReading error: " + e.getMessage());
        }
    }

    /** Get the latest reading for each node (for the sidebar live stats) */
    public List<SensorReading> getLatestReadingsPerNode() {
        String sql = "SELECT r.* FROM sensor_readings r " +
                     "INNER JOIN (SELECT node_id, MAX(reading_id) max_id " +
                     "            FROM sensor_readings GROUP BY node_id) latest " +
                     "ON r.node_id = latest.node_id AND r.reading_id = latest.max_id " +
                     "ORDER BY r.node_id";
        List<SensorReading> list = new ArrayList<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(mapReading(rs));
        } catch (SQLException e) {
            System.err.println("[DAO] getLatestReadings error: " + e.getMessage());
        }
        return list;
    }

    /** Last 8 readings across all nodes — for the mini bar chart */
    public List<SensorReading> getRecentReadings(int limit) {
        String sql = "SELECT * FROM sensor_readings ORDER BY reading_id DESC LIMIT ?";
        List<SensorReading> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapReading(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DAO] getRecentReadings error: " + e.getMessage());
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────
    //  INCIDENT LOG + NAVIGATION
    // ─────────────────────────────────────────────────────────

    public void insertIncident(IncidentLog inc) {
        String sql = "INSERT INTO incident_log (node_id, incident_type, description, severity) " +
                     "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, inc.getNodeId());
            ps.setString(2, inc.getIncidentType().name());
            ps.setString(3, inc.getDescription());
            ps.setString(4, inc.getSeverity().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DAO] insertIncident error: " + e.getMessage());
        }
    }

    public int getTotalIncidents() {
        String sql = "SELECT COUNT(*) FROM incident_log";
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("[DAO] getTotalIncidents error: " + e.getMessage());
        }
        return 0;
    }

    /** Navigate: get incident at position (1-based offset by creation order) */
    public IncidentLog getIncidentAtOffset(int offset) {
        String sql = "SELECT * FROM incident_log ORDER BY incident_id LIMIT 1 OFFSET ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, offset - 1));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapIncident(rs);
            }
        } catch (SQLException e) {
            System.err.println("[DAO] getIncidentAtOffset error: " + e.getMessage());
        }
        return null;
    }

    /** First record */
    public IncidentLog getFirstIncident() {
        return getIncidentAtOffset(1);
    }

    /** Last record */
    public IncidentLog getLastIncident() {
        int total = getTotalIncidents();
        return getIncidentAtOffset(total);
    }

    /** All incidents — for table view */
    public List<IncidentLog> getAllIncidents() {
        String sql = "SELECT * FROM incident_log ORDER BY incident_id DESC";
        List<IncidentLog> list = new ArrayList<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(mapIncident(rs));
        } catch (SQLException e) {
            System.err.println("[DAO] getAllIncidents error: " + e.getMessage());
        }
        return list;
    }

    /** Recent unresolved incidents for the right panel */
    public List<IncidentLog> getRecentUnresolvedIncidents(int limit) {
        String sql = "SELECT * FROM incident_log WHERE resolved = FALSE " +
                     "ORDER BY incident_id DESC LIMIT ?";
        List<IncidentLog> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapIncident(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DAO] getRecentUnresolved error: " + e.getMessage());
        }
        return list;
    }

    public int getTotalVehicles() {
        String sql = "SELECT COALESCE(SUM(vehicles_per_min),0) FROM (" +
                     "  SELECT r.vehicles_per_min FROM sensor_readings r " +
                     "  INNER JOIN (SELECT node_id, MAX(reading_id) mid FROM sensor_readings GROUP BY node_id) l " +
                     "  ON r.node_id=l.node_id AND r.reading_id=l.mid) t";
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("[DAO] getTotalVehicles error: " + e.getMessage());
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────
    //  Mappers
    // ─────────────────────────────────────────────────────────

    private SensorReading mapReading(ResultSet rs) throws SQLException {
        SensorReading r = new SensorReading();
        r.setReadingId(rs.getLong("reading_id"));
        r.setNodeId(rs.getString("node_id"));
        r.setVehiclesPerMin(rs.getInt("vehicles_per_min"));
        r.setDensityLevel(DensityLevel.valueOf(rs.getString("density_level")));
        r.setLightState(LightState.valueOf(rs.getString("light_state")));
        r.setAvgWaitSec(rs.getDouble("avg_wait_sec"));
        Timestamp ts = rs.getTimestamp("timestamp");
        if (ts != null) r.setTimestamp(ts.toLocalDateTime());
        return r;
    }

    private IncidentLog mapIncident(ResultSet rs) throws SQLException {
        IncidentLog inc = new IncidentLog();
        inc.setIncidentId(rs.getLong("incident_id"));
        inc.setNodeId(rs.getString("node_id"));
        inc.setIncidentType(IncidentType.valueOf(rs.getString("incident_type")));
        inc.setDescription(rs.getString("description"));
        inc.setSeverity(Severity.valueOf(rs.getString("severity")));
        inc.setResolved(rs.getBoolean("resolved"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) inc.setCreatedAt(ts.toLocalDateTime());
        return inc;
    }

    public String insertNode(String nodeId, String nodeName, String location) {
        // Check if already exists
        try (PreparedStatement check = db.getConnection().prepareStatement(
                "SELECT node_id FROM intersection_nodes WHERE node_id = ?")) {
            check.setString(1, nodeId);
            try (java.sql.ResultSet rs = check.executeQuery()) {
                if (rs.next()) return "EXISTS";
            }
        } catch (SQLException e) {
            return "ERROR: " + e.getMessage();
        }
        String sql = "INSERT INTO intersection_nodes (node_id, node_name, location, latitude, longitude) VALUES (?,?,?,0,0)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.setString(2, nodeName);
            ps.setString(3, location == null || location.isEmpty() ? nodeName : location);
            ps.executeUpdate();
            System.out.println("[DAO] Node inserted: " + nodeId);
            return "OK";
        } catch (SQLException e) {
            System.err.println("[DAO] insertNode error: " + e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    public List<String> getAllNodeIds() {
        String sql = "SELECT node_id FROM intersection_nodes ORDER BY node_id";
        List<String> ids = new ArrayList<>();
        try (Statement st = db.getConnection().createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) ids.add(rs.getString("node_id"));
        } catch (SQLException e) {
            System.err.println("[DAO] getAllNodeIds error: " + e.getMessage());
        }
        return ids;
    }

    // ── Analytics: vehicle history per node (last N readings) ────────────────
    public List<SensorReading> getReadingsForNode(String nodeId, int limit) {
        String sql = "SELECT * FROM sensor_readings WHERE node_id=? ORDER BY reading_id DESC LIMIT ?";
        List<SensorReading> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, nodeId); ps.setInt(2, limit);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapReading(rs));
            }
        } catch (SQLException e) { System.err.println("[DAO] getReadingsForNode: " + e.getMessage()); }
        return list;
    }

    // ── Node health: last seen timestamp per node ─────────────────────────────
    public java.util.Map<String,java.time.LocalDateTime> getLastSeenPerNode() {
        String sql = "SELECT node_id, MAX(timestamp) last_ts FROM sensor_readings GROUP BY node_id";
        java.util.Map<String,java.time.LocalDateTime> map = new java.util.HashMap<>();
        try (Statement st = db.getConnection().createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                java.sql.Timestamp ts = rs.getTimestamp("last_ts");
                if (ts != null) map.put(rs.getString("node_id"), ts.toLocalDateTime());
            }
        } catch (SQLException e) { System.err.println("[DAO] getLastSeenPerNode: " + e.getMessage()); }
        return map;
    }

    // ── Incident stats: count per node ────────────────────────────────────────
    public java.util.Map<String,Integer> getIncidentCountPerNode() {
        String sql = "SELECT node_id, COUNT(*) cnt FROM incident_log GROUP BY node_id";
        java.util.Map<String,Integer> map = new java.util.HashMap<>();
        try (Statement st = db.getConnection().createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) map.put(rs.getString("node_id"), rs.getInt("cnt"));
        } catch (SQLException e) { System.err.println("[DAO] getIncidentCountPerNode: " + e.getMessage()); }
        return map;
    }

    // ── Resolve incident ──────────────────────────────────────────────────────
    public void resolveIncident(long incidentId) {
        String sql = "UPDATE incident_log SET resolved=TRUE WHERE incident_id=?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, incidentId); ps.executeUpdate();
        } catch (SQLException e) { System.err.println("[DAO] resolveIncident: " + e.getMessage()); }
    }

    // ── Avg wait time last N readings ─────────────────────────────────────────
    public double getAvgWaitTime() {
        String sql = "SELECT AVG(avg_wait_sec) FROM (SELECT avg_wait_sec FROM sensor_readings ORDER BY reading_id DESC LIMIT 50) t";
        try (Statement st = db.getConnection().createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return Math.round(rs.getDouble(1) * 10.0) / 10.0;
        } catch (SQLException e) { System.err.println("[DAO] getAvgWaitTime: " + e.getMessage()); }
        return 0;
    }
}