package com.mirto.launcher;

import com.mirto.launcher.interfaces.Progress;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import javafx.application.Application;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.TreeView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.SelectionModel;
import javafx.scene.control.TextField;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TreeCell;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Alert;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.paint.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Optional;

public class BridgeLinkLauncher extends Application implements Progress {
    private static final boolean DEVELOP = false;
    // Keep in sync with the <version> in pom.xml; when running from a packaged
    // jar the manifest value wins (see resolveVersion()).
    // Package-private so UpdateChecker can use it as the single source of truth
    // for the running version when the jar manifest carries no Implementation-Version.
    static final String FALLBACK_VERSION = "1.8.1";
    private static final String VERSION = DEVELOP ? "Development " + FALLBACK_VERSION : resolveVersion();

    /**
     * Reads the implementation version from the jar manifest when available so
     * the title never drifts from the build version; falls back to the constant.
     */
    private static String resolveVersion() {
        try {
            String v = BridgeLinkLauncher.class.getPackage().getImplementationVersion();
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        } catch (Exception ignored) {
            // fall through to constant
        }
        return FALLBACK_VERSION;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BridgeLinkLauncher.class);
    private Image LAUNCHER_ICON;
    private Image BRIDGELINK_ICON;

    private final ObservableList<Connection> connectionsList = FXCollections.observableArrayList();

    private TreeView<Connection> connectionsTreeView;
    private SelectionModel<TreeItem<Connection>> treeSelectionModel;

    private TextField filterField;
    private TextField groupTextField;
    private TextField addressTextField;
    private TextField usernameTextField;
    private PasswordField passwordField;
    private Button launchButton;
    private ComboBox<BundledJava> bundledJavaCombo;
    private ComboBox<HeapMemory> heapSizeCombo;
    private TextField jvmOptionsTextField;
    private TextArea notesTextArea;
    private RadioButton bundledJavaRadio;
    private RadioButton customJavaRadio;
    private TextField customJavaTextField;
    private CheckBox showConsoleCheckBox;
    private CheckBox clearCacheCheckBox;
    private Button iconButton;
    private Text progressText;
    private ProgressBar progressBar;
    private ProgressIndicator progressIndicator;
    private Button cancelButton;
    private CheckBox closeWindowCheckBox;
    private TextField sshTunnelTextField;
    private Button sshTunnelTestButton;
    private volatile Process tunnelProcess; // running ssh -N process, if any
    private Button newButton;
    private Button saveButton;
    private Button duplicateButton;
    private Button deleteButton;
    private Button importButton;
    private Button exportButton;
    private Button revertButton;
    private CheckBox trustSelfSignedCheckBox;
    private Thread launchThread;
    private volatile DownloadJNLP currentDownload;
    private volatile boolean isLaunching = false;
    private Stage primaryStage;
    private final String[] startupArgs; // raw application arguments (data dir override)
    private String appDir;     // Application directory
    private File dataFolder;   // "data" folder within appDir
    private File cacheFolder;  // New "cache" folder within appDir
    private String tempSelectedIcon;  // Temporarily selected icon (before save)
    private ConnectionStore connectionStore; // Persistence layer (load/save/import/export)
    private ConnectionHealth connectionHealth; // background reachability probes
    private UpdateChecker updateChecker;       // new-release notification
    private Button healthRefreshButton;
    private Label updateStatusLabel;
    private Button openReleaseButton;
    private Button dismissUpdateButton;
    private String lastReleaseUrl;
    // Snapshot of the selected connection's form values, used to detect unsaved
    // changes and to implement Discard/Revert.
    private Map<String, Object> loadedSnapshot;

    /** No-arg constructor required by the JavaFX launcher (see {@link #main}). */
    public BridgeLinkLauncher() {
        this(null);
    }

