module com.smartgrid {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires java.sql;

    opens com.smartgrid to javafx.fxml;
    opens com.smartgrid.controller to javafx.fxml;
    opens com.smartgrid.model to javafx.base;

    exports com.smartgrid;
    exports com.smartgrid.controller;
    exports com.smartgrid.model;
    exports com.smartgrid.dao;
    exports com.smartgrid.embedded;
    exports com.smartgrid.util;
}
