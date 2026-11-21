package com.aipilot.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** Undecorated themed stage shell (Platforma151-style). */
public final class ThemedStage {
    private ThemedStage() {
    }

    public static Stage open(Window owner, String title, Node body, double width, double height) {
        return open(owner, title, body, width, height, Modality.NONE);
    }

    public static Stage open(Window owner, String title, Node body, double width, double height,
                             Modality modality) {
        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(modality);
        stage.initStyle(StageStyle.TRANSPARENT);

        HBox bar = new HBox();
        bar.getStyleClass().addAll("title-bar", "dialog-title-bar");
        bar.setPadding(new Insets(0, 0, 0, 12));
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label brand = new Label(title);
        brand.getStyleClass().add("title-bar-brand");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox chrome = new HBox();
        chrome.setAlignment(javafx.geometry.Pos.TOP_RIGHT);
        ChromeButton close = new ChromeButton(ChromeButton.Kind.CLOSE);
        close.setOnMouseClicked(e -> stage.close());
        chrome.getChildren().add(close);
        chrome.setTranslateX(WindowClip.BORDER);
        chrome.setTranslateY(-WindowClip.BORDER);
        bar.getChildren().addAll(brand, spacer, chrome);
        WindowDragHelper.install(stage, bar);

        VBox shell = new VBox(bar, body);
        shell.getStyleClass().add("app-shell");
        VBox.setVgrow(body, Priority.ALWAYS);

        Scene scene = new Scene(shell, width, height);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        var css = ThemedStage.class.getResource("/css/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        WindowClip.install(shell);
        EdgeResizeHelper.install(stage, shell);
        return stage;
    }
}