    /**
     * @param args raw application arguments; when present, the first one overrides
     *             the application directory (same behaviour as before). Needed as
     *             an explicit field because {@code getParameters()} is not usable
     *             from the static shutdown hook registered in {@link #main}.
     */
    public BridgeLinkLauncher(String[] args) {
        this.startupArgs = args != null ? args : new String[0];
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        getAppHolder()[0] = this; // allow the shutdown hook to clean up the SSH tunnel
        LAUNCHER_ICON = new Image("/images/logo.png");
        BRIDGELINK_ICON = new Image("/images/BridgeLink.png");
        stage.setTitle("Mirto-Launcher (" + VERSION + ")");

        try {
            stage.getIcons().add(LAUNCHER_ICON);
        } catch (Exception e) {
            // Handle icon loading failure
        }

        initializeDirectories();
        connectionStore = new ConnectionStore(dataFolder, new File(appDir));

        // Background reachability probes: status dots in the connection tree
        connectionHealth = new ConnectionHealth();
        connectionHealth.setListener((conn, status, message) -> connectionsTreeView.refresh());
        connectionHealth.startMonitoring(new java.util.HashSet<>());
        // Probe connections as they are loaded/added (initial load included)
        connectionsList.addListener((javafx.collections.ListChangeListener<Connection>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Connection c : change.getAddedSubList()) {
                        connectionHealth.check(c);
                    }
                }
            }
        });

        VBox root = new VBox(15);
        root.setPadding(new Insets(15));

        // Connections Section with Buttons
        VBox connectionsSection = new VBox(20);
        connectionsSection.setPadding(new Insets(10));
        connectionsSection.setAlignment(Pos.TOP_CENTER);

        // Table buttons
        HBox tableButtons = new HBox(10);

        filterField = new TextField();
        filterField.setPromptText("Filter connections...");
        filterField.setPrefWidth(200);
        filterField.setMinWidth(200);
        filterField.setMaxWidth(200);
        filterField.textProperty().addListener((obs, oldVal, newVal) -> updateTreeViewWithFilter(newVal));
        // Ctrl+F (or Cmd+F on macOS) moves the focus to the filter field
        final TextField shortcutFilterField = filterField;
        shortcutFilterField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if ((event.isControlDown() || event.isMetaDown()) && event.getCode() == javafx.scene.input.KeyCode.F) {
                shortcutFilterField.requestFocus();
                shortcutFilterField.positionCaret(shortcutFilterField.getText().length());
                event.consume();
            }
        });

        newButton = new Button("New");
        newButton.setOnAction(e -> createNewConnection());
        saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveCurrentConnection());
        revertButton = new Button("Revert");
        revertButton.setDisable(true);
        revertButton.setTooltip(new Tooltip("Discard unsaved changes and restore the saved values"));
        revertButton.setOnAction(e -> revertChanges());
        duplicateButton = new Button("Duplicate");
        duplicateButton.setOnAction(e -> duplicateConnection());
        deleteButton = new Button("Delete");
        deleteButton.setDisable(true);
        deleteButton.setOnAction(e -> deleteCurrentConnection());
        healthRefreshButton = new Button("Refresh status");
        healthRefreshButton.setTooltip(new Tooltip("Check now whether every configured server is reachable"));
        healthRefreshButton.setOnAction(e -> {
            if (connectionHealth != null) {
                connectionHealth.checkAllNow();
            }
        });
        tableButtons.getChildren().addAll(filterField, newButton, saveButton, revertButton, duplicateButton, deleteButton, healthRefreshButton);
        tableButtons.setAlignment(Pos.CENTER_LEFT);

        // TreeView setup
        connectionsTreeView = new TreeView<>();
        connectionsTreeView.setEditable(true);
        // Preferred height so the "fit to content" window sizing below always
        // leaves a usable list of connections visible; Vgrow still expands it.
        connectionsTreeView.setPrefHeight(240);
        VBox.setVgrow(connectionsTreeView, Priority.ALWAYS);

        // Cell factory for editing names
        connectionsTreeView.setCellFactory(treeView -> {
            TreeCell<Connection> cell = new TextFieldTreeCell<Connection>(new StringConverter<Connection>() {
                @Override
                public String toString(Connection conn) {
                    if (conn == null) return "";
                    if (conn.getAddress() != null) { // Connection node
                        return conn.getName() != null ? conn.getName() : "";
                    } else { // Group node
                        return conn.getGroup() != null ? conn.getGroup() : "Ungrouped";
                    }
                }

                @Override
                public Connection fromString(String string) {
                    TreeItem<Connection> item = connectionsTreeView.getSelectionModel().getSelectedItem();
                    if (item != null && item.getValue() != null && item.getValue().getAddress() != null) {
                        Connection conn = item.getValue();
                        conn.setName(string);
                        return conn;
                    }
                    return null;
                }
            }) {
                @Override
                public void updateItem(Connection item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item != null && !empty && item.getIcon() != null && !item.getIcon().trim().isEmpty()) {
                        String iconName = item.getIcon();
                        Image iconImage = null;
                        
                        // Check if it's BridgeLink.png, load from /images/ resource
                        if (iconName.equals("BridgeLink.png")) {
                            try {
                                iconImage = new Image(getClass().getResourceAsStream("/images/BridgeLink.png"));
                            } catch (Exception e) {
                                // Fallback to default icon if resource fails
                            }
                        } else {
                            // Load from data/icons folder for other icons
                            File icon = new File(new File(dataFolder, "icons"), iconName);
                            if (icon.exists()) {
                                try {
                                    iconImage = new Image(icon.toURI().toString());
                                } catch (Exception e) {
                                    // Failed to load file icon
                                }
                            }
                        }
                        
                        // Health status dot (shown once the connection has been probed)
                        javafx.scene.Node healthGraphic = null;
                        if (connectionHealth != null) {
                            ConnectionHealth.Status hs = connectionHealth.getStatus(item);
                            if (hs == ConnectionHealth.Status.REACHABLE || hs == ConnectionHealth.Status.UNREACHABLE) {
                                Text dot = new Text("\u25CF");
                                dot.setFill(hs == ConnectionHealth.Status.REACHABLE
                                        ? Color.web("#2e9e44") : Color.web("#cc3333"));
                                healthGraphic = dot;
                            }
                        }

                        if (iconImage != null) {
                            ImageView value = new ImageView(iconImage);
                            value.setPreserveRatio(true);
                            value.setFitHeight(15);
                            if (healthGraphic != null) {
                                HBox graphic = new HBox(3, value, healthGraphic);
                                graphic.setAlignment(Pos.CENTER_LEFT);
                                setGraphic(graphic);
                            } else {
                                setGraphic(value);
                            }
                        } else if (healthGraphic != null) {
                            setGraphic(healthGraphic);
                        } else {
                            setGraphic(null);
                        }
                    } else {
                        setGraphic(null);
                    }

                    // Show the connection notes (and address) as a tooltip on hover
                    if (item != null && !empty && item.getAddress() != null) {
                        StringBuilder tip = new StringBuilder();
                        if (StringUtils.isNotBlank(item.getName())) {
                            tip.append(item.getName()).append('\n');
                        }
                        tip.append(item.getAddress());
                        if (connectionHealth != null) {
                            ConnectionHealth.Status hs = connectionHealth.getStatus(item);
                            if (hs == ConnectionHealth.Status.REACHABLE) {
                                tip.append("\n\nStatus: reachable (")
                                   .append(connectionHealth.getStatusMessage(item)).append(')');
                            } else if (hs == ConnectionHealth.Status.UNREACHABLE) {
                                tip.append("\n\nStatus: unreachable (")
                                   .append(connectionHealth.getStatusMessage(item)).append(')');
                            }
                        }
                        if (StringUtils.isNotBlank(item.getNotes())) {
                            tip.append("\n\nNotes:\n").append(item.getNotes());
                        }
                        setTooltip(new Tooltip(tip.toString()));
                    } else {
                        setTooltip(null);
                    }
                }
            };

            cell.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !cell.isEmpty()) {
                    Connection conn = cell.getItem();
                    if (conn != null && conn.getAddress() != null) { // Only act on connections, not groups
                        if (isLaunching) {
                            return;
                        }
                        // Double-click launches the connection directly; use F2 to rename.
                        treeSelectionModel.select(cell.getTreeItem());
                        launch();
                    }
                }
            });
            return cell;
        });

        // Handle edit commit
        connectionsTreeView.setOnEditCommit(event -> {
            Connection conn = event.getNewValue();
            if (conn != null && conn.getAddress() != null) {
                String newName = StringUtils.trim(conn.getName());
                boolean exists = connectionsList.stream().anyMatch(c ->
                        !StringUtils.equals(c.getId(), conn.getId()) &&
                                StringUtils.equalsIgnoreCase(c.getName(), newName));
                if (!exists) {
                    conn.setName(newName);
                    saveConnections();
                }
                updateTreeView();
            }
        });

        connectionsList.addAll(loadConnections());

        // Build tree structure
        TreeItem<Connection> rootTree = new TreeItem<>(null);
        rootTree.setExpanded(true);
        connectionsTreeView.setRoot(rootTree);
        connectionsTreeView.setShowRoot(false);

        updateTreeView();

        // Selection model
        treeSelectionModel = connectionsTreeView.getSelectionModel(); // Corrected type usage
        treeSelectionModel.selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            Connection selectedConn = newVal != null ? newVal.getValue() : null;
            boolean isConnection = selectedConn != null && selectedConn.getAddress() != null;

            // Only clear temporary icon when switching to a different connection
            Connection oldConn = oldVal != null ? oldVal.getValue() : null;
            boolean connectionChanged = selectedConn != oldConn;
            if (connectionChanged) {
                tempSelectedIcon = null;
            }

            if (isConnection) {
                updateUIFromConnection(selectedConn);
                takeFormSnapshot();
            } else {
                groupTextField.setText("");
                addressTextField.setText("");
                usernameTextField.setText("");
                passwordField.setText("");
                showConsoleCheckBox.setSelected(false);
                bundledJavaCombo.getSelectionModel().select(0);
                heapSizeCombo.getSelectionModel().select(1);
                jvmOptionsTextField.setText("");
                notesTextArea.setText("");
                sshTunnelTextField.setText("");
                clearCacheCheckBox.setSelected(false);
                trustSelfSignedCheckBox.setSelected(false);
                // Reset radio buttons to bundled and update control states
                bundledJavaRadio.setSelected(true);
                customJavaTextField.setText("");
                updateJavaControlStates();
                loadedSnapshot = null;
            }

            saveButton.setDisable(true);
            revertButton.setDisable(true);
            duplicateButton.setDisable(!isConnection);
            deleteButton.setDisable(!isConnection);
            launchButton.setDisable(!isConnection);
            groupTextField.setDisable(!isConnection);
            addressTextField.setDisable(!isConnection);
            usernameTextField.setDisable(!isConnection);
            passwordField.setDisable(!isConnection);
            jvmOptionsTextField.setDisable(!isConnection);
            notesTextArea.setDisable(!isConnection);
            sshTunnelTextField.setDisable(!isConnection);
            sshTunnelTestButton.setDisable(!isConnection);
            showConsoleCheckBox.setDisable(!isConnection);
            trustSelfSignedCheckBox.setDisable(!isConnection);

            // Don't directly control bundledJavaCombo and customJavaTextField here
            // Let updateJavaControlStates() handle them based on radio button selection
            // But disable radio buttons if no connection is selected
            bundledJavaRadio.setDisable(!isConnection);
            customJavaRadio.setDisable(!isConnection);
            
            // Only if no connection is selected, disable both Java controls
            if (!isConnection) {
                bundledJavaCombo.setDisable(true);
                heapSizeCombo.setDisable(true);
                customJavaTextField.setDisable(true);
            } else {
                heapSizeCombo.setDisable(false);
                // Let updateJavaControlStates() handle bundledJavaCombo and customJavaTextField
            }

            exportButton.setDisable(connectionsList.isEmpty());
        });

        // Right buttons
        VBox rightButtons = new VBox(10);
        importButton = new Button("Import");
        importButton.setOnAction(e -> importConnections());
        exportButton = new Button("Export");
        exportButton.setOnAction(e -> exportConnections());
        exportButton.setDisable(true);
        rightButtons.getChildren().addAll(importButton, exportButton);
        rightButtons.setAlignment(Pos.TOP_CENTER);

        HBox tableArea = new HBox(10);
        tableArea.getChildren().addAll(connectionsTreeView, rightButtons);
        HBox.setHgrow(connectionsTreeView, Priority.ALWAYS);

        connectionsSection.getChildren().addAll(tableButtons, tableArea);

        // Configuration Section (Modified: Java Home and Max Heap Size on same line)
        VBox configBox = new VBox(10);

        // Group row
        HBox groupRow = new HBox(10);
        Label groupLabel = new Label("Group:");
        groupTextField = new TextField();
        groupTextField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        groupRow.getChildren().addAll(groupLabel, groupTextField);
        HBox.setHgrow(groupTextField, Priority.ALWAYS);

        // Address row
        HBox addressRow = new HBox(10);
        Label addressLabel = new Label("Address:");
        addressTextField = new TextField("https://localhost:8443");
        addressTextField.setPromptText("https://server:port");
        addressTextField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        addressRow.getChildren().addAll(addressLabel, addressTextField);
        HBox.setHgrow(addressTextField, Priority.ALWAYS);

        // Credential row
        HBox credentialsRow = new HBox(10);
        Label usernameLabel = new Label("Username:");
        usernameTextField = new TextField();
        usernameTextField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        Label passwordLabel = new Label("Password:");
        passwordField = new PasswordField();
        passwordField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        credentialsRow.getChildren().addAll(usernameLabel, usernameTextField, passwordLabel, passwordField);
        HBox.setHgrow(usernameTextField, Priority.ALWAYS);
        HBox.setHgrow(passwordField, Priority.ALWAYS);

        // Java Config rows
        // Java Home row with radio buttons, combo box, and text field
        HBox javaHomeRow = new HBox(10);
        Label javaHomeLabel = new Label("Java Home:");
        ToggleGroup javaTypeGroup = new ToggleGroup();
        bundledJavaRadio = new RadioButton("Bundled");
        bundledJavaRadio.setToggleGroup(javaTypeGroup);
        bundledJavaRadio.setSelected(true);
        customJavaRadio = new RadioButton("Custom");
        customJavaRadio.setToggleGroup(javaTypeGroup);
        
        bundledJavaCombo = new ComboBox<>(FXCollections.observableArrayList(
                new BundledJava("", "Java 17"),
                new BundledJava("", "Java 8")
        ));
        bundledJavaCombo.getSelectionModel().select(0);
        bundledJavaCombo.setOnAction(e -> updateSaveButtonState());

        // Custom Java text field
        customJavaTextField = new TextField();
        customJavaTextField.setPromptText("Enter custom Java home path (e.g., /usr/lib/jvm/java-11)");
        customJavaTextField.setDisable(true); // Start disabled
        customJavaTextField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());

        javaHomeRow.getChildren().addAll(javaHomeLabel, bundledJavaRadio, customJavaRadio, bundledJavaCombo, customJavaTextField);
        HBox.setHgrow(bundledJavaCombo, Priority.ALWAYS);
        HBox.setHgrow(customJavaTextField, Priority.ALWAYS);

        // Heap Size row
        HBox heapSizeRow = new HBox(10);
        Label heapSizeLabel = new Label("Max Heap Size:");
        heapSizeCombo = new ComboBox<>(FXCollections.observableArrayList(
                new HeapMemory("256m", "256 MB"),
                new HeapMemory("512m", "512 MB"),
                new HeapMemory("1g", "1 GB"),
                new HeapMemory("2g", "2 GB"),
                new HeapMemory("4g", "4 GB")
        ));
        heapSizeCombo.getSelectionModel().select(1);
        heapSizeCombo.setOnAction(e -> updateSaveButtonState());
        heapSizeRow.getChildren().addAll(heapSizeLabel, heapSizeCombo);
        HBox.setHgrow(heapSizeCombo, Priority.ALWAYS);

        // Radio button event handlers to enable/disable appropriate fields
        bundledJavaRadio.setOnAction(e -> {
            updateJavaControlStates();
            updateSaveButtonState();
        });

        customJavaRadio.setOnAction(e -> {
            updateJavaControlStates();
            updateSaveButtonState();
        });

        // JVM options row
        HBox jvmOptionsRow = new HBox(10);
        Label jvmOptionsLabel = new Label("JVM Options:");
        jvmOptionsTextField = new TextField("");
        jvmOptionsTextField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        jvmOptionsRow.getChildren().addAll(jvmOptionsLabel, jvmOptionsTextField);
        HBox.setHgrow(jvmOptionsTextField, Priority.ALWAYS);

        // Notes row
        HBox notesRow = new HBox(10);
        Label notesLabel = new Label("Notes:");
        notesTextArea = new TextArea("");
        notesTextArea.setPromptText("Enter notes about this connection...");
        notesTextArea.setPrefRowCount(3);
        notesTextArea.setWrapText(true);
        notesTextArea.setMaxHeight(80);
        notesTextArea.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        notesRow.getChildren().addAll(notesLabel, notesTextArea);
        notesRow.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(notesTextArea, Priority.ALWAYS);

        HBox consoleRow = new HBox(10);
        Label consoleLabel = new Label("Show Java Console:");
        showConsoleCheckBox = new CheckBox();
        showConsoleCheckBox.setSelected(false);
        showConsoleCheckBox.setOnAction(e -> updateSaveButtonState());
        Label clearCacheLabel = new Label("Clear Java Cache:");
        clearCacheCheckBox = new CheckBox();
        clearCacheCheckBox.setSelected(false);
        clearCacheCheckBox.setOnAction(e -> updateSaveButtonState());
        trustSelfSignedCheckBox = new CheckBox("Trust self-signed certificate (insecure)");
        trustSelfSignedCheckBox.setSelected(false);
        trustSelfSignedCheckBox.setTooltip(new Tooltip(
                "Disables SSL certificate and hostname verification for this connection.\n" +
                "Only enable it for servers using self-signed certificates; it makes the\n" +
                "connection vulnerable to man-in-the-middle attacks."));
        trustSelfSignedCheckBox.setOnAction(e -> updateSaveButtonState());
        consoleRow.getChildren().addAll(consoleLabel, showConsoleCheckBox, clearCacheLabel, clearCacheCheckBox);

        // Security options row
        HBox securityRow = new HBox(10);
        securityRow.getChildren().addAll(trustSelfSignedCheckBox);
        securityRow.setAlignment(Pos.CENTER_LEFT);

        // SSH tunnel row: optional "ssh -L ..." command to jump through a tunnel
        HBox sshTunnelRow = new HBox(10);
        Label sshTunnelLabel = new Label("SSH Tunnel:");
        sshTunnelTextField = new TextField();
        sshTunnelTextField.setPromptText("e.g. ssh -L 8443:mirth.prova.it:8443 root@node01.picopalla.it (leave empty for direct connection)");
        sshTunnelTextField.textProperty().addListener((obs, oldVal, newVal) -> updateSaveButtonState());
        sshTunnelTextField.setTooltip(new Tooltip(
                "Optional SSH local-port-forwarding command used to reach the server\n" +
                "through a tunnel (jump). Example:\n" +
                "  ssh -L 8443:mirth.prova.it:8443 root@node01.picopalla.it\n" +
                "Supported options: -L (required), -p <port>, -i <keyfile>, -N, -f.\n" +
                "At Launch an \"ssh -N\" process is started in the background and the\n" +
                "address is rewritten to http://localhost:<localport>; the tunnel is\n" +
                "closed automatically when BridgeLink exits.\n" +
                "Use Test to verify the command syntax and that the tunnel opens."));
        sshTunnelTestButton = new Button("Test");
        sshTunnelTestButton.setTooltip(new Tooltip("Opens the tunnel, verifies the port is listening, then closes it"));
        sshTunnelTestButton.setOnAction(e -> testSshTunnel());
        sshTunnelRow.getChildren().addAll(sshTunnelLabel, sshTunnelTextField, sshTunnelTestButton);
        HBox.setHgrow(sshTunnelTextField, Priority.ALWAYS);

        // Icon selection row
        HBox iconRow = new HBox(10);
        Label iconSelectionLabel = new Label("Icon:");

        // Create icon button with default icon image
        iconButton = new Button();
        iconButton.setPrefSize(32, 32);
        iconButton.setMinSize(32, 32);
        iconButton.setMaxSize(32, 32);
        iconButton.setStyle("-fx-background-color: transparent; -fx-border-color: #cccccc; -fx-border-radius: 4;");

        // Set default icon
        ImageView defaultIconView = new ImageView(LAUNCHER_ICON);
        defaultIconView.setFitWidth(24);
        defaultIconView.setFitHeight(24);
        defaultIconView.setPreserveRatio(true);
        iconButton.setGraphic(defaultIconView);
        iconButton.setOnAction(e -> {
            IconSelectionDialog iconDialog = new IconSelectionDialog(
                primaryStage,
                dataFolder,
                BRIDGELINK_ICON,
                this::updateIconSelection
            );
            iconDialog.show();
        });

        iconRow.getChildren().addAll(iconSelectionLabel, iconButton);
        iconRow.setAlignment(Pos.CENTER_LEFT);

        configBox.getChildren().addAll(groupRow, addressRow, credentialsRow, javaHomeRow, heapSizeRow, jvmOptionsRow, notesRow, consoleRow, securityRow, sshTunnelRow, iconRow);

        // Progress Section (unchanged)
        Separator separator = new Separator();
        progressBar = new ProgressBar(0.0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        launchButton = new Button("Launch");
        launchButton.setOnAction(e -> launch());
        cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> cancelLaunch());
        cancelButton.setVisible(false);
        StackPane buttonStack = new StackPane(launchButton, cancelButton);
        buttonStack.setAlignment(Pos.CENTER);
        HBox progressBarBox = new HBox(10, progressBar, buttonStack);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressIndicator = new ProgressIndicator(-1.0);
        progressIndicator.setPrefHeight(20.0);
        progressText = new Text("Requesting main JNLP...");
        VBox progressBox = new VBox(10, separator, progressBarBox, progressIndicator, progressText);
        setProgressControlsVisible(false);

        // Bottom Section (unchanged)
        HBox bottomBox = new HBox(10);
        closeWindowCheckBox = new CheckBox("Close after launch");
        updateStatusLabel = new Label("");
        updateStatusLabel.setVisible(false);
        updateStatusLabel.setStyle("-fx-text-fill: #b8860b; -fx-font-weight: bold;");
        openReleaseButton = new Button("Download");
        openReleaseButton.setVisible(false);
        openReleaseButton.setOnAction(e -> {
            if (lastReleaseUrl != null) {
                getHostServices().showDocument(lastReleaseUrl);
            }
        });
        dismissUpdateButton = new Button("\u00D7");
        dismissUpdateButton.setVisible(false);
        dismissUpdateButton.setOnAction(e -> {
            updateStatusLabel.setVisible(false);
            openReleaseButton.setVisible(false);
            dismissUpdateButton.setVisible(false);
        });
        bottomBox.getChildren().addAll(closeWindowCheckBox, updateStatusLabel, openReleaseButton, dismissUpdateButton);
        bottomBox.setAlignment(Pos.CENTER_LEFT);

        // Assemble layout
        root.getChildren().addAll(connectionsSection, configBox, progressBox, bottomBox);

        // Select first connection
        if (!connectionsList.isEmpty()) {
            TreeItem<Connection> firstGroup = connectionsTreeView.getRoot().getChildren().get(0);
            if (firstGroup != null && !firstGroup.getChildren().isEmpty()) {
                treeSelectionModel.select(firstGroup.getChildren().get(0));
            }
        }

        Scene scene = new Scene(root, 800, 600);
        stage.setScene(scene);
        stage.setOnCloseRequest(this::handleWindowCloseRequest);
        stage.show();

        // Fit the window to its content on startup so every section — and a
        // usable chunk of the connections tree — is visible; users can still
        // resize freely afterwards.
        root.layout();
        stage.setWidth(Math.max(root.prefWidth(-1), 800));
        stage.setHeight(Math.max(root.prefHeight(-1), 640));
        stage.setMinWidth(720);
        stage.setMinHeight(560);

        // Capture the initial snapshot so the dirty-tracking (Save/Revert) works
        // from the very first selection made above.
        takeFormSnapshot();
        saveButton.setDisable(true);
        revertButton.setDisable(true);

        newButton.requestFocus();

        // Check write permissions to "data" and "cache" folders after showing the application
        checkWritePermissions(stage);

        // Check GitHub for a newer release (silent; shows a banner when found)
        updateChecker = new UpdateChecker();
        updateChecker.checkAsync(false, result -> {
            if (result.updateAvailable) {
                showUpdateBanner(result.latestVersion, result.releaseUrl);
            }
        });
    }

    /** Shows the "new version available" banner in the bottom bar. */
    private void showUpdateBanner(String latestVersion, String releaseUrl) {
        lastReleaseUrl = releaseUrl;
        updateStatusLabel.setText("New version available: v" + latestVersion);
        updateStatusLabel.setVisible(true);
        openReleaseButton.setVisible(true);
        dismissUpdateButton.setVisible(true);
    }

    private void initializeDirectories() {
        // Get the application directory (where the JAR or class file resides)
        try {
            String jarPath = BridgeLinkLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            appDir = new File(jarPath).getParent(); // Parent directory of the JAR/class file
            appDir = URLDecoder.decode(appDir, StandardCharsets.UTF_8.name()); // Decode path
        } catch (Exception e) {
            appDir = System.getProperty("user.dir"); // Fallback to user.dir if detection fails
            LOG.warn("Failed to determine application directory: {}", e.getMessage());
        }

        if (startupArgs.length > 0 && StringUtils.isNotBlank(startupArgs[0])) {
            appDir = startupArgs[0]; // Override with first parameter if provided
        }

        File appRoot = new File(appDir);
        File baseDir = appRoot;
        // System install locations (/opt/... on Linux, Program Files on Windows)
        // are usually not writable by regular users: keep user data in the home
        // directory instead so settings survive and can always be saved.
        if (!isWritableBase(appRoot)) {
            baseDir = new File(System.getProperty("user.home"), ".mirto-launcher");
            LOG.warn("Application folder is not writable ({}); storing data in {}", appDir, baseDir);
            migrateDataFolder(new File(appRoot, "data"), new File(baseDir, "data"));
            // Compatibility: pick up settings stored by pre-rename builds (~/.bridgelink-launcher).
            File legacyBase = new File(System.getProperty("user.home"), ".bridgelink-launcher");
            if (!legacyBase.getAbsoluteFile().equals(baseDir.getAbsoluteFile())) {
                migrateDataFolder(new File(legacyBase, "data"), new File(baseDir, "data"));
            }
        }

        // Set up data and cache folders
        dataFolder = new File(baseDir, "data");
        cacheFolder = new File(baseDir, "cache");

        // Copy resource icons to data/icons folder
        copyResourceIconsToDataFolder();
    }

    /** True when a "data" folder can be created and written inside the given directory. */
    private static boolean isWritableBase(File dir) {
        try {
            File data = new File(dir, "data");
            if (!data.exists() && !data.mkdirs()) {
                return false;
            }
            File probe = File.createTempFile("bridgelink-probe", ".tmp", data);
            return probe.delete();
        } catch (Exception e) {
            return false;
        }
    }

    /** One-time copy of existing settings from a read-only install folder to the user home. */
    private static void migrateDataFolder(File source, File target) {
        if (!source.isDirectory() || new File(target, "connections.json").exists()) {
            return; // nothing to migrate, or already migrated on a previous run
        }
        try {
            copyDirectory(source, target);
            LOG.info("Migrated existing settings from {} to {}", source, target);
        } catch (Exception e) {
            LOG.warn("Could not migrate settings from {}: {}", source, e.getMessage());
        }
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("Cannot create directory: " + dst);
        }
        File[] children = src.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File out = new File(dst, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, out);
            } else {
                Files.copy(child.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void checkWritePermissions(Stage stage) {
        // Check "data" folder
        StringBuilder errorMessage = new StringBuilder();

        if (!dataFolder.exists()) {
            try {
                dataFolder.mkdirs(); // Create the "data" folder if it doesn't exist
            } catch (SecurityException e) {
                errorMessage.append("Cannot create 'data' folder in: ").append(appDir)
                        .append("\nError: ").append(e.getMessage()).append("\n");
            }
        }

        if (dataFolder.exists() && !dataFolder.isDirectory()) {
            errorMessage.append("'data' path exists but is not a directory: ")
                    .append(dataFolder.getAbsolutePath()).append("\n");
        } else if (dataFolder.exists()) {
            try {
                File tempFile = File.createTempFile("test", ".tmp", dataFolder);
                tempFile.delete(); // Clean up
            } catch (IOException e) {
                errorMessage.append("No write permission in 'data' folder: ")
                        .append(dataFolder.getAbsolutePath())
                        .append("\nError: ").append(e.getMessage()).append("\n");
            }
        }

        // Check "cache" folder
        if (!cacheFolder.exists()) {
            try {
                cacheFolder.mkdirs(); // Create the "cache" folder if it doesn't exist
            } catch (SecurityException e) {
                errorMessage.append("Cannot create 'cache' folder in: ").append(appDir)
                        .append("\nError: ").append(e.getMessage()).append("\n");
            }
        }

        if (cacheFolder.exists() && !cacheFolder.isDirectory()) {
            errorMessage.append("'cache' path exists but is not a directory: ")
                    .append(cacheFolder.getAbsolutePath()).append("\n");
        } else if (cacheFolder.exists()) {
            try {
                File tempFile = File.createTempFile("test", ".tmp", cacheFolder);
                tempFile.delete(); // Clean up
            } catch (IOException e) {
                errorMessage.append("No write permission in 'cache' folder: ")
                        .append(cacheFolder.getAbsolutePath())
                        .append("\nError: ").append(e.getMessage()).append("\n");
            }
        }

        // Show alert if there are any errors
        if (errorMessage.length() > 0) {
            showWritePermissionAlert(stage, errorMessage.toString());
        }
    }

    private void showWritePermissionAlert(Stage stage, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(stage);
        alert.setTitle("Write Permission Error");
        alert.setHeaderText("Cannot write to required folders");
        alert.setContentText(message + "\nSome features (e.g., saving connections or caching) may not work. " +
                "Please ensure the 'data' and 'cache' folders are writable.");
        alert.showAndWait();
    }

    private void updateTreeView() {
        updateTreeViewWithFilter(filterField != null ? filterField.getText() : "");
    }

    private void updateTreeViewWithFilter(String filter) {
        TreeItem<Connection> root = connectionsTreeView.getRoot();
        if (root == null) {
            root = new TreeItem<>(null);
            root.setExpanded(true);
            connectionsTreeView.setRoot(root);
            connectionsTreeView.setShowRoot(false);
        }
        root.getChildren().clear();

        Map<String, TreeItem<Connection>> groupItems = new HashMap<>();
        String filterLower = filter.toLowerCase().trim();

        for (Connection conn : connectionsList) {
            String groupName = StringUtils.isBlank(conn.getGroup()) ? "Ungrouped" : conn.getGroup();
            String name = conn.getName() != null ? conn.getName() : "";

            if (filterLower.isEmpty() || name.toLowerCase().contains(filterLower) || groupName.toLowerCase().contains(filterLower)) {
                TreeItem<Connection> groupItem = groupItems.computeIfAbsent(groupName,
                        k -> {
                            Connection groupConn = new Connection(null, "", null, null, null, null, null,
                                    null, false, false, null, false, null, false, null, null, groupName, null, false, false, null, false, null);
                            TreeItem<Connection> item = new TreeItem<>(groupConn);
                            item.setExpanded(true);
                            return item;
                        });

                TreeItem<Connection> connItem = new TreeItem<>(conn);
                groupItem.getChildren().add(connItem);

                if (!root.getChildren().contains(groupItem)) {
                    root.getChildren().add(groupItem);
                }
            }
        }
    }

    private void createNewConnection() {
        if (!isLaunching) {
            String newName = "New Connection";
            int counter = 1;
            while (nameExists(newName)) {
                newName = "New Connection " + counter++;
            }

            TextInputDialog dialog = new TextInputDialog(newName);
            dialog.setTitle("New Connection");
            dialog.setHeaderText("Enter connection name:");
            Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
            dialogStage.getIcons().add(LAUNCHER_ICON);

            dialog.showAndWait().ifPresent(name -> {
                addConnectionWithName(name);
                updateTreeView();
                Connection newConn = connectionsList.stream()
                        .filter(conn -> conn.getName().equals(name))
                        .findFirst()
                        .orElse(null);
                if (newConn != null) {
                    TreeItem<Connection> newItem = findTreeItem(newConn);
                    if (newItem != null) {
                        treeSelectionModel.select(newItem);
                    }
                }
            });
        }
    }

    private void addConnectionWithName(String name) {
        if (StringUtils.isNotBlank(name)) {
            String finalName = name;
            int cnt = 1;
            while (nameExists(finalName)) {
                finalName = name + " Copy " + cnt++;
            }
            addConnection(finalName, "", "BUNDLED", "Java 17", "", "512m", "", false, "", false, "", false, "", "", "", "", false, "", false, "");
        }
    }

    private void updateJavaControlStates() {
        if (customJavaRadio.isSelected()) {
            bundledJavaCombo.setDisable(true);
            customJavaTextField.setDisable(false);
        } else {
            bundledJavaCombo.setDisable(false);
            customJavaTextField.setDisable(true);
        }
    }

    private void saveCurrentConnection() {
        if (!isLaunching) {
            TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null && selectedItem.getValue().getAddress() != null) {
                Connection currentConnection = selectedItem.getValue();

                updateConnectionFromUI(currentConnection);
                updateTreeView();
                connectionsTreeView.refresh();
                saveButton.setDisable(true);
                saveConnections();
                takeFormSnapshot(); // changes are persisted: reset the dirty state

                TreeItem<Connection> newSelectedItem = findTreeItem(currentConnection);
                if (newSelectedItem != null) {
                    treeSelectionModel.select(newSelectedItem);
                }
                
                // Ensure the Java control states remain correct after saving
                updateJavaControlStates();
            }
        }
    }

    private void duplicateConnection() {
        if (!isLaunching) {
            TextInputDialog dialog = new TextInputDialog("New Connection");
            dialog.setTitle("Duplicate Connection");
            dialog.setHeaderText("Enter new connection name:");
            Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
            dialogStage.getIcons().add(LAUNCHER_ICON);

            dialog.showAndWait().ifPresent(name -> {
                if (StringUtils.isNotBlank(name)) {
                    TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
                    Connection source = (selectedItem != null) ? selectedItem.getValue() : null;

                    String finalName = name;
                    int cnt = 1;
                    while (nameExists(finalName)) {
                        finalName = name + " Copy " + cnt++;
                    }
                    // Duplicate the selected connection preserving all its properties
                    // (icon, group, JVM options, notes, ...), overriding only id and name.
                    Connection newConn;
                    if (source != null && source.getAddress() != null) {
                        newConn = new Connection(source);
                    } else {
                        newConn = new Connection();
                        updateConnectionFromUI(newConn);
                    }
                    newConn.setId(UUID.randomUUID().toString());
                    newConn.setName(finalName);
                    connectionsList.add(newConn);
                    updateTreeView();
                    saveConnections();
                    treeSelectionModel.select(findTreeItem(newConn));
                }
            });
        }
    }

    private void deleteCurrentConnection() {
        if (!isLaunching) {
            TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null && selectedItem.getValue().getAddress() != null) {
                Connection selected = selectedItem.getValue();
                String originalGroup = StringUtils.isBlank(selected.getGroup()) ? "Ungrouped" : selected.getGroup();

                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Delete Connection");
                alert.setHeaderText("Confirm deletion?");
                Stage dialogStage = (Stage) alert.getDialogPane().getScene().getWindow();
                dialogStage.getIcons().add(LAUNCHER_ICON);

                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.OK) {
                        connectionsList.remove(selected);
                        updateTreeView();
                        saveConnections();

                        if (!connectionsList.isEmpty()) {
                            TreeItem<Connection> newGroupItem = null;
                            for (TreeItem<Connection> groupItem : connectionsTreeView.getRoot().getChildren()) {
                                if (StringUtils.equals(groupItem.getValue().getGroup(), originalGroup)) {
                                    newGroupItem = groupItem;
                                    break;
                                }
                            }

                            if (newGroupItem != null && !newGroupItem.getChildren().isEmpty()) {
                                treeSelectionModel.select(newGroupItem.getChildren().get(0));
                            } else if (!connectionsTreeView.getRoot().getChildren().isEmpty()) {
                                TreeItem<Connection> firstGroup = connectionsTreeView.getRoot().getChildren().get(0);
                                if (!firstGroup.getChildren().isEmpty()) {
                                    treeSelectionModel.select(firstGroup.getChildren().get(0));
                                } else {
                                    treeSelectionModel.clearSelection();
                                }
                            } else {
                                treeSelectionModel.clearSelection();
                            }
                        } else {
                            treeSelectionModel.clearSelection();
                        }
                    }
                });
            }
        }
    }

    private TreeItem<Connection> findTreeItem(Connection conn) {
        for (TreeItem<Connection> groupItem : connectionsTreeView.getRoot().getChildren()) {
            for (TreeItem<Connection> item : groupItem.getChildren()) {
                if (item.getValue() == conn) return item;
            }
        }
        return null;
    }

    private void launch() {
        if (isLaunching || addressTextField.getText().isEmpty()) return;

        isLaunching = true;
        setUIEnabled(false);
        setProgressControlsVisible(true);
        progressText.setText("Launching " + addressTextField.getText());
        progressBar.setProgress(0.0);
        progressIndicator.setProgress(-1.0);
        cancelButton.setDisable(false);

        launchThread = new Thread(() -> {
            try {
                String host = addressTextField.getText();

                // Optional SSH tunnel: open it first, then route the download
                // through the local end of the tunnel.
                SshTunnel tunnel = null;
                String tunnelCmdText = sshTunnelTextField.getText().trim();
                if (!tunnelCmdText.isEmpty()) {
                    String error = SshTunnel.validate(tunnelCmdText);
                    if (error != null) {
                        throw new IllegalStateException("Invalid SSH Tunnel command: " + error);
                    }
                    tunnel = parseTunnel(tunnelCmdText);
                    final SshTunnel activeTunnel = tunnel;
                    updateProgressText("Opening SSH tunnel on localhost:" + activeTunnel.localPort + "...");
                    startTunnelProcess(activeTunnel);
                    host = activeTunnel.rewriteUrl(host);
                    final String tunneledHost = host;
                    Platform.runLater(() -> {
                        progressText.setText("Launching " + tunneledHost + " (via SSH tunnel)");
                        log("SSH tunnel active: " + String.join(" ", activeTunnel.buildCommand()));
                    });
                }

                updateProgressText("Downloading JNLP from " + host);

                DownloadJNLP download = new DownloadJNLP(host, cacheFolder, clearCacheCheckBox.isSelected());
                currentDownload = download;

                String customJavaHome = customJavaRadio.isSelected() ? customJavaTextField.getText() : null;
                JavaConfig javaConfig = new JavaConfig(heapSizeCombo.getValue().toString(), this.bundledJavaCombo.getValue().toString(), this.jvmOptionsTextField.getText(), customJavaHome);
                Credential credential = new Credential(StringUtils.trim(usernameTextField.getText()), StringUtils.trim(passwordField.getText()));
                CodeBase codeBase = download.handle(this);
                currentDownload = null;

                ProcessLauncher process = new ProcessLauncher();
                updateProgressText("Starting application...");

                // Get icon path and connection name for the selected connection
                TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
                String iconPath = null;
                String connectionName = null;
                if (selectedItem != null && selectedItem.getValue() != null && selectedItem.getValue().getAddress() != null) {
                    Connection selectedConnection = selectedItem.getValue();
                    connectionName = selectedConnection.getName();

                    if (selectedConnection.getIcon() != null && !selectedConnection.getIcon().trim().isEmpty()) {
                        String iconFileName = selectedConnection.getIcon();
                        if (iconFileName.startsWith("resource:")) {
                            // Remove the "resource:" prefix and use the filename directly from data/icons
                            iconFileName = iconFileName.substring("resource:".length());
                        }

                        // All icons (resource and user-uploaded) are now in data/icons folder
                        File iconFile = new File(new File(dataFolder, "icons"), iconFileName);
                        if (iconFile.exists()) {
                            iconPath = iconFile.getAbsolutePath();
                        }
                    }
                }

                process.launch(javaConfig, credential, codeBase, showConsoleCheckBox.isSelected(), iconPath, connectionName);

                updateProgressText("Application launched successfully");

                Thread.sleep(1000);
                Platform.runLater(() -> {
                    if (closeWindowCheckBox.isSelected()) {
                        primaryStage.close();
                    } else {
                        resetUI();
                    }
                });

            } catch (InterruptedException e) {
                Platform.runLater(() -> {
                    progressText.setText("Launch cancelled");
                    resetUI();
                });
            } catch (Exception e ) {
                // Failed to launch: do not leave an orphan tunnel behind
                stopTunnelProcess();
                Platform.runLater(() -> {
                    showErrorDialog(e, "Launch Failed");
                    resetUI();
                });
            } finally {
                currentDownload = null;
            }
        }, "Launch Thread");
        launchThread.start();
    }

    private void cancelLaunch() {
        if (launchThread != null && launchThread.isAlive()) {
            Platform.runLater(() -> progressText.setText("Cancelling..."));
            launchThread.interrupt();
            if (currentDownload != null) {
                currentDownload.cancel();
            }

            // Give a moment for cancellation to propagate
            try {
                launchThread.join(1000); // Wait up to 1 second for thread to exit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
            // If the tunnel was already opened, make sure it is not orphaned
            stopTunnelProcess();
        }
    }

    /**
     * Parses an already-validated ssh tunnel command into its components.
     */
    private static SshTunnel parseTunnel(String command) {
        String error = SshTunnel.validate(command);
        if (error != null) {
            throw new IllegalStateException(error);
        }
        return SshTunnel.parse(command);
    }

    /**
     * Starts the background "ssh -N" process for the given tunnel and waits
     * until the local forwarded port accepts connections.
     */
    private void startTunnelProcess(SshTunnel tunnel) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(tunnel.buildCommand());
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            throw new Exception("Cannot execute \"ssh\". Make sure an OpenSSH client is installed and available in the PATH. (" + e.getMessage() + ")");
        }
        // The tunnel must die together with this JVM even if no shutdown hook is
        // registered (e.g. when the app is started through the JavaFX launcher
        // without going through main()).
        final Process spawned = p;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (spawned.isAlive()) {
                        spawned.destroy();
                    }
                } catch (Throwable ignored) {
                    // best-effort cleanup during JVM shutdown
                }
            }, "ssh-tunnel-destroy"));
        } catch (IllegalStateException ignored) {
            // JVM already shutting down: nothing to register
        }
        tunnelProcess = p;

        boolean listening = false;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                int code = p.exitValue();
                throw new Exception("The SSH tunnel closed immediately (exit code " + code + ").\n" +
                        "Check the command, the jump host reachability and the SSH credentials/key.");
            }
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("localhost", tunnel.localPort), 500);
                listening = true;
                break;
            } catch (Exception ignored) {
                Thread.sleep(300);
            }
        }
        if (!listening) {
            stopTunnelProcess();
            throw new Exception("Timed out waiting for the SSH tunnel: localhost:" + tunnel.localPort +
                    " is not accepting connections.\nThe remote endpoint may be unreachable through the jump host.");
        }

        // The local port accepting connections is not enough: ssh -L accepts the
        // socket even when it cannot open a channel to the target, then closes it
        // right away. Verify end-to-end with a protocol-agnostic probe: a working
        // tunnel stays open waiting for data (timeout), a broken one EOFs at once.
        boolean forwarding = false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", tunnel.localPort), 2000);
            s.setSoTimeout(3000);
            int b = -2;
            try {
                b = s.getInputStream().read();
            } catch (java.net.SocketTimeoutException e) {
                forwarding = true; // held open: the remote end is reachable
            }
            if (!forwarding && b != -1) {
                forwarding = true; // server spoke first (e.g. TLS alert): tunnel works
            }
        } catch (java.io.IOException ignored) {
            // connect/read failure means the tunnel is not usable
        }
        if (!forwarding) {
            stopTunnelProcess();
            throw new Exception("The SSH tunnel is listening, but the jump host could not reach " +
                    tunnel.remoteHost + ":" + tunnel.remotePort + ".\n" +
                    "Check that the target host and port are correct and reachable from the jump host.");
        }
    }

    /**
     * Terminates the background tunnel process, if one is running. The tunnel
     * is normally kept alive for the whole BridgeLink session (the Java child
     * process inherits the listening port) and is destroyed when this launcher
     * exits thanks to the shutdown hook registered in main().
     */
    private void stopTunnelProcess() {
        Process p = tunnelProcess;
        tunnelProcess = null;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    /**
     * Validates the tunnel command currently typed in the form, then briefly
     * opens the tunnel to verify it works, and closes it again.
     */
    private void testSshTunnel() {
        String cmd = sshTunnelTextField.getText().trim();
        if (cmd.isEmpty()) {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("SSH Tunnel");
            info.setHeaderText("No SSH tunnel configured");
            info.setContentText("Enter a command like:\n  ssh -L 8443:mirth.prova.it:8443 root@node01.picopalla.it\nthen press Test again.");
            info.initOwner(primaryStage);
            info.showAndWait();
            return;
        }

        String error = SshTunnel.validate(cmd);
        if (error != null) {
            showAlert("Invalid SSH Tunnel command: " + error);
            return;
        }

        final SshTunnel tunnel = SshTunnel.parse(cmd);
        sshTunnelTestButton.setDisable(true);
        Thread t = new Thread(() -> {
            String message;
            Alert.AlertType type;
            try {
                startTunnelProcess(tunnel);
                message = "Tunnel established successfully:\n" +
                        String.join(" ", tunnel.buildCommand()) + "\n\n" +
                        "localhost:" + tunnel.localPort + " is forwarding to " +
                        tunnel.remoteHost + ":" + tunnel.remotePort + ".\n" +
                        "The test tunnel has been closed; it will be reopened automatically at Launch.";
                type = Alert.AlertType.INFORMATION;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                message = "Tunnel test cancelled.";
                type = Alert.AlertType.WARNING;
            } catch (Exception e) {
                message = e.getMessage() != null ? e.getMessage() : e.toString();
                type = Alert.AlertType.ERROR;
            } finally {
                stopTunnelProcess();
            }
            final String resultMessage = message;
            final Alert.AlertType resultType = type;
            // The Alert (and its Stage) must be created on the FX Application Thread.
            Platform.runLater(() -> {
                Alert alert = new Alert(resultType);
                alert.setTitle("SSH Tunnel Test");
                alert.setHeaderText(resultType == Alert.AlertType.INFORMATION ? "OK" : "Failed");
                alert.setContentText(resultMessage);
                alert.initOwner(primaryStage);
                alert.showAndWait();
                sshTunnelTestButton.setDisable(false);
            });
        }, "SSH Tunnel Test");
        t.setDaemon(true);
        t.start();
    }

    private void importConnections() {
        if (isLaunching) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Connections");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Files", "*.json")
        );
        File file = fileChooser.showOpenDialog(primaryStage);

        if (file != null) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                ObjectMapper objectMapper = new ObjectMapper();
                List<Connection> importedConnections = objectMapper.readValue(content,
                        new TypeReference<List<Connection>>() {});

                // Handle duplicate names
                for (Connection conn : importedConnections) {
                    String baseName = conn.getName();
                    String newName = baseName;
                    int counter = 1;
                    while (nameExists(newName)) {
                        newName = baseName + " (Imported " + counter++ + ")";
                    }
                    conn.setName(newName);
                    // Import icons
                    if (conn.getIcon() != null && !conn.getIcon().trim().isEmpty()) {
                        File sourceIcon = new File(new File(file.getParentFile(), "icons"), conn.getIcon());
                        if (sourceIcon.exists()) {
                            File iconFolder = new File(dataFolder, "icons");
                            iconFolder.mkdirs();
                            File targetIcon = new File(iconFolder, conn.getIcon());
                            Files.copy(sourceIcon.toPath(), targetIcon.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            conn.setIcon("");
                        }
                    }
                    conn.setId(UUID.randomUUID().toString()); // Generate new unique ID
                    connectionsList.add(conn);
                }

                updateTreeView();
                saveConnections();
            } catch (IOException e) {
                showAlert("Failed to import connections: " + e.getMessage());
            }
        }
    }

    private void exportConnections() {
        if (isLaunching || connectionsList.isEmpty()) return;

        // Derive the scoping options from the current tree selection: a selected
        // connection node offers itself and its group, a selected group node
        // offers that group.
        Connection selectedConnection = null;
        String selectedGroup = null;
        TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
        if (selectedItem != null && selectedItem.getValue() != null) {
            Connection value = selectedItem.getValue();
            if (value.getAddress() != null) { // connection node
                selectedConnection = value;
                if (StringUtils.isNotBlank(value.getGroup())) {
                    selectedGroup = value.getGroup();
                }
            } else if (StringUtils.isNotBlank(value.getGroup())) { // group node
                selectedGroup = value.getGroup();
            }
        }

        ExportOptionsDialog exportDialog = new ExportOptionsDialog(primaryStage, LAUNCHER_ICON,
                selectedConnection != null ? selectedConnection.getName() : null, selectedGroup);
        if (!exportDialog.showAndWait()) return;

        List<Connection> toExport;
        String baseName;
        switch (exportDialog.getScope()) {
            case SELECTED_CONNECTION:
                if (selectedConnection == null) { // defensive: option was disabled
                    toExport = connectionsList;
                    baseName = "all_connections";
                } else {
                    toExport = new ArrayList<>();
                    toExport.add(selectedConnection);
                    baseName = sanitizeFileName(selectedConnection.getName());
                }
                break;
            case SELECTED_GROUP:
                final String group = selectedGroup != null ? selectedGroup : "";
                toExport = new ArrayList<>();
                for (Connection conn : connectionsList) {
                    if (group.equals(conn.getGroup())) {
                        toExport.add(conn);
                    }
                }
                baseName = sanitizeFileName(group) + "_connections";
                break;
            case ALL:
            default:
                toExport = connectionsList;
                baseName = "all_connections";
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Connections");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Files", "*.json")
        );
        fileChooser.setInitialFileName(baseName + ".json");
        File file = fileChooser.showSaveDialog(primaryStage);

        if (file != null) {
            writeConnectionsToFile(file, toExport, exportDialog.isExportWithCredential());
        }
    }

    /** Makes a connection/group name safe for use as a file name. */
    private static String sanitizeFileName(String name) {
        String s = StringUtils.isBlank(name) ? "connection" : name.trim();
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void writeConnectionsToFile(File file, List<Connection> connections, boolean withCredential) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            if (!withCredential) {
                List<Connection> sanitized = new ArrayList<>();
                for (Connection conn : connections) {
                    Connection copy = new Connection(
                            conn.getId(), conn.getName(), conn.getAddress(),
                            conn.getJavaHome(), conn.getJavaHomeBundledValue(), conn.getJavaFxHome(),
                            conn.getHeapSize(), conn.getIcon(), conn.isShowJavaConsole(),
                            conn.isSslProtocolsCustom(), conn.getSslProtocols(),
                            conn.isSslCipherSuitesCustom(), conn.getSslCipherSuites(),
                            conn.isUseLegacyDHSettings(), "", "",
                            conn.getGroup(), conn.getJvmOptions(), conn.isCloseWindow(),
                            conn.isClearCacheJars(), conn.getCustomJavaHome(), conn.isUseCustomJavaHome(),
                            conn.getNotes());
                    sanitized.add(copy);
                }
                objectMapper.writeValue(file, sanitized);
            } else {
                objectMapper.writeValue(file, connections);
            }
        } catch (IOException e) {
            showAlert("Failed to export connections: " + e.getMessage());
        }
    }

    private void updateIconSelection(String iconFileName) {
        TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
        if (selectedItem != null && selectedItem.getValue() != null && selectedItem.getValue().getAddress() != null) {
            // Store the temporarily selected icon (don't update connection yet)
            tempSelectedIcon = iconFileName != null ? iconFileName : "";

            // Update icon button to show the new selection
            updateIconButton(iconFileName);

            // Enable save button since icon changed
            updateSaveButtonState();
        }
    }

    private void updateIconButton(String iconFileName) {
        ImageView iconView;

        if (iconFileName != null && !iconFileName.trim().isEmpty()) {
            try {
                // Check if it's BridgeLink.png, load from /images/ resource
                if (iconFileName.equals("BridgeLink.png")) {
                    iconView = new ImageView(BRIDGELINK_ICON);
                } else {
                    // Load from data/icons folder for other icons
                    File iconFile = new File(new File(dataFolder, "icons"), iconFileName);
                    if (iconFile.exists()) {
                        Image customIcon = new Image(iconFile.toURI().toString());
                        iconView = new ImageView(customIcon);
                    } else {
                        iconView = new ImageView(BRIDGELINK_ICON);
                    }
                }
            } catch (Exception e) {
                iconView = new ImageView(BRIDGELINK_ICON);
            }
        } else {
            iconView = new ImageView(BRIDGELINK_ICON);
        }

        iconView.setFitWidth(24);
        iconView.setFitHeight(24);
        iconView.setPreserveRatio(true);
        iconButton.setGraphic(iconView);
    }

    private void setUIEnabled(boolean enabled) {
        TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
        boolean isConnectionSelected = selectedItem != null && selectedItem.getValue() != null &&
                selectedItem.getValue().getAddress() != null;
        boolean finalEnabled = enabled && isConnectionSelected;

        // Disable input fields and specific buttons based on selection
        groupTextField.setDisable(!finalEnabled);
        addressTextField.setDisable(!finalEnabled);
        usernameTextField.setDisable(!finalEnabled);
        passwordField.setDisable(!finalEnabled);
        heapSizeCombo.setDisable(!finalEnabled);
        jvmOptionsTextField.setDisable(!finalEnabled);
        notesTextArea.setDisable(!finalEnabled);
        sshTunnelTextField.setDisable(!finalEnabled);
        sshTunnelTestButton.setDisable(!finalEnabled);
        showConsoleCheckBox.setDisable(!finalEnabled);
        clearCacheCheckBox.setDisable(!finalEnabled);
        iconButton.setDisable(!finalEnabled);
        saveButton.setDisable(!finalEnabled);
        duplicateButton.setDisable(!finalEnabled);
        deleteButton.setDisable(!finalEnabled);
        launchButton.setDisable(!finalEnabled);

        // Handle Java controls specially - disable radio buttons if no connection selected
        bundledJavaRadio.setDisable(!finalEnabled);
        customJavaRadio.setDisable(!finalEnabled);
        
        if (!finalEnabled) {
            // If no connection selected or launching, disable both Java controls
            bundledJavaCombo.setDisable(true);
            customJavaTextField.setDisable(true);
        } else {
            // If connection is selected and not launching, let updateJavaControlStates() handle the enables/disables
            updateJavaControlStates();
        }

        // Other UI elements only disabled during launch
        boolean launchEnabled = enabled;
        closeWindowCheckBox.setDisable(!launchEnabled);
        newButton.setDisable(!launchEnabled);
        importButton.setDisable(!launchEnabled);
        exportButton.setDisable(!launchEnabled || connectionsList.isEmpty());
        connectionsTreeView.setDisable(!launchEnabled);
    }

    private void resetUI() {
        progressBar.setProgress(0.0);
        progressIndicator.setProgress(-1.0);
        progressText.setText("");
        setUIEnabled(true);
        setProgressControlsVisible(false);
        isLaunching = false;

        // need update Save button after reset UI
        // Apply after launcher
        updateSaveButtonState();
    }

    private void setProgressControlsVisible(boolean visible) {
        progressBar.setVisible(visible);
        progressIndicator.setVisible(visible);
        progressText.setVisible(visible);
        launchButton.setVisible(!visible);
        cancelButton.setVisible(visible);
    }

    private boolean nameExists(String name) {
        return this.connectionsList.stream().anyMatch(conn -> StringUtils.equalsIgnoreCase(name, conn.getName()));
    }

    private String getJavaHome() {
        if (customJavaRadio != null && customJavaRadio.isSelected()) {
            return "CUSTOM";
        } else {
            return "BUNDLED";
        }
    }

    private List<Connection> loadConnections() {
        List<Connection> connections = new ArrayList<>();
        File connectionsFile = new File(dataFolder, "connections.json"); // Use dataFolder directly
        if (connectionsFile.exists()) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                connections = objectMapper.readValue(connectionsFile, new TypeReference<List<Connection>>() {});

                // Handle existing connections that don't have clearCacheJars field (set to false)
                for (Connection conn : connections) {
                    // Jackson will set clearCacheJars to false for missing fields, so no additional handling needed
                }

                // Semantic validation: surface unusable entries (bad address/heap size)
                // now with a precise message instead of an opaque failure at launch.
                String problems = ConnectionsValidator.validate(connections);
                if (problems != null) {
                    showAlert("Some saved connections have invalid settings and may fail to launch:\n" + problems);
                }
            } catch (IOException e) {
                showAlert("Unable to load connections from file: " + connectionsFile.getAbsolutePath()
                        + ". The file is corrupt or has an unexpected structure.\nError: " + e.getMessage());
            }
        }
        return connections;
    }

    private void saveConnections() {
        // Ensure the "data" folder exists
        if (!dataFolder.exists()) {
            dataFolder.mkdirs(); // Create the "data" folder if it doesn't exist
        }

        File connectionsFile = new File(dataFolder, "connections.json");
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(connectionsFile, this.connectionsList);
        } catch (IOException e) {
            showAlert(e.getMessage());
        }
    }

    private void addConnection(String name, String address, String javaHome, String javaHomeBundledValue,
                               String javaFxHome, String heapSize, String icon, boolean showJavaConsole,
                               String sslProtocols, boolean sslProtocolsCustom, String sslCipherSuites,
                               boolean useLegacyDHSettings, String username, String password, String group, String jvmOptions, boolean closeWindow, String customJavaHome, boolean useCustomJavaHome, String notes) {
        Connection conn = new Connection(UUID.randomUUID().toString(), name, address, javaHome,
                javaHomeBundledValue, javaFxHome, heapSize, icon, showJavaConsole,
                sslProtocolsCustom, sslProtocols, false, sslCipherSuites, useLegacyDHSettings,
                username, password, group, jvmOptions, closeWindow, false, customJavaHome, useCustomJavaHome, notes);
        this.connectionsList.add(conn);
        updateTreeView(); // Update tree after adding
        saveConnections();
        this.treeSelectionModel.select(findTreeItem(conn));
        this.saveButton.setDisable(true);
    }

    private void updateUIFromConnection(Connection conn) {
        groupTextField.setText(StringUtils.defaultString(conn.getGroup()));
        addressTextField.setText(StringUtils.defaultString(conn.getAddress()));
        usernameTextField.setText(StringUtils.defaultString(conn.getUsername()));
        passwordField.setText(StringUtils.defaultString(conn.getPassword()));
        showConsoleCheckBox.setSelected(conn.isShowJavaConsole());

        String javaHomeBundledValue = conn.getJavaHomeBundledValue();
        for (BundledJava e : this.bundledJavaCombo.getItems()) {
            if (StringUtils.equalsIgnoreCase(e.getVersion(), javaHomeBundledValue)) {
                this.bundledJavaCombo.getSelectionModel().select(e);
                break;
            }
        }

        String heapSize = conn.getHeapSize();
        for (HeapMemory e : this.heapSizeCombo.getItems()) {
            if (StringUtils.equalsIgnoreCase(e.getValue(), heapSize)) {
                this.heapSizeCombo.getSelectionModel().select(e);
                break;
            }
        }
        jvmOptionsTextField.setText(StringUtils.defaultString(conn.getJvmOptions()));
        notesTextArea.setText(StringUtils.defaultString(conn.getNotes()));
        sshTunnelTextField.setText(StringUtils.defaultString(conn.getSshTunnelCommand()));
        closeWindowCheckBox.setSelected(conn.isCloseWindow());
        clearCacheCheckBox.setSelected(conn.isClearCacheJars());
        trustSelfSignedCheckBox.setSelected(conn.isTrustSelfSignedCertificate());

        // Handle custom Java home setting
        String customJavaHome = conn.getCustomJavaHome();
        customJavaTextField.setText(StringUtils.defaultString(customJavaHome));
        
        // Set radio button based on stored preference
        if (conn.isUseCustomJavaHome()) {
            customJavaRadio.setSelected(true);
        } else {
            bundledJavaRadio.setSelected(true);
        }

        // Ensure control states are properly set
        updateJavaControlStates();

        // Update icon UI - use tempSelectedIcon if set, otherwise use connection's icon
        String iconToDisplay = (tempSelectedIcon != null) ? tempSelectedIcon : conn.getIcon();
        updateIconButton(iconToDisplay);
    }

    private void updateConnectionFromUI(Connection conn) {
        conn.setGroup(this.groupTextField.getText());
        conn.setAddress(this.addressTextField.getText());
        conn.setUsername(this.usernameTextField.getText());
        conn.setPassword(this.passwordField.getText());
        conn.setJavaHome(getJavaHome());
        conn.setJavaHomeBundledValue(this.bundledJavaCombo.getValue().toString());
        conn.setJavaFxHome("");
        conn.setHeapSize(this.heapSizeCombo.getValue().toString());
        conn.setJvmOptions(this.jvmOptionsTextField.getText());
        conn.setNotes(this.notesTextArea.getText());
        String tunnelCmd = this.sshTunnelTextField.getText().trim();
        conn.setSshTunnelCommand(tunnelCmd.isEmpty() ? null : tunnelCmd);

        // Apply temporary icon if it exists, otherwise keep existing icon
        if (tempSelectedIcon != null) {
            conn.setIcon(tempSelectedIcon);
            tempSelectedIcon = null; // Clear temporary icon after applying
        } else if (conn.getIcon() == null) {
            conn.setIcon("");
        }

        conn.setShowJavaConsole(showConsoleCheckBox.isSelected());
        conn.setSslProtocolsCustom(false);
        conn.setSslProtocols("");
        conn.setSslCipherSuitesCustom(false);
        conn.setSslCipherSuites("");
        conn.setUseLegacyDHSettings(false);
        conn.setTrustSelfSignedCertificate(trustSelfSignedCheckBox.isSelected());
        conn.setCloseWindow(this.closeWindowCheckBox.isSelected());
        conn.setClearCacheJars(this.clearCacheCheckBox.isSelected());

        // Always save custom Java home value if it exists, regardless of radio selection
        String customJavaPath = customJavaTextField.getText().trim();
        if (!customJavaPath.isEmpty()) {
            conn.setCustomJavaHome(customJavaPath);
        } else {
            conn.setCustomJavaHome(null);
        }
        
        // Save the radio button selection state
        conn.setUseCustomJavaHome(customJavaRadio.isSelected());
    }

    private void updateSaveButtonState() {
        if (isLaunching) {
            this.saveButton.setDisable(true);
            return;
        }

        TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null) {
            this.saveButton.setDisable(true);
            tempSelectedIcon = null; // Clear temp icon if no selection
            return;
        }

        Connection selected = selectedItem.getValue();
        if (selected == null) {
            this.saveButton.setDisable(true);
            tempSelectedIcon = null; // Clear temp icon if no connection
            return;
        }

        String currentIcon = getCurrentSelectedIcon();
        String originalIcon = selected.getIcon();
        log("Original Icon: " + originalIcon + ", Current Icon: " + currentIcon);

        // Compare the live form against the snapshot taken when the connection
        // was loaded (or right after the last save). This keeps the list of
        // compared fields in one single place.
        boolean unchanged = !isFormDirty();
        this.saveButton.setDisable(unchanged);
        this.revertButton.setDisable(unchanged || isLaunching);
    }

    private String getCurrentSelectedIcon() {
        // Return temporary icon if it exists, otherwise return the saved icon
        if (tempSelectedIcon != null) {
            return tempSelectedIcon;
        }
        TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
        if (selectedItem != null && selectedItem.getValue() != null && selectedItem.getValue().getAddress() != null) {
            return selectedItem.getValue().getIcon();
        }
        return "";
    }

    /** Snapshot of every form value that {@link #updateSaveButtonState()} compares. */
    private Map<String, Object> captureFormValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("group", groupTextField.getText());
        values.put("address", addressTextField.getText());
        values.put("username", usernameTextField.getText());
        values.put("password", passwordField.getText());
        values.put("javaHome", getJavaHome());
        values.put("javaHomeBundledValue", bundledJavaCombo.getValue() != null ? bundledJavaCombo.getValue().toString() : "");
        values.put("customJavaHome", customJavaTextField.getText());
        values.put("useCustomJavaHome", customJavaRadio.isSelected());
        values.put("heapSize", heapSizeCombo.getValue() != null ? heapSizeCombo.getValue().toString() : "");
        values.put("jvmOptions", jvmOptionsTextField.getText());
        values.put("notes", notesTextArea.getText());
        values.put("sshTunnelCommand", sshTunnelTextField.getText().trim());
        values.put("showJavaConsole", showConsoleCheckBox.isSelected());
        values.put("trustSelfSignedCertificate", trustSelfSignedCheckBox.isSelected());
        values.put("closeWindow", closeWindowCheckBox.isSelected());
        values.put("clearCacheJars", clearCacheCheckBox.isSelected());
        values.put("icon", getCurrentSelectedIcon());
        return values;
    }

    /**
     * Takes a snapshot of the current form so later edits can be detected and
     * discarded with Revert. Must be called right after the form has been
     * populated from a connection (or reset to defaults).
     */
    private void takeFormSnapshot() {
        loadedSnapshot = captureFormValues();
    }

    /** @return true when the form differs from the last snapshot taken. */
    private boolean isFormDirty() {
        Map<String, Object> snapshot = loadedSnapshot;
        if (snapshot == null) {
            return false;
        }
        return !captureFormValues().equals(snapshot);
    }

    /**
     * Discards unsaved changes: re-populates the form from the connection's
     * stored values (i.e. what was last persisted) and refreshes the tree.
     */
    private void revertChanges() {
        if (isLaunching) {
            return;
        }
        TreeItem<Connection> selectedItem = connectionsTreeView.getSelectionModel().getSelectedItem();
        Connection selected = selectedItem != null ? selectedItem.getValue() : null;
        if (selected == null || selected.getAddress() == null) {
            return;
        }
        tempSelectedIcon = null; // drop any pending icon selection
        updateUIFromConnection(selected);
        takeFormSnapshot();
        saveButton.setDisable(true);
        revertButton.setDisable(true);
        connectionsTreeView.refresh(); // cell tooltips are derived from the notes field
    }

    /**
     * Warns about unsaved edits before closing the launcher; consumes the close
     * request when the user chooses to stay.
     */
    private void handleWindowCloseRequest(javafx.stage.WindowEvent event) {
        if (!isLaunching && isFormDirty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("There are unsaved changes for the selected connection.");
            alert.setContentText("Do you want to leave without saving?");
            Stage dialogStage = (Stage) alert.getDialogPane().getScene().getWindow();
            dialogStage.getIcons().add(LAUNCHER_ICON);
            alert.initOwner(primaryStage);
            Optional<ButtonType> response = alert.showAndWait();
            if (!response.isPresent() || response.get() != ButtonType.OK) {
                event.consume();
                return;
            }
        }
        stopTunnelProcess();
        if (connectionHealth != null) {
            connectionHealth.shutdown();
        }
    }

    public void showAlert(String err){
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(err);
        alert.initOwner(primaryStage);
        alert.showAndWait();
    }

    private void showErrorDialog(Throwable t, String header) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setResizable(true);
        alert.setHeight(550.0);
        alert.setWidth(550.0);
        TextArea textArea = new TextArea(ExceptionUtils.getStackTrace(t));
        textArea.setEditable(false);
        alert.getDialogPane().setContent(textArea);
        alert.initOwner(this.primaryStage);
        alert.show();
    }

    @Override
    public void updateProgressBar(double progress){
        Platform.runLater(() -> this.progressBar.setProgress(progress));
    }
    @Override
    public void updateProgressText(String message){
        Platform.runLater(() -> this.progressText.setText(message));
    }

    /**
     * Copies all resource icons to the data/icons folder for easy access
     */
    private void copyResourceIconsToDataFolder() {
        try {
            // Create icons directory if it doesn't exist
            File iconsFolder = new File(dataFolder, "icons");
            if (!iconsFolder.exists()) {
                iconsFolder.mkdirs();
            }

            // List of default icon names from resources
            String[] defaultIconNames = {
                "deep-learning.png", "healthcare.png", "sharing.png",
                "sun.png", "support.png", "support2.png", "transfer.png"
            };

            // Copy icons from /icons/ folder
            for (String iconName : defaultIconNames) {
                try {
                    InputStream iconStream = getClass().getResourceAsStream("/icons/" + iconName);
                    if (iconStream != null) {
                        File targetFile = new File(iconsFolder, iconName);

                        // Only copy if file doesn't exist or if resource is newer
                        if (!targetFile.exists()) {
                            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                                byte[] buffer = new byte[1024];
                                int bytesRead;
                                while ((bytesRead = iconStream.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                            log("Copied resource icon: " + iconName + " to " + targetFile.getAbsolutePath());
                        }
                        iconStream.close();
                    }
                } catch (IOException e) {
                    log("Failed to copy resource icon " + iconName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log("Error setting up resource icons: " + e.getMessage());
        }
    }
    /** Thin alias over SLF4J so existing call sites keep working unchanged. */
    private void log(String message) {
        LOG.debug(message);
    }
    public static void main(String[] args) {
        SSLBypass.disableSSLVerification();
        // Keep a reference to the running application so the shutdown hook can
        // terminate any SSH tunnel process spawned by the launcher (Java 8 API only).
        RUNTIME_APP_HOLDER = new BridgeLinkLauncher[1];
        final BridgeLinkLauncher[] appHolder = RUNTIME_APP_HOLDER;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            BridgeLinkLauncher app = appHolder[0];
            if (app != null) {
                try {
                    app.stopTunnelProcess();
                } catch (Throwable t) {
                    // best-effort cleanup: never let the hook crash the JVM shutdown
                }
            }
        }, "ssh-tunnel-cleanup"));
        Application.launch(BridgeLinkLauncher.class, args);
    }

    /** Shared holder so {@link #start} and the shutdown hook in {@link #main} can meet. */
    private static BridgeLinkLauncher[] RUNTIME_APP_HOLDER;

    @SuppressWarnings("unchecked")
    private static BridgeLinkLauncher[] getAppHolder() {
        BridgeLinkLauncher[] holder = RUNTIME_APP_HOLDER;
        return holder != null ? holder : new BridgeLinkLauncher[1];
    }
}
