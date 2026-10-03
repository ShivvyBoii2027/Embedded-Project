package com.smartgrid.embedded;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages all embedded sensor node threads.
 * Thread-safe. Supports adding nodes at runtime.
 */
public class EmbeddedNodeManager {

    private static EmbeddedNodeManager instance;
    private final List<EmbeddedNode> nodes = new ArrayList<>();

    private EmbeddedNodeManager() {
        nodes.add(new EmbeddedNode("NODE_A", "Central Junction",  55));
        nodes.add(new EmbeddedNode("NODE_B", "North Boulevard",   70));
        nodes.add(new EmbeddedNode("NODE_C", "East Avenue",       90));
        nodes.add(new EmbeddedNode("NODE_D", "West Crossing",     35));
        nodes.add(new EmbeddedNode("NODE_E", "South District",    50));
    }

    public static synchronized EmbeddedNodeManager getInstance() {
        if (instance == null) instance = new EmbeddedNodeManager();
        return instance;
    }

    /** Start all nodes. Safe to call multiple times. */
    public synchronized void startAll() {
        nodes.forEach(EmbeddedNode::start);
        System.out.println("[NodeManager] All nodes started (" + nodes.size() + " total).");
    }

    /** Stop all nodes. */
    public synchronized void stopAll() {
        nodes.forEach(EmbeddedNode::stop);
        System.out.println("[NodeManager] All nodes stopped.");
    }

    /** Add and start a new node at runtime. */
    public synchronized void addNode(String nodeId, String nodeName, int baseTraffic) {
        // Don't add duplicates
        if (getNode(nodeId) != null) {
            System.out.println("[NodeManager] Node " + nodeId + " already exists.");
            return;
        }
        EmbeddedNode node = new EmbeddedNode(nodeId, nodeName, baseTraffic);
        nodes.add(node);
        // Only start if the system is currently running
        boolean systemRunning = nodes.stream()
            .filter(n -> !n.getNodeId().equals(nodeId))
            .anyMatch(EmbeddedNode::isRunning);
        if (systemRunning) {
            node.start();
            System.out.println("[NodeManager] Added and started node: " + nodeId);
        } else {
            System.out.println("[NodeManager] Added node (not started — system offline): " + nodeId);
        }
    }

    public synchronized EmbeddedNode getNode(String nodeId) {
        return nodes.stream()
            .filter(n -> n.getNodeId().equals(nodeId))
            .findFirst().orElse(null);
    }

    public List<EmbeddedNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public boolean anyRunning() {
        return nodes.stream().anyMatch(EmbeddedNode::isRunning);
    }
}
