-- ============================================================
--  SmartGrid City — Database Setup Script
--  Run this in MySQL Workbench or mysql CLI:
--  mysql -u root -p < database_setup.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS smartgrid_db;
USE smartgrid_db;

-- -------------------------------------------------------
-- Table: intersection_nodes
-- Represents each embedded microcontroller / sensor node
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS intersection_nodes (
    node_id       VARCHAR(10)  NOT NULL PRIMARY KEY,
    node_name     VARCHAR(100) NOT NULL,
    location      VARCHAR(100) NOT NULL,
    latitude      DOUBLE       NOT NULL,
    longitude     DOUBLE       NOT NULL,
    is_active     BOOLEAN      DEFAULT TRUE,
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- -------------------------------------------------------
-- Table: sensor_readings
-- Live data pushed by embedded node threads every ~3 sec
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_readings (
    reading_id      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id         VARCHAR(10)  NOT NULL,
    vehicles_per_min INT         NOT NULL,
    density_level   ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL,
    light_state     ENUM('RED','AMBER','GREEN')            NOT NULL,
    avg_wait_sec    DOUBLE       NOT NULL,
    timestamp       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (node_id) REFERENCES intersection_nodes(node_id)
);

-- -------------------------------------------------------
-- Table: incident_log
-- Auto-generated when density crosses threshold
-- This is what the nav buttons (First/-3/Prev/Next/+3/Last)
-- browse through
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS incident_log (
    incident_id     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id         VARCHAR(10)  NOT NULL,
    incident_type   ENUM('CONGESTION','HIGH_DENSITY','LIGHT_FAULT','CLEARED') NOT NULL,
    description     VARCHAR(255) NOT NULL,
    severity        ENUM('INFO','WARNING','CRITICAL') NOT NULL,
    resolved        BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (node_id) REFERENCES intersection_nodes(node_id)
);

-- -------------------------------------------------------
-- Table: city_stats_snapshot
-- Hourly aggregate — used for the traffic flow chart
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS city_stats_snapshot (
    snapshot_id     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    total_vehicles  INT          NOT NULL,
    active_nodes    INT          NOT NULL,
    avg_wait_sec    DOUBLE       NOT NULL,
    congestion_count INT         NOT NULL,
    snapshot_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- -------------------------------------------------------
-- Seed data: 5 intersection nodes
-- -------------------------------------------------------
INSERT INTO intersection_nodes (node_id, node_name, location, latitude, longitude) VALUES
('NODE_A', 'Central Junction',   'Central Ave & Main St',   51.9225, 4.4792),
('NODE_B', 'North Boulevard',    'North Blvd & Park Rd',    51.9310, 4.4802),
('NODE_C', 'East Avenue',        'East Ave & Commerce St',  51.9195, 4.4921),
('NODE_D', 'West Crossing',      'West Cross & River Rd',   51.9215, 4.4601),
('NODE_E', 'South District',     'South St & Market Ave',   51.9105, 4.4790);

-- -------------------------------------------------------
-- Seed data: some initial incidents for nav browsing
-- -------------------------------------------------------
INSERT INTO incident_log (node_id, incident_type, description, severity, resolved) VALUES
('NODE_A', 'CLEARED',      'Traffic flow normalised after peak hour', 'INFO', TRUE),
('NODE_B', 'HIGH_DENSITY', 'Vehicle density exceeded 70 vehicles/min', 'WARNING', FALSE),
('NODE_C', 'CONGESTION',   'Critical congestion — 120+ vehicles/min detected', 'CRITICAL', FALSE),
('NODE_D', 'CLEARED',      'Intersection cleared, flow restored', 'INFO', TRUE),
('NODE_E', 'HIGH_DENSITY', 'Moderate congestion during school hours', 'WARNING', TRUE),
('NODE_A', 'CONGESTION',   'Rush hour spike — automated light cycle adjusted', 'CRITICAL', TRUE),
('NODE_B', 'LIGHT_FAULT',  'Sensor heartbeat missed — checking connection', 'WARNING', FALSE),
('NODE_C', 'HIGH_DENSITY', 'Second congestion event — East Ave corridor', 'WARNING', FALSE),
('NODE_A', 'CLEARED',      'Morning congestion resolved', 'INFO', TRUE),
('NODE_D', 'HIGH_DENSITY', 'Unusual spike — possible event nearby', 'WARNING', FALSE);
