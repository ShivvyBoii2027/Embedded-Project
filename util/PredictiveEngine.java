package com.smartgrid.util;

import com.smartgrid.dao.TrafficDAO;
import com.smartgrid.model.SensorReading;

import java.util.*;

/**
 * Predicts which nodes will hit critical density in the next N minutes.
 * Uses simple linear regression on the last 10 readings per node.
 * Runs on demand — called from the refresh loop.
 */
public class PredictiveEngine {

    private static PredictiveEngine instance;
    private final TrafficDAO dao = new TrafficDAO();

    // Result: nodeId -> predicted vehicles/min in ~5 minutes
    private final Map<String, Integer> predictions    = new HashMap<>();
    private final Map<String, String>  predictionText = new HashMap<>();

    private static final int CRITICAL_THRESHOLD = 110;
    private static final int READINGS_NEEDED    = 8;
    private static final int FORECAST_STEPS     = 5; // ~5 poll cycles = ~15 seconds forecast

    private PredictiveEngine() {}

    public static synchronized PredictiveEngine getInstance() {
        if (instance == null) instance = new PredictiveEngine();
        return instance;
    }

    /** Runs prediction for all given nodes */
    public void update(Set<String> nodeIds) {
        predictions.clear();
        predictionText.clear();

        for (String nodeId : nodeIds) {
            List<SensorReading> readings = dao.getReadingsForNode(nodeId, READINGS_NEEDED);
            if (readings.size() < 3) {
                predictionText.put(nodeId, "Insufficient data");
                continue;
            }

            // Readings come newest-first — reverse for regression
            Collections.reverse(readings);
            int n = readings.size();
            double[] x = new double[n];
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = i;
                y[i] = readings.get(i).getVehiclesPerMin();
            }

            // Linear regression: y = a + b*x
            double sumX=0, sumY=0, sumXY=0, sumX2=0;
            for (int i=0; i<n; i++) { sumX+=x[i]; sumY+=y[i]; sumXY+=x[i]*y[i]; sumX2+=x[i]*x[i]; }
            double denom = n * sumX2 - sumX * sumX;
            double slope  = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
            double intercept = (sumY - slope * sumX) / n;

            // Predict value at FORECAST_STEPS steps ahead
            int predicted = (int) Math.max(0, Math.min(200, intercept + slope * (n - 1 + FORECAST_STEPS)));
            predictions.put(nodeId, predicted);

            // Generate text
            int current = readings.get(readings.size()-1).getVehiclesPerMin();
            String trend = slope > 2 ? "rising fast" : slope > 0.5 ? "rising" : slope < -2 ? "falling fast" : slope < -0.5 ? "falling" : "stable";

            if (predicted >= CRITICAL_THRESHOLD) {
                predictionText.put(nodeId, "⚠ CRITICAL predicted (" + predicted + "/min) — " + trend);
            } else if (predicted >= 80) {
                predictionText.put(nodeId, "HIGH density ahead (" + predicted + "/min) — " + trend);
            } else {
                predictionText.put(nodeId, "Normal (" + predicted + "/min) — " + trend);
            }
        }
    }

    public int getPrediction(String nodeId) {
        return predictions.getOrDefault(nodeId, -1);
    }

    public String getPredictionText(String nodeId) {
        return predictionText.getOrDefault(nodeId, "—");
    }

    public boolean isPredictedCritical(String nodeId) {
        return predictions.getOrDefault(nodeId, 0) >= CRITICAL_THRESHOLD;
    }

    public Map<String, String> getAllPredictions() {
        return Collections.unmodifiableMap(predictionText);
    }
}
