package com.smartgrid.util;

import com.smartgrid.dao.DatabaseManager;
import com.smartgrid.dao.TrafficDAO;
import com.smartgrid.model.IncidentLog;
import com.smartgrid.model.IncidentLog.IncidentType;
import com.smartgrid.model.IncidentLog.Severity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class NodeHealthMonitor {

    public enum NodeStatus { HEALTHY, WARNING, FAULT, UNKNOWN }

    private static NodeHealthMonitor instance;
    private final TrafficDAO dao = new TrafficDAO();
    private final Map<String, NodeStatus>       statusMap    = new HashMap<>();
    private final Map<String, LocalDateTime>    lastSeenMap  = new HashMap<>();
    private final Set<String>                   faultAlerted = new HashSet<>();
    // Grace period: after clearFault(), ignore DB timestamps for this many seconds
    // so the node has time to write its first new reading before being re-faulted
    private final Map<String, Long> gracePeriodUntil = new HashMap<>();

    private static final int WARNING_SECONDS = 12;
    private static final int FAULT_SECONDS   = 25;
    private static final int GRACE_SECONDS   = 30;

    private NodeHealthMonitor() {}

    public static synchronized NodeHealthMonitor getInstance() {
        if (instance == null) instance = new NodeHealthMonitor();
        return instance;
    }

    public void update(Set<String> registeredNodes) {
        if (!DatabaseManager.getInstance().isConnected()) return;
        Map<String, LocalDateTime> lastSeen = dao.getLastSeenPerNode();
        lastSeenMap.putAll(lastSeen);
        long nowMillis = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();

        for (String nodeId : registeredNodes) {
            // Skip health check during grace period (just restarted)
            Long graceUntil = gracePeriodUntil.get(nodeId);
            if (graceUntil != null && nowMillis < graceUntil) {
                statusMap.put(nodeId, NodeStatus.HEALTHY);
                continue;
            }

            LocalDateTime ts = lastSeen.get(nodeId);
            if (ts == null) {
                statusMap.put(nodeId, NodeStatus.UNKNOWN);
                continue;
            }
            long secAgo = ChronoUnit.SECONDS.between(ts, now);

            NodeStatus next;
            if      (secAgo < WARNING_SECONDS) next = NodeStatus.HEALTHY;
            else if (secAgo < FAULT_SECONDS)   next = NodeStatus.WARNING;
            else                               next = NodeStatus.FAULT;

            statusMap.put(nodeId, next);

            if (next == NodeStatus.FAULT && !faultAlerted.contains(nodeId)) {
                dao.insertIncident(new IncidentLog(nodeId, IncidentType.LIGHT_FAULT,
                    "Node " + nodeId + " heartbeat lost — " + secAgo + "s since last reading",
                    Severity.CRITICAL));
                faultAlerted.add(nodeId);
            }
            if (next == NodeStatus.HEALTHY) faultAlerted.remove(nodeId);
        }
    }

    /** Immediately clear fault and grant a grace period so the node can restart */
    public void clearFault(String nodeId) {
        statusMap.put(nodeId, NodeStatus.HEALTHY);
        faultAlerted.remove(nodeId);
        // Give the node GRACE_SECONDS before health checks resume
        gracePeriodUntil.put(nodeId, System.currentTimeMillis() + GRACE_SECONDS * 1000L);
        System.out.println("[Health] Fault cleared for " + nodeId + " — grace period " + GRACE_SECONDS + "s");
    }

    public NodeStatus getStatus(String nodeId) {
        return statusMap.getOrDefault(nodeId, NodeStatus.UNKNOWN);
    }

    public Map<String, NodeStatus> getAllStatuses() {
        return Collections.unmodifiableMap(statusMap);
    }

    public LocalDateTime getLastSeen(String nodeId) {
        return lastSeenMap.get(nodeId);
    }

    public void reset() {
        statusMap.clear();
        faultAlerted.clear();
        gracePeriodUntil.clear();
    }
}
