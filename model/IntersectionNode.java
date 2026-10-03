package com.smartgrid.model;

import java.time.LocalDateTime;

// ═══════════════════════════════════════════════════════════
//  Model: IntersectionNode
//  Represents one embedded sensor node / intersection
// ═══════════════════════════════════════════════════════════
public class IntersectionNode {
    private String nodeId;
    private String nodeName;
    private String location;
    private double latitude;
    private double longitude;
    private boolean active;
    private LocalDateTime createdAt;

    public IntersectionNode() {}

    public IntersectionNode(String nodeId, String nodeName, String location,
                             double latitude, double longitude) {
        this.nodeId   = nodeId;
        this.nodeName = nodeName;
        this.location = location;
        this.latitude = latitude;
        this.longitude = longitude;
        this.active   = true;
    }

    public String getNodeId()       { return nodeId; }
    public void setNodeId(String v) { nodeId = v; }

    public String getNodeName()       { return nodeName; }
    public void setNodeName(String v) { nodeName = v; }

    public String getLocation()       { return location; }
    public void setLocation(String v) { location = v; }

    public double getLatitude()        { return latitude; }
    public void setLatitude(double v)  { latitude = v; }

    public double getLongitude()       { return longitude; }
    public void setLongitude(double v) { longitude = v; }

    public boolean isActive()          { return active; }
    public void setActive(boolean v)   { active = v; }

    public LocalDateTime getCreatedAt()         { return createdAt; }
    public void setCreatedAt(LocalDateTime v)   { createdAt = v; }

    @Override
    public String toString() {
        return nodeId + " — " + nodeName;
    }
}
