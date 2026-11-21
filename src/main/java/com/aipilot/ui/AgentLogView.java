package com.aipilot.ui;

import com.aipilot.core.AgentLoop;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compact activity cards plus a verbose console. */
public final class AgentLogView {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_CARDS = 80;
    private static final int MAX_BUFFER = 200;

    private final VBox list = new VBox(8);
    private final ScrollPane scroll = new ScrollPane();
    private final TextArea console = new TextArea();
    private final StackPane root = new StackPane();
    private final ConcurrentLinkedQueue<AgentLoop.Event> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final StringBuilder consoleText = new StringBuilder();
    private volatile boolean uiActive = true;
    private boolean verbose;

    public AgentLogView() {
        list.getStyleClass().add("agent-log");
        list.setFillWidth(true);
        scroll.setContent(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("agent-log-scroll");
        scroll.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        console.setEditable(false);
        console.setWrapText(true);
        console.getStyleClass().add("console-area");
        console.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        console.setVisible(false);
        console.setManaged(false);

        root.getChildren().addAll(scroll, console);
        root.getStyleClass().add("agent-log-host");
        StackPane.setAlignment(scroll, Pos.TOP_LEFT);
        StackPane.setAlignment(console, Pos.TOP_LEFT);
    }

    public Node node() {
        return root;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
        scroll.setVisible(!verbose);
        scroll.setManaged(!verbose);
        console.setVisible(verbose);
        console.setManaged(verbose);
        if (verbose) {
            console.setText(consoleText.toString());
            console.positionCaret(consoleText.length());
        }
    }

    public void setUiActive(boolean active) {
        uiActive = active;
        if (active) {
            scheduleFlush();
        }
    }

    public void add(AgentLoop.Event event) {
        if (event == null) {
            return;
        }
        inbound.add(event);
        while (inbound.size() > MAX_BUFFER) {
            inbound.poll();
        }
        if (uiActive) {
            scheduleFlush();
        }
    }

    public void addDetail(String title, String detail) {
        add(new AgentLoop.Event(AgentLoop.Kind.DETAIL, title, detail, null));
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            flushScheduled.set(false);
            List<AgentLoop.Event> batch = new ArrayList<>();
            AgentLoop.Event next;
            while ((next = inbound.poll()) != null) {
                batch.add(next);
            }
            for (AgentLoop.Event event : batch) {
                addNow(event);
            }
        });
    }

    private void addNow(AgentLoop.Event event) {
        String line = LocalTime.now().format(CLOCK) + "  "
                + (event.title() == null ? "" : event.title())
                + (event.detail() == null || event.detail().isBlank() ? "" : " — " + event.detail());
        if (event.relPath() != null && !event.relPath().isBlank()) {
            line += " [" + event.relPath() + "]";
        }
        if (event.linesAdded() > 0 || event.linesRemoved() > 0) {
            line += " +" + event.linesAdded() + "/-" + event.linesRemoved();
        }
        if (consoleText.length() > 0) {
            consoleText.append('\n');
        }
        consoleText.append(line);
        if (consoleText.length() > 80_000) {
            consoleText.delete(0, consoleText.length() - 60_000);
        }
        if (verbose) {
            console.setText(consoleText.toString());
            console.positionCaret(consoleText.length());
        }
        if (!showCompact(event)) {
            return;
        }
        VBox card = new VBox(4);
        card.getStyleClass().addAll("chat-card", cardClass(event.kind()));
        card.setMaxWidth(Double.MAX_VALUE);
        HBox meta = new HBox(8);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label pill = new Label(pillText(event.kind()));
        pill.getStyleClass().addAll("status-pill", pillClass(event.kind()));
        Label time = new Label(LocalTime.now().format(CLOCK));
        time.getStyleClass().add("chat-card-meta");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        meta.getChildren().addAll(pill, time, spacer);
        if (event.hasFile()) {
            IconButton folder = IconButton.folder();
            folder.setMinSize(28, 28);
            folder.setPrefSize(28, 28);
            folder.setMaxSize(28, 28);
            folder.setOnAction(e -> openFolder(event.localPath()));
            meta.getChildren().add(folder);
        }
        if (event.href() != null && !event.href().isBlank()) {
            Hyperlink link = new Hyperlink("↗");
            link.getStyleClass().add("agent-link");
            link.setOnAction(e -> open(event.href()));
            meta.getChildren().add(link);
        }
        Label title = new Label(event.title() == null ? "" : event.title());
        title.getStyleClass().add("chat-card-meta");
        title.setWrapText(true);

        if (event.relPath() != null && !event.relPath().isBlank()) {
            Label path = new Label(event.relPath());
            path.getStyleClass().add("task-file-path");
            path.setWrapText(true);
            card.getChildren().addAll(meta, title, path);
        } else {
            card.getChildren().addAll(meta, title);
        }

        if (event.linesAdded() > 0 || event.linesRemoved() > 0) {
            HBox diff = new HBox(10);
            diff.setAlignment(Pos.CENTER_LEFT);
            if (event.linesAdded() > 0) {
                Label plus = new Label("+" + event.linesAdded());
                plus.getStyleClass().add("task-diff-add");
                diff.getChildren().add(plus);
            }
            if (event.linesRemoved() > 0) {
                Label minus = new Label("-" + event.linesRemoved());
                minus.getStyleClass().add("task-diff-del");
                diff.getChildren().add(minus);
            }
            card.getChildren().add(diff);
        }

        if (event.detail() != null && !event.detail().isBlank()) {
            Label body = new Label(event.detail());
            body.getStyleClass().add("chat-card-body");
            body.setWrapText(true);
            card.getChildren().add(body);
        }

        list.getChildren().add(0, card);
        while (list.getChildren().size() > MAX_CARDS) {
            list.getChildren().remove(list.getChildren().size() - 1);
        }
    }

    private static boolean showCompact(AgentLoop.Event event) {
        if (event.kind() != AgentLoop.Kind.QUEUE
                && event.kind() != AgentLoop.Kind.RUN
                && event.kind() != AgentLoop.Kind.DONE
                && event.kind() != AgentLoop.Kind.ERROR) {
            return false;
        }
        String title = event.title() == null ? "" : event.title();
        return !"Настройки".equals(title) && !"Сеть".equals(title) && !"Вход".equals(title) && !"Выход".equals(title);
    }

    private static String cardClass(AgentLoop.Kind kind) {
        return switch (kind) {
            case ERROR -> "chat-card-error";
            case RUN, DONE -> "chat-card-request";
            case CONNECT, QUEUE, START, DETAIL -> "chat-card-info";
        };
    }

    private static String pillClass(AgentLoop.Kind kind) {
        return switch (kind) {
            case ERROR -> "status-pill";
            case RUN, QUEUE -> "status-pill-busy";
            case CONNECT, START, DONE, DETAIL -> "status-pill-online";
        };
    }

    private static String pillText(AgentLoop.Kind kind) {
        return switch (kind) {
            case START -> "запуск";
            case CONNECT -> "коннект";
            case QUEUE -> "очередь";
            case RUN -> "выполнение";
            case DONE -> "готово";
            case DETAIL -> "лог";
            case ERROR -> "ошибка";
        };
    }

    private static void open(String href) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(href));
            }
        } catch (Exception ignored) {
        }
    }

    private static void openFolder(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(localPath).toAbsolutePath().normalize();
            Path dir = Files.isDirectory(path) ? path : path.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            File folder = dir.toFile();
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) && Files.isRegularFile(path)) {
                    desktop.browseFileDirectory(path.toFile());
                } else {
                    desktop.open(folder);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
