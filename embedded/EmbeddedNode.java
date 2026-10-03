package com.smartgrid.embedded;

import com.smartgrid.dao.DatabaseManager;
import com.smartgrid.dao.TrafficDAO;
import com.smartgrid.model.IncidentLog;
import com.smartgrid.model.IncidentLog.IncidentType;
import com.smartgrid.model.IncidentLog.Severity;
import com.smartgrid.model.SensorReading;
import com.smartgrid.model.SensorReading.DensityLevel;
import com.smartgrid.model.SensorReading.LightState;

import java.util.Random;

/**
 * Simulates one embedded microcontroller / intersection sensor node.
 * Runs on its own daemon thread. Fully restartable after stop().
 */
public class EmbeddedNode implements Runnable {

    // ── Traffic light FSM states ────────────────────────────────────────────
    private enum TLState { GREEN, AMBER, RED }

    // ── Identity ────────────────────────────────────────────────────────────
    private final String nodeId;
    private final String nodeName;
    private final int    baseTraffic;

    // ── Thread control — volatile so changes are visible across threads ──────
    private volatile boolean running    = false;
    private volatile Thread  thread     = null;
    private volatile int     generation = 0; // incremented on every start(); old threads detect this and exit cleanly

    // ── FSM state — reset every time node starts ────────────────────────────
    private TLState  lightState;
    private long     stateEnteredAt;
    private int      consecutiveHigh;
    private boolean  incidentRaised;

    // ── Configurable thresholds ─────────────────────────────────────────────
    private volatile int highThreshold     = 80;
    private volatile int criticalThreshold = 110;

    private static final int POLL_MS = 3000;
    private final Random rng = new Random();

    public EmbeddedNode(String nodeId, String nodeName, int baseTraffic) {
        this.nodeId      = nodeId;
        this.nodeName    = nodeName;
        this.baseTraffic = baseTraffic;
    }

    // ── Public control ──────────────────────────────────────────────────────

    public synchronized void start() {
        // Guard: don't start if already running
        if (running && thread != null && thread.isAlive()) {
            System.out.println("[NODE " + nodeId + "] Already running.");
            return;
        }
        // Reset all FSM state fresh every start
        lightState      = TLState.GREEN;
        stateEnteredAt  = System.currentTimeMillis();
        consecutiveHigh = 0;
        incidentRaised  = false;
        generation++;   // old thread will see this changed and stop without touching running
        running    = true;

        thread = new Thread(this, "EmbeddedNode-" + nodeId);
        thread.setDaemon(true);
        thread.start();
        System.out.println("[NODE " + nodeId + "] Started.");
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
        System.out.println("[NODE " + nodeId + "] Stopped.");
    }

    // ── Main embedded loop ──────────────────────────────────────────────────

