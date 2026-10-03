package com.smartgrid.model;

import java.time.LocalDateTime;

/**
 * One row of live sensor data pushed by an embedded node thread.
 */
public class SensorReading {

    public enum DensityLevel { LOW, MEDIUM, HIGH, CRITICAL }
    public enum LightState   { RED, AMBER, GREEN }

    private long          readingId;
    private String        nodeId;
    private int           vehiclesPerMin;
    private DensityLevel  densityLevel;
    private LightState    lightState;
    private double        avgWaitSec;
    private LocalDateTime timestamp;

    public SensorReading() {}

    public SensorReading(String nodeId, int vehiclesPerMin,
                          DensityLevel density, LightState light, double avgWait) {
        this.nodeId         = nodeId;
        this.vehiclesPerMin = vehiclesPerMin;
        this.densityLevel   = density;
        this.lightState     = light;
        this.avgWaitSec     = avgWait;
        this.timestamp      = LocalDateTime.now();
    }

    public long   getReadingId() { return readingId; }
    public void   setReadingId(long v)        { readingId = v; }

    public String getNodeId()                 { return nodeId; }
    public void   setNodeId(String v)         { nodeId = v; }

    public int    getVehiclesPerMin()         { return vehiclesPerMin; }
    public void   setVehiclesPerMin(int v)    { vehiclesPerMin = v; }

    public DensityLevel getDensityLevel()              { return densityLevel; }
    public void         setDensityLevel(DensityLevel v){ densityLevel = v; }

    public LightState getLightState()                  { return lightState; }
    public void       setLightState(LightState v)      { lightState = v; }

    public double getAvgWaitSec()             { return avgWaitSec; }
    public void   setAvgWaitSec(double v)     { avgWaitSec = v; }

    public LocalDateTime getTimestamp()              { return timestamp; }
    public void          setTimestamp(LocalDateTime v){ timestamp = v; }
}
