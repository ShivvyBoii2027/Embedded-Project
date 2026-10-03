package com.smartgrid.controller;

import com.smartgrid.dao.DatabaseManager;
import com.smartgrid.dao.TrafficDAO;
import com.smartgrid.embedded.EmbeddedNode;
import com.smartgrid.embedded.EmbeddedNodeManager;
import com.smartgrid.model.IncidentLog;
import com.smartgrid.model.IncidentLog.IncidentType;
import com.smartgrid.model.IncidentLog.Severity;
import com.smartgrid.model.SensorReading;
import com.smartgrid.model.SensorReading.DensityLevel;
import com.smartgrid.util.PredictiveEngine;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainController implements Initializable {

    // ── FXML ─────────────────────────────────────────────────────────────────
    @FXML private Label      dbStatusBadge;
    @FXML private Label      clockLabel;
    @FXML private Button     dbConnectBtn;
    @FXML private Button     emergencyBtn;
    @FXML private Button     heatmapBtn;
    @FXML private Button     analyticsBtn;
    @FXML private VBox       nodeCardsContainer;
    @FXML private StackPane  mapStack;
    @FXML private Label      alertLabel;
    @FXML private Label      zoomLabel;
    @FXML private Label      predictionLabel;
    @FXML private Label      incidentTypeLabel;
    @FXML private Label      incidentNodeLabel;
    @FXML private Label      incidentTimeLabel;
    @FXML private Label      incidentDescLabel;
    @FXML private Button     btnFirst, btnMinus3, btnPrev, btnNext, btnPlus3, btnLast;
    @FXML private Label      navCurrentLabel, navTotalLabel;
    @FXML private Label      statActiveNodes, statTotalVehicles, statAlerts, statLogRows;
    @FXML private Canvas     chartCanvas;
    @FXML private VBox       incidentsContainer;
    @FXML private TextField  searchField;
    @FXML private Button     exportBtn;
    @FXML private Button     addNodeBtn;
    @FXML private Button     triggerIncidentBtn;

    // ── Services ──────────────────────────────────────────────────────────────
    private final DatabaseManager     dbManager   = DatabaseManager.getInstance();
    private final EmbeddedNodeManager nodeManager = EmbeddedNodeManager.getInstance();
    private final TrafficDAO          dao         = new TrafficDAO();
    private final PredictiveEngine    predictor   = PredictiveEngine.getInstance();

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean connected     = false;
    private boolean emergencyMode = false;
    private boolean heatmapMode   = false;
    private boolean alertBlinking = false;
    private int     navPosition   = 1;
    private int     navTotal      = 0;
    private String  searchFilter  = "";
    private double  animTick      = 0;
    private double  zoomLevel     = 1.0;
    private double  mapOffsetX    = 0;
    private double  mapOffsetY    = 0;
    private List<SensorReading> lastReadings = new ArrayList<>();

    private final LinkedHashMap<String, double[]> nodeRegistry      = new LinkedHashMap<>();
    private final HashMap<String, String>         nodeNameCache     = new HashMap<>();
    private final HashMap<String, String>         nodeLocationCache = new HashMap<>();

    // Roads: H at 0.20,0.42,0.65,0.85 — V at 0.18,0.38,0.62,0.82
    // All node positions are exact intersections of these roads
    private static final String[]   BASE_IDS = {"NODE_A","NODE_B","NODE_C","NODE_D","NODE_E"};
    private static final double[][] BASE_XY  = {
        {0.38,0.42}, {0.38,0.20}, {0.62,0.42}, {0.18,0.42}, {0.38,0.65}
    };
    private static final double[][] EXTRA_XY = {
        {0.18,0.20},{0.62,0.20},{0.18,0.65},{0.62,0.65},
        {0.82,0.42},{0.18,0.85},{0.38,0.85},{0.62,0.85},
        {0.82,0.20},{0.82,0.65},{0.82,0.85},{0.18,0.20}
    };
    private static final double[][] ROAD_DEFS = {
        {0,0.20,1,0.20,1},{0,0.42,1,0.42,1},{0,0.65,1,0.65,1},{0,0.85,1,0.85,1},
        {0.18,0,0.18,1,0},{0.38,0,0.38,1,0},{0.62,0,0.62,1,0},{0.82,0,0.82,1,0},
    };

    // Vehicle sim
    private final ArrayList<double[]> vehicles        = new ArrayList<>();
    private final int[]               roadVehicleCount = new int[ROAD_DEFS.length];
    private boolean vehiclesInited = false;
    private int     lastNodeCount  = 0;
    private final Random vehicleRng = new Random(99);

    private ScheduledExecutorService refreshScheduler;
    private AnimationTimer mapAnimator, clockTimer;
    private Timeline alertBlink;
    private Canvas cityCanvas;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy — HH:mm:ss");
    private static final DateTimeFormatter LOG_FMT  = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ═══════════════════════════════════════════════════════
    //  INIT
    // ═══════════════════════════════════════════════════════
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        startClock();
        Platform.runLater(() -> {
            cityCanvas = new Canvas();
            mapStack.getChildren().add(cityCanvas);
            cityCanvas.widthProperty().bind(mapStack.widthProperty());
            cityCanvas.heightProperty().bind(mapStack.heightProperty());
            cityCanvas.widthProperty().addListener((o,ov,nv) -> redrawMap());
            cityCanvas.heightProperty().addListener((o,ov,nv) -> redrawMap());
            mapStack.setOnScroll(e -> {
                if (!connected) return;
                zoomLevel = Math.max(0.5, Math.min(4.0, zoomLevel + (e.getDeltaY() > 0 ? 0.15 : -0.15)));
                if (zoomLabel != null) zoomLabel.setText(Math.round(zoomLevel*100)+"%");
            });
            mapStack.setOnMouseClicked(e -> handleMapClick(e.getX(), e.getY()));
            drawOfflineMap();
            enableActionButtons(false);
        });
    }

    // ═══════════════════════════════════════════════════════
    //  NODE REGISTRY
    // ═══════════════════════════════════════════════════════
    private void initNodeRegistry() {
        nodeRegistry.clear(); nodeNameCache.clear(); nodeLocationCache.clear();
        for (int i=0; i<BASE_IDS.length; i++)
            nodeRegistry.put(BASE_IDS[i], new double[]{BASE_XY[i][0], BASE_XY[i][1]});
        List<String> dbIds = dao.getAllNodeIds();
        int extra = 0;
        for (String id : dbIds) {
            if (!nodeRegistry.containsKey(id)) {
                int idx = Math.min(extra, EXTRA_XY.length-1);
                nodeRegistry.put(id, new double[]{EXTRA_XY[idx][0], EXTRA_XY[idx][1]});
                extra++;
            }
        }
        loadNodeMetadata();
        for (String id : dbIds) {
            if (nodeManager.getNode(id) == null)
                nodeManager.addNode(id, nodeNameCache.getOrDefault(id, id), 60);
        }
    }

    private void loadNodeMetadata() {
        try (Statement st = dbManager.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT node_id,node_name,location FROM intersection_nodes")) {
            while (rs.next()) {
                nodeNameCache.put(rs.getString("node_id"), rs.getString("node_name"));
                nodeLocationCache.put(rs.getString("node_id"), rs.getString("location"));
            }
        } catch (Exception e) { System.err.println("[Registry] "+e.getMessage()); }
    }

    private void registerNewNode(String id, String name, String location) {
        int extra = Math.max(0, nodeRegistry.size()-BASE_IDS.length);
        int idx = Math.min(extra, EXTRA_XY.length-1);
        nodeRegistry.put(id, new double[]{EXTRA_XY[idx][0], EXTRA_XY[idx][1]});
        nodeNameCache.put(id, name);
        nodeLocationCache.put(id, location.isEmpty() ? name : location);
    }

    // ═══════════════════════════════════════════════════════
    //  DB CONNECT / DISCONNECT
    // ═══════════════════════════════════════════════════════
    @FXML
    private void handleDbToggle() {
        if (!connected) {
            boolean ok = dbManager.connect();
            if (ok) {
                connected = true;
                dbStatusBadge.setText("● Connected");
                dbStatusBadge.getStyleClass().setAll("status-badge-on");
                dbConnectBtn.setText("Disconnect DB");
                dbConnectBtn.getStyleClass().setAll("disconnect-btn");
                nodeManager.startAll();
                initNodeRegistry();
                startRefreshLoop();
                startMapAnimation();
                loadNavData();
                enableActionButtons(true);
            } else {
                showAlert("Connection Failed",
                    "Could not connect to MySQL.\nCheck your password in DatabaseManager.java.",
                    Alert.AlertType.ERROR);
            }
        } else {
            nodeManager.stopAll();
            if (refreshScheduler!=null) refreshScheduler.shutdownNow();
            if (mapAnimator!=null) mapAnimator.stop();
            if (alertBlink!=null) alertBlink.stop();
            dbManager.disconnect();
            connected=false; emergencyMode=false; heatmapMode=false;
            dbStatusBadge.setText("● Disconnected");
            dbStatusBadge.getStyleClass().setAll("status-badge-off");
            dbConnectBtn.setText("Connect DB");
            dbConnectBtn.getStyleClass().setAll("connect-btn");
            if (emergencyBtn!=null) emergencyBtn.getStyleClass().setAll("emergency-btn");
            if (heatmapBtn!=null) heatmapBtn.setText("Heatmap: OFF");
            disableNavButtons(true); enableActionButtons(false);
            nodeCardsContainer.getChildren().clear();
            incidentsContainer.getChildren().clear();
            lastReadings=new ArrayList<>(); nodeRegistry.clear(); vehiclesInited=false;
            clearStats(); drawOfflineMap();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  EMERGENCY OVERRIDE
    // ═══════════════════════════════════════════════════════
    @FXML
    private void handleEmergencyOverride() {
        if (!connected) return;
        emergencyMode = !emergencyMode;
        // Set override on ALL nodes including dynamically added ones
        for (EmbeddedNode n : nodeManager.getNodes()) n.setEmergencyOverride(emergencyMode);
        if (emergencyMode) {
            emergencyBtn.setText("EMERGENCY ACTIVE — Click to Cancel");
            emergencyBtn.getStyleClass().setAll("emergency-btn-active");
            showAlert("Emergency Override ACTIVE",
                "All traffic lights forced to GREEN.\nClick the button again to cancel.",
                Alert.AlertType.WARNING);
        } else {
            emergencyBtn.setText("Emergency Override");
            emergencyBtn.getStyleClass().setAll("emergency-btn");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  HEATMAP
    // ═══════════════════════════════════════════════════════
    @FXML
    private void handleHeatmapToggle() {
        if (!connected) return;
        heatmapMode = !heatmapMode;
        heatmapBtn.setText(heatmapMode ? "Heatmap: ON" : "Heatmap: OFF");
    }

    // ═══════════════════════════════════════════════════════
    //  ZOOM
    // ═══════════════════════════════════════════════════════
    @FXML private void handleZoomIn()    { zoomLevel=Math.min(4.0,zoomLevel+0.25); if(zoomLabel!=null)zoomLabel.setText(Math.round(zoomLevel*100)+"%"); }
    @FXML private void handleZoomOut()   { zoomLevel=Math.max(0.5,zoomLevel-0.25); if(zoomLabel!=null)zoomLabel.setText(Math.round(zoomLevel*100)+"%"); }
    @FXML private void handleZoomReset() { zoomLevel=1.0; mapOffsetX=0; mapOffsetY=0; if(zoomLabel!=null)zoomLabel.setText("100%"); }

    // ═══════════════════════════════════════════════════════
    //  MAP CLICK — node popup
    // ═══════════════════════════════════════════════════════
    private void handleMapClick(double mx, double my) {
        if (!connected||cityCanvas==null) return;
        double w=cityCanvas.getWidth(), h=cityCanvas.getHeight();
        double cx=w/2, cy=h/2;
        double canvasX=(mx-mapOffsetX-cx)/zoomLevel+cx;
        double canvasY=(my-mapOffsetY-cy)/zoomLevel+cy;
        for (Map.Entry<String,double[]> entry : nodeRegistry.entrySet()) {
            double nx=entry.getValue()[0]*w, ny=entry.getValue()[1]*h;
            if (Math.sqrt((canvasX-nx)*(canvasX-nx)+(canvasY-ny)*(canvasY-ny))<=18) {
                showNodePopup(entry.getKey()); return;
            }
        }
    }

    private void showNodePopup(String nodeId) {
        EmbeddedNode node = nodeManager.getNode(nodeId);
        String pred = predictor.getPredictionText(nodeId);
        SensorReading r = lastReadings.stream().filter(x->x.getNodeId().equals(nodeId)).findFirst().orElse(null);
        Dialog<ButtonType> d = styledDialog(nodeId+" — "+shortName(nodeId), "Live Node Details");
        VBox content = new VBox(10); content.setPadding(new Insets(16));
        content.setStyle("-fx-background-color:#0a0f1e;");

        HBox statusRow = new HBox(10); statusRow.setAlignment(Pos.CENTER_LEFT);
        if (node!=null) {
            Label runLbl = new Label(node.isRunning()?"● RUNNING":"○ STOPPED");
            runLbl.setStyle(node.isRunning()?"-fx-text-fill:#34d399;-fx-font-weight:bold;":"-fx-text-fill:#f87171;-fx-font-weight:bold;");
            statusRow.getChildren().add(runLbl);
        }
        content.getChildren().add(statusRow);

        GridPane grid = new GridPane(); grid.setHgap(16); grid.setVgap(6);
        String[][] rows = {
            {"Vehicles/min",  r!=null?String.valueOf(r.getVehiclesPerMin()):"—"},
            {"Light state",   r!=null?r.getLightState().name():"—"},
            {"Density",       r!=null?r.getDensityLevel().name():"—"},
            {"Avg wait",      r!=null?r.getAvgWaitSec()+"s":"—"},
            {"Threshold H",   node!=null?node.getHighThreshold()+" veh/min":"—"},
            {"Threshold C",   node!=null?node.getCriticalThreshold()+" veh/min":"—"},
            {"AI Prediction", pred},
            {"Location",      locationFor(nodeId)},
        };
        for (int i=0; i<rows.length; i++) {
            Label k=new Label(rows[i][0]); k.setStyle("-fx-text-fill:#374151;-fx-font-size:12px;");
            Label v=new Label(rows[i][1]); v.setStyle("-fx-text-fill:#c0c9d8;-fx-font-size:12px;-fx-font-weight:bold;");
            grid.add(k,0,i); grid.add(v,1,i);
        }
        content.getChildren().add(grid);
        d.getDialogPane().setContent(content); d.getDialogPane().setMinWidth(380);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); d.showAndWait();
    }

    // ═══════════════════════════════════════════════════════
    //  ANALYTICS
    // ═══════════════════════════════════════════════════════
    @FXML
    private void handleAnalytics() {
        if (!connected) return;
        Dialog<ButtonType> d = styledDialog("Analytics Dashboard","Real-time system metrics");
        d.getDialogPane().setMinWidth(780); d.getDialogPane().setMinHeight(540);
        TabPane tabs = new TabPane();
        tabs.setStyle("-fx-background-color:#0a0f1e;");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab chartTab = new Tab("Node Comparison");
        Canvas barChart = new Canvas(720,300);
        drawNodeComparisonChart(barChart);
        VBox chartBox = new VBox(8); chartBox.setPadding(new Insets(12));
        chartBox.setStyle("-fx-background-color:#0a0f1e;");
        Label chartLbl = new Label("  Live vehicles/min per node");
        chartLbl.setStyle("-fx-text-fill:#374151;-fx-font-size:11px;");
        chartBox.getChildren().addAll(chartLbl, barChart);
        chartTab.setContent(chartBox);

        Tab histTab = new Tab("Incident History");
        histTab.setContent(buildIncidentHistoryTable());

        Tab predTab = new Tab("Predictive AI");
        predTab.setContent(buildPredictionPanel());

        tabs.getTabs().addAll(chartTab, histTab, predTab);
        d.getDialogPane().setContent(tabs);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private void drawNodeComparisonChart(Canvas c) {
        GraphicsContext gc = c.getGraphicsContext2D();
        double w=c.getWidth(), h=c.getHeight();
        gc.setFill(Color.web("#060a12")); gc.fillRect(0,0,w,h);
        if (lastReadings.isEmpty()) { gc.setFill(Color.web("#374151")); gc.setFont(Font.font("Consolas",13)); gc.fillText("No data yet",20,h/2); return; }
        int n=lastReadings.size(); double barW=Math.min(80,(w-80)/n-16);
        double maxV=Math.max(lastReadings.stream().mapToDouble(SensorReading::getVehiclesPerMin).max().orElse(100),110);
        for (int i=0; i<n; i++) {
            SensorReading r=lastReadings.get(i);
            double x=40+i*(barW+16), barH=((double)r.getVehiclesPerMin()/maxV)*(h-80), y=h-40-barH;
            Color bc=switch(r.getDensityLevel()){case LOW->Color.web("#059669",0.85);case MEDIUM->Color.web("#3b82f6",0.85);case HIGH->Color.web("#f59e0b",0.85);default->Color.web("#dc2626",0.90);};
            gc.setFill(bc.deriveColor(0,1,1,0.12)); gc.fillRoundRect(x-4,y-4,barW+8,barH+8,6,6);
            gc.setFill(bc); gc.fillRoundRect(x,y,barW,barH,4,4);
            gc.setFill(Color.web("#c0c9d8")); gc.setFont(Font.font("Consolas",FontWeight.BOLD,12));
            gc.fillText(String.valueOf(r.getVehiclesPerMin()),x+barW/2-10,y-6);
            gc.setFill(Color.web("#374151")); gc.setFont(Font.font("Consolas",11));
            gc.fillText(labelFor(r.getNodeId()),x+barW/2-8,h-22);
        }
        double critY=h-40-(110.0/maxV)*(h-80);
        gc.setStroke(Color.web("#dc2626",0.5)); gc.setLineWidth(0.8); gc.setLineDashes(4,4);
        gc.strokeLine(20,critY,w-20,critY); gc.setLineDashes(0);
    }

    private ScrollPane buildIncidentHistoryTable() {
        List<IncidentLog> all = dao.getAllIncidents();
        TableView<IncidentLog> table = new TableView<>();
        table.setStyle("-fx-background-color:#0a0f1e;-fx-border-color:#162034;"); table.getStyleClass().add("analytics-table"); table.setPrefHeight(400);
        TableColumn<IncidentLog,String> c1=col("ID",70,r->String.valueOf(r.getIncidentId()));
        TableColumn<IncidentLog,String> c2=col("Node",100,r->r.getNodeId());
        TableColumn<IncidentLog,String> c3=col("Type",120,r->r.getIncidentType().name());
        TableColumn<IncidentLog,String> c4=col("Severity",90,r->r.getSeverity().name());
        TableColumn<IncidentLog,String> c5=col("Resolved",80,r->r.isResolved()?"Yes":"No");
        TableColumn<IncidentLog,String> c6=col("Time",140,r->r.getCreatedAt()!=null?r.getCreatedAt().format(LOG_FMT):"");
        TableColumn<IncidentLog,String> c7=col("Description",260,r->r.getDescription());
        table.getColumns().addAll(c1,c2,c3,c4,c5,c6,c7);
        table.setItems(FXCollections.observableArrayList(all));
        TextField tf=new TextField(); tf.setPromptText("Filter...");
        tf.setStyle("-fx-background-color:#0d1426;-fx-border-color:#162034;-fx-text-fill:#c0c9d8;-fx-border-radius:4;-fx-background-radius:4;-fx-font-size:11px;-fx-padding:4 8 4 8;");
        tf.textProperty().addListener((obs,ov,nv)->{ String f=nv.toLowerCase(); table.setItems(FXCollections.observableArrayList(all.stream().filter(i->i.getNodeId().toLowerCase().contains(f)||i.getIncidentType().name().toLowerCase().contains(f)||i.getDescription().toLowerCase().contains(f)).toList())); });
        Button expBtn=new Button("Export CSV"); expBtn.setStyle("-fx-background-color:#052e1c;-fx-border-color:#059669;-fx-border-width:0.5;-fx-border-radius:4;-fx-background-radius:4;-fx-text-fill:#34d399;-fx-font-size:11px;-fx-padding:4 10 4 10;-fx-cursor:hand;");
        expBtn.setOnAction(e->handleExportCSVDirect(all));
        HBox bar=new HBox(8,tf,expBtn); bar.setPadding(new Insets(8,12,8,12)); bar.setAlignment(Pos.CENTER_LEFT); bar.setStyle("-fx-background-color:#0a0f1e;"); HBox.setHgrow(tf,Priority.ALWAYS);
        VBox box=new VBox(0,bar,table); box.setStyle("-fx-background-color:#0a0f1e;");
        ScrollPane sp=new ScrollPane(box); sp.setFitToWidth(true); sp.setFitToHeight(true); sp.setStyle("-fx-background-color:#0a0f1e;-fx-background:#0a0f1e;-fx-border-color:transparent;");
        return sp;
    }

    private <T> TableColumn<T,String> col(String title, int width, java.util.function.Function<T,String> fn) {
        TableColumn<T,String> c=new TableColumn<>(title); c.setPrefWidth(width); c.setMinWidth(40);
        c.setCellValueFactory(d->new SimpleStringProperty(fn.apply(d.getValue()))); return c;
    }

    private VBox buildPredictionPanel() {
        VBox box=new VBox(10); box.setPadding(new Insets(16)); box.setStyle("-fx-background-color:#0a0f1e;");
        Label title=new Label("AI Congestion Forecast — linear regression on last 8 readings per node");
        title.setStyle("-fx-text-fill:#374151;-fx-font-size:11px;"); box.getChildren().add(title);
        predictor.update(nodeRegistry.keySet());
        for (String id : nodeRegistry.keySet()) {
            String pred=predictor.getPredictionText(id); boolean crit=predictor.isPredictedCritical(id); int val=predictor.getPrediction(id);
            HBox row=new HBox(12); row.setPadding(new Insets(8,12,8,12)); row.setStyle("-fx-background-color:#0d1426;-fx-border-color:#162034;-fx-border-width:0.5;-fx-border-radius:6;-fx-background-radius:6;"); row.setAlignment(Pos.CENTER_LEFT);
            Label idL=new Label(id); idL.setMinWidth(100); idL.setStyle("-fx-text-fill:#c0c9d8;-fx-font-size:12px;-fx-font-weight:bold;");
            Canvas mini=new Canvas(120,18); drawMiniTrendBar(mini,id,val);
            Label predL=new Label(pred); predL.setStyle(crit?"-fx-text-fill:#f87171;-fx-font-size:11px;-fx-font-weight:bold;":val>=80?"-fx-text-fill:#fbbf24;-fx-font-size:11px;":"-fx-text-fill:#34d399;-fx-font-size:11px;");
            row.getChildren().addAll(idL,mini,predL); box.getChildren().add(row);
        }
        return box;
    }

    private void drawMiniTrendBar(Canvas c, String nodeId, int predicted) {
        GraphicsContext gc=c.getGraphicsContext2D(); double w=c.getWidth(), h=c.getHeight();
        gc.setFill(Color.web("#060a12")); gc.fillRect(0,0,w,h);
        List<SensorReading> history=dao.getReadingsForNode(nodeId,8); if (history.isEmpty()) return;
        Collections.reverse(history);
        int max=Math.max(150,history.stream().mapToInt(SensorReading::getVehiclesPerMin).max().orElse(50));
        double bw=(w-10)/(history.size()+1);
        for (int i=0; i<history.size(); i++) { double bh=(history.get(i).getVehiclesPerMin()/(double)max)*(h-4); gc.setFill(Color.web("#3b82f6",0.7)); gc.fillRoundRect(5+i*bw,h-2-bh,bw-1,bh,1,1); }
        double pbh=(predicted/(double)max)*(h-4); gc.setFill(predicted>=110?Color.web("#dc2626",0.9):Color.web("#f59e0b",0.9)); gc.fillRoundRect(5+history.size()*bw,h-2-pbh,bw-1,pbh,1,1);
    }

    // ═══════════════════════════════════════════════════════
    //  NAVIGATION
    // ═══════════════════════════════════════════════════════
    @FXML private void navFirst()  { navPosition=1;                                   loadRecord(); }
    @FXML private void navMinus3() { navPosition=Math.max(1,navPosition-3);           loadRecord(); }
    @FXML private void navPrev()   { navPosition=Math.max(1,navPosition-1);           loadRecord(); }
    @FXML private void navNext()   { navPosition=Math.min(navTotal,navPosition+1);    loadRecord(); }
    @FXML private void navPlus3()  { navPosition=Math.min(navTotal,navPosition+3);    loadRecord(); }
    @FXML private void navLast()   { navPosition=navTotal;                             loadRecord(); }

    private void loadNavData() { navTotal=dao.getTotalIncidents(); navPosition=navTotal; navTotalLabel.setText(String.valueOf(navTotal)); disableNavButtons(navTotal==0); loadRecord(); }
    private void loadRecord()  { if(navTotal==0)return; navCurrentLabel.setText(String.valueOf(navPosition)); IncidentLog inc=dao.getIncidentAtOffset(navPosition); if(inc!=null)displayIncidentDetail(inc); }
    private void displayIncidentDetail(IncidentLog inc) {
        incidentTypeLabel.setText(inc.getIncidentType().name());
        incidentNodeLabel.setText(inc.getNodeId()+" — "+locationFor(inc.getNodeId()));
        incidentTimeLabel.setText(inc.getCreatedAt()!=null?inc.getCreatedAt().format(LOG_FMT):"");
        incidentDescLabel.setText(inc.getDescription());
        incidentTypeLabel.getStyleClass().setAll("incident-type-label",switch(inc.getSeverity()){case CRITICAL->"severity-critical";case WARNING->"severity-warning";default->"severity-info";});
    }

    // ═══════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════
    @FXML private void handleSearch() { searchFilter=searchField!=null?searchField.getText().trim().toLowerCase():""; refreshIncidentPanel(dao.getRecentUnresolvedIncidents(50)); }

    // ═══════════════════════════════════════════════════════
    //  ADD NODE
    // ═══════════════════════════════════════════════════════
    @FXML private void handleAddNode() {
        if (!connected){showAlert("Not Connected","Connect first.",Alert.AlertType.WARNING);return;}
        Dialog<ButtonType> d=styledDialog("Add Intersection Node","Register a new sensor node");
        GridPane g=dialogGrid();
        TextField idF=new TextField(); idF.setPromptText("NODE_F");
        TextField nF=new TextField();  nF.setPromptText("West Industrial");
        TextField lF=new TextField();  lF.setPromptText("West Rd & Factory Ave");
        TextField tF=new TextField("60");
        g.add(dialogLabel("Node ID:"),0,0); g.add(idF,0,1);
        g.add(dialogLabel("Name:"),1,0);    g.add(nF,1,1);
        g.add(dialogLabel("Location:"),0,2);g.add(lF,0,3);
        g.add(dialogLabel("Base traffic:"),1,2); g.add(tF,1,3);
        d.getDialogPane().setContent(g); d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        d.showAndWait().ifPresent(bt->{
            if(bt!=ButtonType.OK)return;
            String id=idF.getText().trim().toUpperCase(), name=nF.getText().trim(), loc=lF.getText().trim();
            if(id.isEmpty()||name.isEmpty()){showAlert("Missing Fields","Node ID and Name required.",Alert.AlertType.WARNING);return;}
            if(!id.matches("[A-Z0-9_]+")){showAlert("Invalid ID","Letters, numbers, underscores only.",Alert.AlertType.WARNING);return;}
            int base=60; try{base=Integer.parseInt(tF.getText().trim());}catch(Exception ignored){}
            String result=dao.insertNode(id,name,loc);
            if(result.equals("EXISTS")){showAlert("Already Exists","Node "+id+" already exists.",Alert.AlertType.WARNING);return;}
            if(result.startsWith("ERROR")){showAlert("DB Error",result,Alert.AlertType.ERROR);return;}
            registerNewNode(id,name,loc);
            nodeManager.addNode(id,name,base);
            statActiveNodes.setText(nodeRegistry.size()+" / "+nodeRegistry.size());
            nodeCardsContainer.getChildren().add(buildWaitingCard(id,name));
            showAlert("Node Added",id+" — "+name+" is now live.",Alert.AlertType.INFORMATION);
        });
    }

    // ═══════════════════════════════════════════════════════
    //  TRIGGER INCIDENT
    // ═══════════════════════════════════════════════════════
    @FXML private void handleTriggerIncident() {
        if (!connected){showAlert("Not Connected","Connect first.",Alert.AlertType.WARNING);return;}
        Dialog<ButtonType> d=styledDialog("Trigger Incident","Manually inject an incident");
        GridPane g=dialogGrid();
        ComboBox<String> nodeBox=new ComboBox<>(); List<String> allIds=dao.getAllNodeIds(); if(allIds.isEmpty())allIds=Arrays.asList("NODE_A","NODE_B","NODE_C","NODE_D","NODE_E"); nodeBox.getItems().addAll(allIds); nodeBox.setValue(allIds.get(0));
        ComboBox<String> typeBox=new ComboBox<>(); typeBox.getItems().addAll("CONGESTION","HIGH_DENSITY","LIGHT_FAULT","CLEARED"); typeBox.setValue("CONGESTION");
        ComboBox<String> sevBox=new ComboBox<>();  sevBox.getItems().addAll("INFO","WARNING","CRITICAL"); sevBox.setValue("CRITICAL");
        TextField descF=new TextField("Manual override — emergency scenario"); descF.setPrefWidth(260);
        g.add(dialogLabel("Node:"),0,0);g.add(nodeBox,0,1);g.add(dialogLabel("Type:"),1,0);g.add(typeBox,1,1);
        g.add(dialogLabel("Severity:"),0,2);g.add(sevBox,0,3);g.add(dialogLabel("Description:"),1,2);g.add(descF,1,3);
        d.getDialogPane().setContent(g); d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        d.showAndWait().ifPresent(bt->{if(bt!=ButtonType.OK)return; dao.insertIncident(new IncidentLog(nodeBox.getValue(),IncidentType.valueOf(typeBox.getValue()),descF.getText().trim(),Severity.valueOf(sevBox.getValue()))); loadNavData(); showAlert("Triggered","Incident logged.",Alert.AlertType.INFORMATION);});
    }

    // ═══════════════════════════════════════════════════════
    //  ADJUST THRESHOLDS
    // ═══════════════════════════════════════════════════════
    @FXML private void handleAdjustThresholds() {
        if (!connected){showAlert("Not Connected","Connect first.",Alert.AlertType.WARNING);return;}
        Dialog<ButtonType> d=styledDialog("Adjust Thresholds","Changes apply immediately to embedded threads");
        GridPane g=dialogGrid();
        g.add(dialogLabel("Node"),0,0); g.add(dialogLabel("High (veh/min)"),1,0); g.add(dialogLabel("Critical"),2,0);
        Map<String,TextField[]> fields=new LinkedHashMap<>(); int row=1;
        for (String id:nodeRegistry.keySet()) {
            EmbeddedNode n=nodeManager.getNode(id);
            TextField hF=new TextField(n!=null?String.valueOf(n.getHighThreshold()):"80");
            TextField cF=new TextField(n!=null?String.valueOf(n.getCriticalThreshold()):"110");
            hF.setPrefWidth(90); cF.setPrefWidth(90);
            g.add(new Label(id),0,row); g.add(hF,1,row); g.add(cF,2,row); fields.put(id,new TextField[]{hF,cF}); row++;
        }
        ScrollPane sp=new ScrollPane(g); sp.setFitToWidth(true); sp.setPrefHeight(Math.min(300,row*36+40)); sp.setStyle("-fx-background-color:transparent;-fx-border-color:transparent;");
        d.getDialogPane().setContent(sp); d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        d.showAndWait().ifPresent(bt->{if(bt!=ButtonType.OK)return; fields.forEach((id,tf)->{EmbeddedNode n=nodeManager.getNode(id);if(n==null)return;try{n.setHighThreshold(Integer.parseInt(tf[0].getText().trim()));}catch(Exception ignored){}try{n.setCriticalThreshold(Integer.parseInt(tf[1].getText().trim()));}catch(Exception ignored){}});showAlert("Updated","All thresholds applied.",Alert.AlertType.INFORMATION);});
    }

    // ═══════════════════════════════════════════════════════
    //  NODE TOGGLE
    // ═══════════════════════════════════════════════════════
    @FXML private void handleNodeToggle() {
        if (!connected){showAlert("Not Connected","Connect first.",Alert.AlertType.WARNING);return;}
        List<String> ids=new ArrayList<>(nodeRegistry.keySet());
        Dialog<ButtonType> d=styledDialog("Node Controls","Enable or disable embedded sensor nodes");
        VBox box=new VBox(0); box.setStyle("-fx-background-color:#0a0f1e;");
        HBox hdr=new HBox(); hdr.setPadding(new Insets(10,20,8,20)); hdr.setStyle("-fx-background-color:#0d1426;-fx-border-color:#162034;-fx-border-width:0 0 0.5 0;");
        Label h1=new Label("Node"); h1.setMinWidth(230); h1.setStyle("-fx-text-fill:#374151;-fx-font-size:11px;-fx-font-weight:bold;");
        Label h2=new Label("Status"); h2.setMinWidth(90); h2.setStyle("-fx-text-fill:#374151;-fx-font-size:11px;-fx-font-weight:bold;");
        Label h3=new Label("Action"); h3.setStyle("-fx-text-fill:#374151;-fx-font-size:11px;-fx-font-weight:bold;");
        hdr.getChildren().addAll(h1,h2,h3); box.getChildren().add(hdr);
        for (String id:ids) {
            EmbeddedNode node=nodeManager.getNode(id); boolean running=node!=null&&node.isRunning();
            HBox rowH=new HBox(0); rowH.setPadding(new Insets(10,20,10,20)); rowH.setAlignment(Pos.CENTER_LEFT); rowH.setStyle("-fx-border-color:#162034;-fx-border-width:0 0 0.5 0;");
            VBox nameCol=new VBox(2); nameCol.setMinWidth(230);
            Label idL=new Label(id); idL.setStyle("-fx-text-fill:#e2e8f0;-fx-font-size:12px;-fx-font-weight:bold;");
            Label nmL=new Label(shortName(id)); nmL.setStyle("-fx-text-fill:#374151;-fx-font-size:11px;");
            nameCol.getChildren().addAll(idL,nmL);
            Label stL=new Label(running?"● ACTIVE":"○ STOPPED"); stL.setMinWidth(90); stL.setStyle(running?"-fx-text-fill:#34d399;-fx-font-size:11px;":"-fx-text-fill:#f87171;-fx-font-size:11px;");
            Button btn=new Button(running?"Stop":"Start");
            btn.setStyle(running?"-fx-background-color:#2a0f0f;-fx-border-color:#dc2626;-fx-border-width:0.5;-fx-border-radius:5;-fx-background-radius:5;-fx-text-fill:#f87171;-fx-font-size:12px;-fx-padding:5 16 5 16;-fx-cursor:hand;":"-fx-background-color:#052e1c;-fx-border-color:#059669;-fx-border-width:0.5;-fx-border-radius:5;-fx-background-radius:5;-fx-text-fill:#34d399;-fx-font-size:12px;-fx-padding:5 16 5 16;-fx-cursor:hand;");
            btn.setOnAction(e->{EmbeddedNode n=nodeManager.getNode(id);if(n==null)return;if(n.isRunning()){n.stop();btn.setText("Start");btn.setStyle("-fx-background-color:#052e1c;-fx-border-color:#059669;-fx-border-width:0.5;-fx-border-radius:5;-fx-background-radius:5;-fx-text-fill:#34d399;-fx-font-size:12px;-fx-padding:5 16 5 16;-fx-cursor:hand;");stL.setText("○ STOPPED");stL.setStyle("-fx-text-fill:#f87171;-fx-font-size:11px;");}else{n.start();btn.setText("Stop");btn.setStyle("-fx-background-color:#2a0f0f;-fx-border-color:#dc2626;-fx-border-width:0.5;-fx-border-radius:5;-fx-background-radius:5;-fx-text-fill:#f87171;-fx-font-size:12px;-fx-padding:5 16 5 16;-fx-cursor:hand;");stL.setText("● ACTIVE");stL.setStyle("-fx-text-fill:#34d399;-fx-font-size:11px;");}});
            rowH.getChildren().addAll(nameCol,stL,btn); box.getChildren().add(rowH);
        }
        ScrollPane sp=new ScrollPane(box); sp.setFitToWidth(true); sp.setPrefHeight(Math.min(400,ids.size()*62+50)); sp.setPrefWidth(440); sp.setStyle("-fx-background-color:#0a0f1e;-fx-background:#0a0f1e;-fx-border-color:transparent;");
        d.getDialogPane().setContent(sp); d.getDialogPane().setMinWidth(480); d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); d.showAndWait();
    }

    // ═══════════════════════════════════════════════════════
    //  EXPORT CSV
    // ═══════════════════════════════════════════════════════
    @FXML private void handleExportCSV() { handleExportCSVDirect(dao.getAllIncidents()); }
    private void handleExportCSVDirect(List<IncidentLog> all) {
        FileChooser fc=new FileChooser(); fc.setTitle("Export"); fc.setInitialFileName("smartgrid_incidents.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
        File file=fc.showSaveDialog(exportBtn!=null?exportBtn.getScene().getWindow():null);
        if (file==null) return;
        try (PrintWriter pw=new PrintWriter(new FileWriter(file))) {
            pw.println("incident_id,node_id,type,severity,description,resolved,created_at");
            for (IncidentLog inc:all) pw.printf("%d,%s,%s,%s,\"%s\",%s,%s%n",inc.getIncidentId(),inc.getNodeId(),inc.getIncidentType(),inc.getSeverity(),inc.getDescription().replace("\"","'"),inc.isResolved(),inc.getCreatedAt()!=null?inc.getCreatedAt():"");
            showAlert("Exported","Exported "+all.size()+" records.",Alert.AlertType.INFORMATION);
        } catch (Exception e){showAlert("Export Failed",e.getMessage(),Alert.AlertType.ERROR);}
    }

    // ═══════════════════════════════════════════════════════
    //  REFRESH LOOP
    // ═══════════════════════════════════════════════════════
    private void startRefreshLoop() {
        refreshScheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"UIRefresh");t.setDaemon(true);return t;});
        refreshScheduler.scheduleAtFixedRate(()->{
            if (!dbManager.isConnected()) return;
            try {
                List<SensorReading> readings=dao.getLatestReadingsPerNode();
                List<IncidentLog> incidents=dao.getRecentUnresolvedIncidents(50);
                int totalVeh=dao.getTotalVehicles(), totalInc=dao.getTotalIncidents();
                List<SensorReading> recent=dao.getRecentReadings(8);
                double avgWait=dao.getAvgWaitTime();
                predictor.update(nodeRegistry.keySet());
                Platform.runLater(()->{
                    lastReadings=readings;
                    updateNodeCards(readings);
                    updateStats(readings,incidents,totalVeh,totalInc,avgWait);
                    refreshIncidentPanel(incidents);
                    drawMiniChart(recent);
                    updatePredictionLabel();
                    if(navTotal!=totalInc){navTotal=totalInc;navTotalLabel.setText(String.valueOf(navTotal));disableNavButtons(navTotal==0);}
                    if(incidents.stream().anyMatch(i->i.getSeverity()==Severity.CRITICAL))startAlertBlink();
                });
            } catch (Exception e){System.err.println("[Refresh] "+e.getMessage());}
        },1,4,TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════
    //  MAP ANIMATION + DRAWING
    // ═══════════════════════════════════════════════════════
    private void startMapAnimation() {
        mapAnimator=new AnimationTimer(){@Override public void handle(long now){animTick+=0.010;redrawMap();}};
        mapAnimator.start();
    }

    private void redrawMap() {
        if (cityCanvas==null) return;
        double w=cityCanvas.getWidth(), h=cityCanvas.getHeight(); if(w<50||h<50)return;
        GraphicsContext gc=cityCanvas.getGraphicsContext2D();
        gc.save();
        gc.setFill(Color.web("#060a12")); gc.fillRect(0,0,w,h);
        double cx=w/2, cy=h/2;
        gc.translate(cx+mapOffsetX,cy+mapOffsetY); gc.scale(zoomLevel,zoomLevel); gc.translate(-cx,-cy);
        double sw=w, sh=h;

        if (heatmapMode) drawHeatmap(gc,sw,sh);

        // Roads — match ROAD_DEFS positions exactly
        double[] hRoads={0.20,0.42,0.65,0.85}, vRoads={0.18,0.38,0.62,0.82};
        gc.setStroke(Color.web("#0d1628")); gc.setLineWidth(16);
        for (double r:hRoads) gc.strokeLine(0,sh*r,sw,sh*r);
        for (double r:vRoads) gc.strokeLine(sw*r,0,sw*r,sh);
        gc.setStroke(Color.web("#162034")); gc.setLineWidth(0.8); gc.setLineDashes(8,8);
        for (double r:hRoads) gc.strokeLine(0,sh*r,sw,sh*r);
        for (double r:vRoads) gc.strokeLine(sw*r,0,sw*r,sh);
        gc.setLineDashes(0);

        // 25 city blocks
        double[][] blocks={
            {0.01,0.01,0.15,0.17},{0.20,0.01,0.16,0.17},{0.40,0.01,0.20,0.17},{0.64,0.01,0.16,0.17},{0.84,0.01,0.15,0.17},
            {0.01,0.22,0.15,0.18},{0.20,0.22,0.16,0.18},{0.40,0.22,0.20,0.18},{0.64,0.22,0.16,0.18},{0.84,0.22,0.15,0.18},
            {0.01,0.44,0.15,0.19},{0.20,0.44,0.16,0.19},{0.40,0.44,0.20,0.19},{0.64,0.44,0.16,0.19},{0.84,0.44,0.15,0.19},
            {0.01,0.67,0.15,0.16},{0.20,0.67,0.16,0.16},{0.40,0.67,0.20,0.16},{0.64,0.67,0.16,0.16},{0.84,0.67,0.15,0.16},
            {0.01,0.87,0.15,0.12},{0.20,0.87,0.16,0.12},{0.40,0.87,0.20,0.12},{0.64,0.87,0.16,0.12},{0.84,0.87,0.15,0.12},
        };
        String[][] bData={
            {"PARK","City Park","#14532d"},{"OFFICE","Business Dist.","#1e3a5f"},{"CIVIC","City Hall","#312e81"},{"HOTEL","Grand Hotel","#292524"},{"AIRPORT","SmartGrid Airport","#1c3557"},
            {"MALL","Shopping Mall","#1e3a5f"},{"MARKET","Street Market","#431407"},{"RES.","Residential","#14532d"},{"SHOP","Retail District","#1e3a5f"},{"MUSEUM","City Museum","#312e81"},
            {"DEPOT","Transit Depot","#292524"},{"SCHOOL","City Academy","#1e3a5f"},{"CLINIC","Medical Centre","#450a0a"},{"BANK","Finance Dist.","#14532d"},{"TECH","Tech Campus","#1e3a5f"},
            {"STADIUM","City Stadium","#312e81"},{"FACTORY","Industrial Zone","#292524"},{"LIBRARY","Public Library","#1e3a5f"},{"PLAZA","Central Plaza","#14532d"},{"COURT","Sports Arena","#312e81"},
            {"ZOO","City Zoo","#14532d"},{"TOWER","Comm. Tower","#292524"},{"HARBOUR","SmartGrid Port","#1c3557"},{"GARDEN","Botanical Garden","#14532d"},{"UNIV","University","#1e3a5f"},
        };
        double fSize=Math.max(7,sw*0.011);
        for (int i=0; i<blocks.length; i++) {
            double bx=blocks[i][0]*sw,by=blocks[i][1]*sh,bw=blocks[i][2]*sw,bh=blocks[i][3]*sh;
            String accent=bData[i][2];
            gc.setFill(Color.web("#0a1020")); gc.fillRoundRect(bx,by,bw,bh,8,8);
            gc.setFill(Color.web(accent,0.28)); gc.fillRoundRect(bx,by,bw,bh,8,8);
            gc.setStroke(Color.web(accent,0.55)); gc.setLineWidth(0.7); gc.strokeRoundRect(bx,by,bw,bh,8,8);
            gc.setFill(Color.web(accent,0.75)); gc.fillRoundRect(bx+1,by+1,bw-2,3,2,2);
            if (zoomLevel>=1.4) {
                gc.setFont(Font.font("Consolas",FontWeight.BOLD,Math.max(8,sw*0.011))); gc.setFill(Color.web("#c0c9d8",0.95)); gc.fillText(bData[i][0],bx+5,by+16);
                gc.setFont(Font.font("Consolas",FontWeight.NORMAL,Math.max(6,sw*0.008))); gc.setFill(Color.web("#4b5563",0.90)); gc.fillText(bData[i][1],bx+5,by+28);
            } else {
                gc.setFont(Font.font("Consolas",FontWeight.NORMAL,fSize)); gc.setFill(Color.web("#1e4976",0.95)); gc.fillText(bData[i][0],bx+5,by+bh/2+4);
            }
        }

        if (!lastReadings.isEmpty()) drawVehicles(gc,sw,sh);

        // Nodes
        for (Map.Entry<String,double[]> entry:nodeRegistry.entrySet()) {
            String id=entry.getKey();
            double nx=entry.getValue()[0]*sw, ny=entry.getValue()[1]*sh;
            SensorReading r=lastReadings.stream().filter(x->x.getNodeId().equals(id)).findFirst().orElse(null);
            EmbeddedNode enDraw=nodeManager.getNode(id);
            boolean isEmergency=enDraw!=null&&enDraw.isEmergencyOverride();
            Color lc=Color.web("#374151");
            if (isEmergency) {
                lc=Color.web("#22c55e");
            } else if (r!=null) {
                lc=switch(r.getLightState()){case GREEN->Color.web("#22c55e");case AMBER->Color.web("#f59e0b");case RED->Color.web("#ef4444");};
            }
            boolean isCrit=r!=null&&r.getDensityLevel()==DensityLevel.CRITICAL;
            boolean isPredCrit=predictor.isPredictedCritical(id);
            if (isCrit||isPredCrit) {
                double pulse=0.35+0.35*Math.sin(animTick*3);
                gc.setStroke(isPredCrit?Color.web("#f59e0b",pulse):Color.web("#ef4444",pulse));
                gc.setLineWidth(1.5); gc.setLineDashes(4,3); gc.strokeOval(nx-26,ny-26,52,52); gc.setLineDashes(0);
            }
            gc.setFill(lc.deriveColor(0,1,0.2,0.12)); gc.fillOval(nx-18,ny-18,36,36);
            gc.setFill(Color.web("#0a1428")); gc.fillOval(nx-13,ny-13,26,26);
            gc.setStroke(lc); gc.setLineWidth(2.0); gc.strokeOval(nx-13,ny-13,26,26);
            gc.setFill(lc); gc.fillOval(nx-5,ny-5,10,10);
            gc.setFont(Font.font("Consolas",FontWeight.BOLD,Math.max(9,sw*0.013))); gc.setFill(lc); gc.fillText(labelFor(id),nx+16,ny+5);
            String dn=shortName(id); if(dn.length()>13)dn=dn.substring(0,13);
            gc.setFont(Font.font("Consolas",FontWeight.NORMAL,Math.max(7,sw*0.010))); gc.setFill(Color.web("#4b5563")); gc.fillText(dn,nx-22,ny-18);
            if (r!=null) {
                gc.setFill(Color.web("#0a1428",0.90)); gc.fillRoundRect(nx-22,ny+16,44,15,4,4);
                gc.setFill(lc); gc.setFont(Font.font("Consolas",FontWeight.NORMAL,Math.max(7,sw*0.010))); gc.fillText(r.getVehiclesPerMin()+"/m",nx-18,ny+27);
            } else {
                gc.setFill(Color.web("#1e2d4a",0.90)); gc.fillRoundRect(nx-16,ny+16,32,14,4,4);
                gc.setFill(Color.web("#60a5fa")); gc.setFont(Font.font("Consolas",FontWeight.NORMAL,Math.max(7,sw*0.010))); gc.fillText("NEW",nx-12,ny+27);
            }
        }
        drawLegend(gc,sw,sh);
        gc.restore();
    }

    private void drawHeatmap(GraphicsContext gc, double w, double h) {
        for (Map.Entry<String,double[]> entry:nodeRegistry.entrySet()) {
            String id=entry.getKey();
            SensorReading r=lastReadings.stream().filter(x->x.getNodeId().equals(id)).findFirst().orElse(null);
            if (r==null) continue;
            double nx=entry.getValue()[0]*w, ny=entry.getValue()[1]*h;
            double intensity=Math.min(1.0,r.getVehiclesPerMin()/110.0);
            String hexColor=intensity>0.85?"#ef4444":intensity>0.55?"#f59e0b":"#3b82f6";
            double radius=Math.max(60,w*0.10+intensity*w*0.12);
            for (int ring=8; ring>=0; ring--) {
                double r2=radius*(ring/8.0);
                double alpha=(0.18+intensity*0.25)*(1.0-(ring/8.0)*0.85);
                gc.setFill(Color.web(hexColor,alpha)); gc.fillOval(nx-r2,ny-r2,r2*2,r2*2);
            }
        }
    }

    private void drawLegend(GraphicsContext gc, double w, double h) {
        double lw=Math.max(115,w*0.14), lh=54, lx=w-lw-6, ly=h-lh-6;
        gc.setFill(Color.web("#060a12",0.92)); gc.fillRoundRect(lx-6,ly-6,lw+12,lh+8,6,6);
        gc.setFont(Font.font("Consolas",FontWeight.NORMAL,Math.max(8,w*0.011)));
        Color[] lc={Color.web("#22c55e"),Color.web("#f59e0b"),Color.web("#ef4444")};
        String[] ll={"GREEN — clear","AMBER — caution","RED — stop"};
        for (int i=0;i<3;i++){gc.setFill(lc[i]);gc.fillOval(lx,ly+i*18,9,9);gc.setFill(lc[i]);gc.fillText(ll[i],lx+16,ly+i*18+9);}
    }

    private void drawOfflineMap() {
        if (cityCanvas==null) return;
        double w=cityCanvas.getWidth(),h=cityCanvas.getHeight(); if(w<10||h<10)return;
        GraphicsContext gc=cityCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#060a12")); gc.fillRect(0,0,w,h);
        gc.setFill(Color.web("#1a3a5c")); gc.setFont(Font.font("Consolas",FontWeight.NORMAL,14));
        gc.fillText("Connect to database to activate city grid.",20,h/2);
    }

    // ═══════════════════════════════════════════════════════
    //  VEHICLE SIMULATION
    // ═══════════════════════════════════════════════════════
    private void initVehicles() {
        vehicles.clear();
        for (int road=0;road<ROAD_DEFS.length;road++) {
            roadVehicleCount[road]=3;
            for (int v=0;v<3;v++){double phase=(v*0.33+0.05)%1.0,baseSpd=0.0017+vehicleRng.nextDouble()*0.0005;vehicles.add(new double[]{road,v%2,phase,baseSpd,baseSpd});}
        }
        vehiclesInited=true; lastNodeCount=nodeRegistry.size();
    }

    private void syncVehicleCount(int road, int target) {
        List<double[]> onRoad=new ArrayList<>(); for(double[]v:vehicles)if((int)v[0]==road)onRoad.add(v);
        int current=onRoad.size();
        if (current<target) { for(int i=current;i<target;i++){double phase=((double)i/target+vehicleRng.nextDouble()*0.05)%1.0,baseSpd=0.0015+vehicleRng.nextDouble()*0.0006;vehicles.add(new double[]{road,i%2,phase,baseSpd,baseSpd});} }
        else if (current>target) { int toRemove=current-target; Iterator<double[]>it=vehicles.iterator(); while(it.hasNext()&&toRemove>0){double[]v=it.next();if((int)v[0]==road){it.remove();toRemove--;}} }
        roadVehicleCount[road]=target;
    }

    private void drawVehicles(GraphicsContext gc, double w, double h) {
        if (!vehiclesInited) initVehicles();
        if (nodeRegistry.size()!=lastNodeCount) lastNodeCount=nodeRegistry.size();
        double sz=Math.max(3.5,w*0.0055);
        List<List<double[]>> roadIntersections=new ArrayList<>(); for(int i=0;i<ROAD_DEFS.length;i++)roadIntersections.add(new ArrayList<>());
        for (Map.Entry<String,double[]>entry:nodeRegistry.entrySet()) {
            String nid=entry.getKey(); double fx=entry.getValue()[0],fy=entry.getValue()[1];
            SensorReading reading=lastReadings.stream().filter(r->r.getNodeId().equals(nid)).findFirst().orElse(null);
            int hLight,vLight;
            EmbeddedNode en=nodeManager.getNode(nid);
            if (en!=null&&en.isEmergencyOverride()){hLight=0;vLight=0;}
            else if(reading==null){hLight=0;vLight=2;}
            else switch(reading.getLightState()){case GREEN->{hLight=0;vLight=2;}case AMBER->{hLight=1;vLight=2;}case RED->{hLight=2;vLight=0;}default->{hLight=0;vLight=2;}}
            double density=reading==null?30:reading.getVehiclesPerMin();
            for (int ri=0;ri<ROAD_DEFS.length;ri++){double[]rd=ROAD_DEFS[ri];boolean horiz=rd[4]>0.5;if(horiz&&Math.abs(fy-rd[1])<0.025)roadIntersections.get(ri).add(new double[]{fx,hLight,density});else if(!horiz&&Math.abs(fx-rd[0])<0.025)roadIntersections.get(ri).add(new double[]{fy,vLight,density});}
        }
        for (int ri=0;ri<ROAD_DEFS.length;ri++){if(roadIntersections.get(ri).isEmpty())continue;double avgD=roadIntersections.get(ri).stream().mapToDouble(x->x[2]).average().orElse(30);int tgt=Math.max(1,Math.min(12,(int)Math.round(1+(avgD/150.0)*11)));if(roadVehicleCount[ri]!=tgt)syncVehicleCount(ri,tgt);}
        for (double[]v:vehicles){
            int ri=(int)v[0];if(ri>=ROAD_DEFS.length)continue;
            double[]rd=ROAD_DEFS[ri];boolean horiz=rd[4]>0.5,lane=v[1]>0.5;
            double phase=v[2],baseSpd=v[4],targetSpd=baseSpd;
            for(double[]inter:roadIntersections.get(ri)){double interPhase=inter[0];int light=(int)inter[1];double dist;if(!lane){dist=interPhase-phase;if(dist<0)dist+=1.0;}else{dist=phase-interPhase;if(dist<0)dist+=1.0;}double spd;if(light==2){if(dist<=0.025)spd=0.0;else if(dist<0.085){double t=(dist-0.025)/(0.085-0.025);spd=baseSpd*t;}else spd=baseSpd;}else if(light==1){spd=dist<0.085?baseSpd*0.45:baseSpd;}else spd=baseSpd;targetSpd=Math.min(targetSpd,spd);}
            double minGap=0.032,carAhead=1.0;for(double[]o:vehicles){if(o==v||(int)o[0]!=ri||(o[1]>0.5)!=lane)continue;double gap;if(!lane){gap=o[2]-v[2];if(gap<0)gap+=1.0;}else{gap=v[2]-o[2];if(gap<0)gap+=1.0;}if(gap<carAhead)carAhead=gap;}
            if(carAhead<minGap&&carAhead<0.99)targetSpd=Math.min(targetSpd,targetSpd*(carAhead/minGap));
            if(targetSpd<0.00002){v[3]=v[3]*0.72;if(v[3]<0.000005)v[3]=0.0;}else v[3]=v[3]*0.87+targetSpd*0.13;
            if(v[3]>0.0){if(!lane){v[2]=(v[2]+v[3])%1.0;}else{v[2]-=v[3];if(v[2]<0)v[2]+=1.0;}}
            double lo=!lane?-5.0:5.0,px,py;
            if(horiz){px=(rd[0]+v[2]*(rd[2]-rd[0]))*w;py=rd[1]*h+lo;}else{px=rd[0]*w+lo;py=(rd[1]+v[2]*(rd[3]-rd[1]))*h;}
            if(px<0||px>w||py<0||py>h)continue;
            double ratio=v[3]/Math.max(0.0001,baseSpd);
            Color cc=ratio<0.04?Color.web("#ef4444",0.95):ratio<0.45?Color.web("#f59e0b",0.85):Color.web(ri<4?"#60a5fa":"#818cf8",0.80);
            gc.setFill(cc);
            if(horiz)gc.fillRoundRect(px-sz,py-sz*0.45,sz*2,sz*0.9,2,2);else gc.fillRoundRect(px-sz*0.45,py-sz,sz*0.9,sz*2,2,2);
            if(ratio>0.35){gc.setFill(Color.web("#ffffff",0.35));double hx=horiz?(!lane?px+sz-1:px-sz+1):px-1.5,hy=horiz?py-1.5:(!lane?py+sz-1:py-sz+1);gc.fillOval(hx,hy,2.5,2.5);}
        }
    }

    // ═══════════════════════════════════════════════════════
    //  NODE CARDS
    // ═══════════════════════════════════════════════════════
    private void updateNodeCards(List<SensorReading> readings) {
        nodeCardsContainer.getChildren().clear();
        Set<String> withReadings=new HashSet<>();
        for(SensorReading r:readings){nodeCardsContainer.getChildren().add(buildNodeCard(r));withReadings.add(r.getNodeId());}
        for(String id:nodeRegistry.keySet())if(!withReadings.contains(id))nodeCardsContainer.getChildren().add(buildWaitingCard(id,nodeNameCache.getOrDefault(id,shortName(id))));
    }

    private VBox buildNodeCard(SensorReading r) {
        VBox card=new VBox(5); card.getStyleClass().add("node-card");
        Color dc=switch(r.getLightState()){case GREEN->Color.web("#34d399");case AMBER->Color.web("#fbbf24");case RED->Color.web("#ef4444");};
        Canvas dot=new Canvas(10,10); dot.getGraphicsContext2D().setFill(dc); dot.getGraphicsContext2D().fillOval(0,0,10,10);
        Label nameLabel=new Label(r.getNodeId()+" — "+shortName(r.getNodeId())); nameLabel.getStyleClass().add("node-card-name");
        HBox header=new HBox(8,nameLabel); HBox.setHgrow(nameLabel,Priority.ALWAYS); header.setAlignment(Pos.CENTER_LEFT); header.getChildren().add(dot);
        EmbeddedNode node=nodeManager.getNode(r.getNodeId());
        int crit=node!=null?node.getCriticalThreshold():110, high=node!=null?node.getHighThreshold():80;
        double pct=Math.min(1.0,(double)r.getVehiclesPerMin()/crit);
        Color bc=pct>0.9?Color.web("#ef4444"):pct>0.6?Color.web("#f59e0b"):Color.web("#34d399");
        Canvas bar=new Canvas(190,5); GraphicsContext bg=bar.getGraphicsContext2D();
        bg.setFill(Color.web("#162034")); bg.fillRoundRect(0,0,190,5,3,3); bg.setFill(bc); bg.fillRoundRect(0,0,190*pct,5,3,3);
        Label v=new Label("Vehicles/min: "+r.getVehiclesPerMin()+"   (H:"+high+" C:"+crit+")"); v.getStyleClass().add("node-stat");
        Label l=new Label("Light: "+r.getLightState()+"   Density: "+r.getDensityLevel()+(r.getDensityLevel()==DensityLevel.CRITICAL?" !":"")); l.getStyleClass().add(r.getDensityLevel()==DensityLevel.CRITICAL?"node-stat-danger":"node-stat");
        String pred=predictor.getPredictionText(r.getNodeId());
        Label predL=new Label("AI: "+pred); predL.setStyle(predictor.isPredictedCritical(r.getNodeId())?"-fx-text-fill:#f87171;-fx-font-size:9px;":"-fx-text-fill:#374151;-fx-font-size:9px;");
        card.getChildren().addAll(header,bar,v,l,predL); return card;
    }

    private VBox buildWaitingCard(String nodeId, String nodeName) {
        VBox card=new VBox(5); card.getStyleClass().add("node-card");
        Canvas dot=new Canvas(10,10); dot.getGraphicsContext2D().setFill(Color.web("#374151")); dot.getGraphicsContext2D().fillOval(0,0,10,10);
        Label nameLabel=new Label(nodeId+" — "+nodeName); nameLabel.getStyleClass().add("node-card-name");
        HBox header=new HBox(8,nameLabel); HBox.setHgrow(nameLabel,Priority.ALWAYS); header.setAlignment(Pos.CENTER_LEFT); header.getChildren().add(dot);
        EmbeddedNode n=nodeManager.getNode(nodeId); boolean alive=n!=null&&n.isRunning();
        Label waiting=new Label("Waiting for first reading..."); waiting.getStyleClass().add("node-stat");
        Label status=new Label(alive?"● Thread running":"○ Thread stopped"); status.setStyle(alive?"-fx-text-fill:#34d399;-fx-font-size:10px;":"-fx-text-fill:#f87171;-fx-font-size:10px;");
        card.getChildren().addAll(header,waiting,status); return card;
    }

    private VBox buildIncidentCard(IncidentLog inc) {
        VBox card=new VBox(3); card.getStyleClass().add("incident-mini-card");
        String s=switch(inc.getSeverity()){case CRITICAL->"incident-badge-critical";case WARNING->"incident-badge-warning";default->"incident-badge-info";};
        Label type=new Label(inc.getIncidentType().name()); type.getStyleClass().addAll("incident-badge",s);
        Label loc=new Label(inc.getNodeId()+" — "+locationFor(inc.getNodeId())); loc.getStyleClass().add("incident-loc");
        Label time=new Label(inc.getCreatedAt()!=null?inc.getCreatedAt().format(LOG_FMT):""); time.getStyleClass().add("incident-time");
        card.getChildren().addAll(type,loc,time); return card;
    }

    // ═══════════════════════════════════════════════════════
    //  STATS + INCIDENTS
    // ═══════════════════════════════════════════════════════
    private void updateStats(List<SensorReading> readings,List<IncidentLog> incidents,int totalVeh,int totalInc,double avgWait) {
        long runningCount=nodeRegistry.keySet().stream().filter(id->{EmbeddedNode n=nodeManager.getNode(id);return n!=null&&n.isRunning();}).count();
        statActiveNodes.setText(runningCount+" / "+nodeRegistry.size());
        statTotalVehicles.setText(totalVeh+"  (avg wait "+avgWait+"s)");
        statAlerts.setText(String.valueOf(incidents.stream().filter(i->i.getSeverity()==Severity.CRITICAL).count()));
        statLogRows.setText(String.format("%,d",totalInc));
        boolean hasCrit=readings.stream().anyMatch(r->r.getDensityLevel()==DensityLevel.CRITICAL);
        if(hasCrit){SensorReading cr=readings.stream().filter(r->r.getDensityLevel()==DensityLevel.CRITICAL).findFirst().get();alertLabel.setText("  Congestion: "+shortName(cr.getNodeId()));alertLabel.getStyleClass().setAll("alert-pill");}
        else{alertLabel.setText("");alertLabel.getStyleClass().setAll("alert-hidden");}
    }

    private void refreshIncidentPanel(List<IncidentLog> incidents) {
        incidentsContainer.getChildren().clear();
        incidents.stream().filter(inc->searchFilter.isEmpty()||inc.getNodeId().toLowerCase().contains(searchFilter)||inc.getIncidentType().name().toLowerCase().contains(searchFilter)||inc.getDescription().toLowerCase().contains(searchFilter)).forEach(inc->incidentsContainer.getChildren().add(buildIncidentCard(inc)));
    }

    private void drawMiniChart(List<SensorReading> readings) {
        GraphicsContext gc=chartCanvas.getGraphicsContext2D(); double w=chartCanvas.getWidth(),h=chartCanvas.getHeight();
        gc.clearRect(0,0,w,h); gc.setFill(Color.web("#060a12")); gc.fillRoundRect(0,0,w,h,6,6);
        if(readings.isEmpty())return;
        int max=readings.stream().mapToInt(SensorReading::getVehiclesPerMin).max().orElse(1),n=readings.size(); double barW=(w-8)/n-3;
        for(int i=n-1;i>=0;i--){SensorReading r=readings.get(i);double barH=((double)r.getVehiclesPerMin()/max)*(h-10),x=4+(n-1-i)*(barW+3),y=h-5-barH;gc.setFill(r.getVehiclesPerMin()==max?Color.web("#f59e0b"):Color.web("#1d4ed8",0.8));gc.fillRoundRect(x,y,barW,barH,2,2);}
    }

    private void updatePredictionLabel() {
        if (predictionLabel==null) return;
        long critCount=nodeRegistry.keySet().stream().filter(predictor::isPredictedCritical).count();
        if(critCount>0){predictionLabel.setText("AI: "+critCount+" node(s) predicted critical");predictionLabel.getStyleClass().setAll("prediction-critical");}
        else{predictionLabel.setText("AI: All nodes nominal");predictionLabel.getStyleClass().setAll("prediction-label");}
    }

    private void startAlertBlink() {
        if(alertBlinking)return; alertBlinking=true; if(alertBlink!=null)alertBlink.stop();
        alertBlink=new Timeline(new KeyFrame(Duration.ZERO,e->alertLabel.setOpacity(1.0)),new KeyFrame(Duration.millis(500),e->alertLabel.setOpacity(0.2)),new KeyFrame(Duration.millis(1000),e->{alertLabel.setOpacity(1.0);alertBlinking=false;}));
        alertBlink.setCycleCount(6); alertBlink.play();
    }

    private void startClock() {
        clockTimer=new AnimationTimer(){long last=0;@Override public void handle(long now){if(now-last>1_000_000_000L){clockLabel.setText(LocalDateTime.now().format(TIME_FMT));last=now;}}};
        clockTimer.start();
    }

    // ═══════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════
    private void disableNavButtons(boolean d){btnFirst.setDisable(d);btnMinus3.setDisable(d);btnPrev.setDisable(d);btnNext.setDisable(d);btnPlus3.setDisable(d);btnLast.setDisable(d);}
    private void enableActionButtons(boolean e){if(exportBtn!=null)exportBtn.setDisable(!e);if(addNodeBtn!=null)addNodeBtn.setDisable(!e);if(triggerIncidentBtn!=null)triggerIncidentBtn.setDisable(!e);if(emergencyBtn!=null)emergencyBtn.setDisable(!e);if(heatmapBtn!=null)heatmapBtn.setDisable(!e);if(analyticsBtn!=null)analyticsBtn.setDisable(!e);}
    private void clearStats(){statActiveNodes.setText("—");statTotalVehicles.setText("—");statAlerts.setText("—");statLogRows.setText("—");navCurrentLabel.setText("—");navTotalLabel.setText("—");incidentDescLabel.setText("Connect to database to load incident log.");incidentTypeLabel.setText("—");incidentNodeLabel.setText("");incidentTimeLabel.setText("");alertLabel.setText("");alertLabel.getStyleClass().setAll("alert-hidden");if(predictionLabel!=null)predictionLabel.setText("");}
    private Dialog<ButtonType> styledDialog(String title,String header){Dialog<ButtonType>d=new Dialog<>();d.setTitle(title);d.setHeaderText(header);d.getDialogPane().getStylesheets().add(getClass().getResource("/com/smartgrid/css/darktheme.css").toExternalForm());return d;}
    private GridPane dialogGrid(){GridPane g=new GridPane();g.setHgap(14);g.setVgap(12);g.setPadding(new Insets(20));ColumnConstraints c1=new ColumnConstraints(165),c2=new ColumnConstraints(165),c3=new ColumnConstraints(110);g.getColumnConstraints().addAll(c1,c2,c3);return g;}
    private Label dialogLabel(String text){Label l=new Label(text);l.setStyle("-fx-text-fill:#6b7280;-fx-font-size:12px;");return l;}
    private void showAlert(String title,String msg,Alert.AlertType type){Alert a=new Alert(type);a.setTitle(title);a.setHeaderText(null);a.setContentText(msg);a.getDialogPane().getStylesheets().add(getClass().getResource("/com/smartgrid/css/darktheme.css").toExternalForm());a.showAndWait();}
    private String labelFor(String id){if(id.startsWith("NODE_")&&id.length()>5)return id.substring(5,Math.min(id.length(),7));return id.length()>2?id.substring(0,2):id;}
    private String shortName(String id){String c=nodeNameCache.get(id);if(c!=null)return c;return switch(id){case "NODE_A"->"Central Junction";case "NODE_B"->"North Boulevard";case "NODE_C"->"East Avenue";case "NODE_D"->"West Crossing";case "NODE_E"->"South District";default->id.replace("NODE_","").replace("_"," ");};}
    private String locationFor(String id){String c=nodeLocationCache.get(id);if(c!=null)return c;return switch(id){case "NODE_A"->"Central Ave & Main St";case "NODE_B"->"North Blvd & Park Rd";case "NODE_C"->"East Ave & Commerce St";case "NODE_D"->"West Cross & River Rd";case "NODE_E"->"South St & Market Ave";default->id+" Intersection";};}
}
