package com.smartgrid.model;

import java.time.LocalDateTime;

/**
 * An incident record stored in incident_log table.
 * The navigation buttons (First/-3/Prev/Next/+3/Last) browse these.
 */
public class IncidentLog {

    public enum IncidentType { CONGESTION, HIGH_DENSITY, LIGHT_FAULT, CLEARED }
    public enum Severity     { INFO, WARNING, CRITICAL }

    private long          incidentId;
    private String        nodeId;
    private IncidentType  incidentType;
    private String        description;
    private Severity      severity;
    private boolean       resolved;
    private LocalDateTime createdAt;

    public IncidentLog() {}

    public IncidentLog(String nodeId, IncidentType type,
                        String description, Severity severity) {
        this.nodeId       = nodeId;
        this.incidentType = type;
        this.description  = description;
        this.severity     = severity;
        this.resolved     = false;
        this.createdAt    = LocalDateTime.now();
    }

    public long getIncidentId()                   { return incidentId; }
    public void setIncidentId(long v)             { incidentId = v; }

    public String getNodeId()                     { return nodeId; }
    public void   setNodeId(String v)             { nodeId = v; }

    public IncidentType getIncidentType()                    { return incidentType; }
    public void         setIncidentType(IncidentType v)      { incidentType = v; }

    public String getDescription()                { return description; }
    public void   setDescription(String v)        { description = v; }

    public Severity getSeverity()                 { return severity; }
    public void     setSeverity(Severity v)       { severity = v; }

    public boolean isResolved()                   { return resolved; }
    public void    setResolved(boolean v)         { resolved = v; }

    public LocalDateTime getCreatedAt()                   { return createdAt; }
    public void          setCreatedAt(LocalDateTime v)    { createdAt = v; }
}
