# SmartGrid City — Embedded Traffic Command Center
### Master Project | JavaFX + MySQL + Embedded Simulation

---

## What This Project Does

SmartGrid City is a **real-time smart city traffic management system** where:

1. **5 embedded sensor nodes** run as background Java threads — each simulating an Arduino/ESP32 microcontroller at a city intersection
2. Every node implements a **traffic light Finite State Machine (FSM)** and monitors vehicle density
3. Nodes push live data to MySQL every 3 seconds — just like real embedded hardware over MQTT/Serial
4. The **JavaFX dashboard** displays a live city map, sensor readings, and incident alerts
5. The **incident log** (browsed with First / −3 / Prev / Next / +3 / Last buttons) auto-populates as nodes detect congestion

---

## Project Structure

```
SmartGridCity/
├── pom.xml                          Maven build file
├── database_setup.sql               Run this first in MySQL
└── src/main/java/com/smartgrid/
    ├── MainApp.java                 Entry point
    ├── controller/
    │   └── MainController.java      All UI logic + nav button handlers
    ├── dao/
    │   ├── DatabaseManager.java     Connect/Disconnect singleton
    │   └── TrafficDAO.java          All SQL queries + navigation
    ├── embedded/
    │   ├── EmbeddedNode.java        ← THE EMBEDDED PROGRAMMING PART
    │   └── EmbeddedNodeManager.java Manages all 5 node threads
    └── model/
        ├── IntersectionNode.java
        ├── SensorReading.java
        └── IncidentLog.java
```

---

## Setup Instructions

### Step 1 — MySQL Database
```sql
-- Open MySQL Workbench or run:
mysql -u root -p < database_setup.sql
```

### Step 2 — Update DB Password
Open `src/main/java/com/smartgrid/dao/DatabaseManager.java`
Change line:
```java
private static final String PASSWORD = "your_password_here";
```
to your actual MySQL root password.

### Step 3 — Build & Run
```bash
# In the SmartGridCity folder:
mvn clean javafx:run
```

---

## The Embedded Programming Explanation

The `EmbeddedNode.java` class is the embedded component. It demonstrates:

| Embedded Concept | Implementation |
|---|---|
| **Microcontroller main loop** | `while(running) { readSensor(); processLogic(); transmit(); sleep(3s); }` |
| **Finite State Machine (FSM)** | Traffic light cycles: GREEN → AMBER → RED → GREEN with time-based transitions |
| **Sensor reading (ADC simulation)** | `readVehicleSensor()` simulates a vehicle-counting loop detector sensor |
| **Interrupt-style threshold detection** | `checkAndRaiseIncident()` fires when density exceeds threshold for N readings (debounce) |
| **Distributed nodes** | Each of the 5 nodes runs independently on its own thread — like real hardware |
| **Data transmission** | `dao.insertReading()` pushes to MySQL like a real node would push over MQTT/Serial |
| **Rush hour patterns** | Time-of-day traffic simulation (morning/evening rush, night quiet) |
| **Graceful shutdown** | `volatile boolean running` flag stops threads safely |

---

## Navigation Buttons Reference

| Button | Action |
|---|---|
| ⏮ First | Jump to record 1 (oldest incident) |
| −3 | Go back 3 records |
| ‹ Prev | Go back 1 record |
| Next › | Go forward 1 record |
| +3 | Go forward 3 records |
| Last ⏭ | Jump to latest incident |

---

## Database Tables

| Table | Purpose |
|---|---|
| `intersection_nodes` | The 5 sensor node definitions |
| `sensor_readings` | Live data pushed every 3 seconds by each node |
| `incident_log` | Auto-generated congestion/alert events (what nav buttons browse) |
| `city_stats_snapshot` | Hourly aggregates for charting |

---

*Built with Java 17 · JavaFX 21 · MySQL 8 · Maven*
