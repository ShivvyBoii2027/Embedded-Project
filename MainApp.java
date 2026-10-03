package com.smartgrid;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/com/smartgrid/fxml/MainView.fxml")
        );
        // Get screen size and use 90% of it
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = Math.min(1280, screen.getWidth()  * 0.95);
        double h = Math.min(780,  screen.getHeight() * 0.92);
        Scene scene = new Scene(loader.load(), w, h);
        scene.getStylesheets().add(
            getClass().getResource("/com/smartgrid/css/darktheme.css").toExternalForm()
        );

        primaryStage.setTitle("SmartGrid City — Traffic Command Center");
        primaryStage.setScene(scene);
        // Set custom window icon
        try {
            primaryStage.getIcons().add(
                new Image(getClass().getResourceAsStream("/com/smartgrid/icon.png"))
            );
        } catch (Exception ignored) {}
        primaryStage.setMinWidth(860);
        primaryStage.setMinHeight(540);
        // Maximise on small screens automatically
        javafx.geometry.Rectangle2D scr = javafx.stage.Screen.getPrimary().getVisualBounds();
        if (scr.getWidth() < 1400 || scr.getHeight() < 850) {
            primaryStage.setMaximized(true);
        }
        primaryStage.show();
    }

    @Override
    public void stop() {
        // Gracefully shut down all embedded node threads on close
        com.smartgrid.embedded.EmbeddedNodeManager.getInstance().stopAll();
        com.smartgrid.dao.DatabaseManager.getInstance().disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