    @Override
    public void run() {
        final int myGeneration = generation; // capture at thread start
        TrafficDAO dao = new TrafficDAO();
        System.out.println("[NODE " + nodeId + "] Loop started (gen=" + myGeneration + ").");

        while (running && generation == myGeneration) {
            // Check DB is connected before each iteration
            if (!DatabaseManager.getInstance().isConnected()) {
                System.out.println("[NODE " + nodeId + "] DB disconnected — pausing.");
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                continue;
            }

            try {
                int vehicles = readVehicleSensor();
                double avgWait = computeAvgWait(vehicles);
                updateFSM(vehicles);
                DensityLevel density = classifyDensity(vehicles);

                SensorReading reading = new SensorReading(
                    nodeId, vehicles, density, getCurrentLight(), avgWait);
                dao.insertReading(reading);
                checkThreshold(dao, vehicles);

                System.out.printf("[NODE %s] v=%d light=%-5s density=%s%n",
                    nodeId, vehicles, lightState, density);

                Thread.sleep(POLL_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[NODE " + nodeId + "] Error: " + e.getMessage());
                // Brief pause then retry — don't exit the loop on errors
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }

        // Only clear running flag if we are still the active generation
        // (if generation changed, a new thread already set running=true — don't clobber it)
        if (generation == myGeneration) {
            running = false;
        }
        System.out.println("[NODE " + nodeId + "] Loop ended (gen=" + myGeneration + ").");
    }

    // ── Sensor simulation ──────────────────────────────────────────────────

    private int readVehicleSensor() {
        int hour = java.time.LocalTime.now().getHour();
        double rush = 1.0;
        if (hour >= 7  && hour <= 9)  rush = 1.8;
        if (hour >= 17 && hour <= 19) rush = 2.0;
        if (hour >= 0  && hour <= 5)  rush = 0.3;
        int v = (int)(baseTraffic * rush + rng.nextGaussian() * 14);
        return Math.max(0, Math.min(150, v));
    }

    private void updateFSM(int vehicles) {
        long elapsed = System.currentTimeMillis() - stateEnteredAt;
        switch (lightState) {
            case GREEN -> {
                long dur = vehicles > highThreshold ? 7000 : 5000;
                if (elapsed >= dur) transition(TLState.AMBER);
            }
            case AMBER -> { if (elapsed >= 2000) transition(TLState.RED); }
            case RED -> { if (elapsed >= 5000) transition(TLState.GREEN); }
        }
    }

    private void transition(TLState next) {
        lightState     = next;
        stateEnteredAt = System.currentTimeMillis();
    }

    private void checkThreshold(TrafficDAO dao, int vehicles) {
        if (vehicles >= highThreshold) {
            consecutiveHigh++;
        } else {
            if (incidentRaised && consecutiveHigh >= 3) {
                dao.insertIncident(new IncidentLog(nodeId, IncidentType.CLEARED,
                    "Traffic normalised at " + nodeName, Severity.INFO));
                incidentRaised = false;
            }
            consecutiveHigh = 0;
        }
        if (consecutiveHigh == 3 && !incidentRaised) {
            boolean crit = vehicles >= criticalThreshold;
            dao.insertIncident(new IncidentLog(nodeId,
                crit ? IncidentType.CONGESTION : IncidentType.HIGH_DENSITY,
                (crit ? "CONGESTION" : "HIGH_DENSITY") + " at " + nodeName + " — " + vehicles + " vehicles/min",
                crit ? Severity.CRITICAL : Severity.WARNING));
            incidentRaised = true;
        }
    }

    private DensityLevel classifyDensity(int v) {
        if (v < 40)  return DensityLevel.LOW;
        if (v < 70)  return DensityLevel.MEDIUM;
        if (v < 110) return DensityLevel.HIGH;
        return DensityLevel.CRITICAL;
    }

    private double computeAvgWait(int v) {
        return Math.round((v * 0.08 + rng.nextDouble() * 2) * 10.0) / 10.0;
    }

    private LightState toLightState(TLState s) {
        return switch (s) {
            case GREEN -> LightState.GREEN;
            case AMBER -> LightState.AMBER;
            case RED   -> LightState.RED;
        };
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    // ── Emergency override ──────────────────────────────────────────────────
    private volatile boolean emergencyOverride = false;
    private volatile boolean faultMode         = false;
    private volatile boolean faultAcknowledged = false;

    public void setEmergencyOverride(boolean on) { emergencyOverride = on; }
    public boolean isEmergencyOverride()          { return emergencyOverride; }
    public void setFaultMode(boolean on)          { faultMode = on; }
    public boolean isFaultMode()                  { return faultMode; }
    public void acknowledgeFault()                { faultAcknowledged = true; faultMode = false; }
    public boolean isFaultAcknowledged()          { return faultAcknowledged; }

    public String    getNodeId()           { return nodeId; }
    public String    getNodeName()         { return nodeName; }
    public boolean   isRunning()           { return running && thread != null && thread.isAlive(); }
    public LightState getCurrentLight()    {
        if (emergencyOverride) return LightState.GREEN;
        return toLightState(lightState != null ? lightState : TLState.GREEN);
    }
    public int       getHighThreshold()    { return highThreshold; }
    public void      setHighThreshold(int v)     { highThreshold = v; }
    public int       getCriticalThreshold()      { return criticalThreshold; }
    public void      setCriticalThreshold(int v) { criticalThreshold = v; }
}
