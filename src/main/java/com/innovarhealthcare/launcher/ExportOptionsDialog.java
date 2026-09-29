package com.innovarhealthcare.launcher;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Dialog for selecting export options when exporting connections:
 * which scope (all / selected connection / group) and whether to include credentials.
 *
 * @author thait
 */
public class ExportOptionsDialog {

    /** What the user chose to export. */
    public enum Scope { ALL, SELECTED_CONNECTION, SELECTED_GROUP }

    private final Stage parentStage;
    private final Image icon;
    private final String selectedConnectionName; // null when no connection is selected
    private final String selectedGroupName;      // null when no group scope applies
    private boolean exportWithCredential = false;
    private Scope scope = Scope.ALL;
    private boolean confirmed = false;

    /**
     * @param parentStage            owner stage of the dialog
     * @param icon                   window icon (may be null)
     * @param selectedConnectionName name of the currently selected connection, or null
     * @param selectedGroupName      group to offer as a scope: from a selected group node,
     *                               or the non-empty group of the selected connection; null when not applicable
     */
    public ExportOptionsDialog(Stage parentStage, Image icon, String selectedConnectionName, String selectedGroupName) {
        this.parentStage = parentStage;
        this.icon = icon;
        this.selectedConnectionName = selectedConnectionName;
        this.selectedGroupName = selectedGroupName;
    }

    /**
     * Shows the dialog and blocks until it is closed.
     *
     * @return true if the user clicked OK, false if cancelled
     */
    public boolean showAndWait() {
        Stage dialogStage = new Stage();
        dialogStage.setTitle("Export Connections");
        dialogStage.initOwner(parentStage);
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.setResizable(false);
        if (icon != null) {
            dialogStage.getIcons().add(icon);
        }

        VBox root = new VBox(12);
        root.setPadding(new Insets(15));

        Label promptLabel = new Label("Export options:");

        // Scope selection: scoped options are only enabled when a matching
        // selection exists in the connections tree.
        ToggleGroup scopeToggle = new ToggleGroup();
        RadioButton allRadio = new RadioButton("All connections");
        allRadio.setToggleGroup(scopeToggle);
        allRadio.setSelected(true);

        RadioButton connectionRadio = new RadioButton(
                selectedConnectionName != null ? "Selected connection: " + selectedConnectionName : "Selected connection");
        connectionRadio.setToggleGroup(scopeToggle);
        connectionRadio.setDisable(selectedConnectionName == null);

        RadioButton groupRadio = new RadioButton(
                selectedGroupName != null ? "Group: " + selectedGroupName : "Group of selected connection");
        groupRadio.setToggleGroup(scopeToggle);
        groupRadio.setDisable(selectedGroupName == null);

        VBox scopeBox = new VBox(4, allRadio, connectionRadio, groupRadio);

        CheckBox credentialCheckBox = new CheckBox("Include user credentials");
        credentialCheckBox.setSelected(false); // default: strip credentials

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        Button okButton = new Button("OK");
        okButton.setDefaultButton(true);
        okButton.setOnAction(e -> {
            exportWithCredential = credentialCheckBox.isSelected();
            if (connectionRadio.isSelected()) {
                scope = Scope.SELECTED_CONNECTION;
            } else if (groupRadio.isSelected()) {
                scope = Scope.SELECTED_GROUP;
            } else {
                scope = Scope.ALL;
            }
            confirmed = true;
            dialogStage.close();
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> dialogStage.close());

        buttonBox.getChildren().addAll(okButton, cancelButton);
        root.getChildren().addAll(promptLabel, scopeBox, credentialCheckBox, buttonBox);

        Scene scene = new Scene(root, 400, 230);
        dialogStage.setScene(scene);
        dialogStage.showAndWait();

        return confirmed;
    }

    public boolean isExportWithCredential() {
        return exportWithCredential;
    }

    public void setExportWithCredential(boolean exportWithCredential) {
        this.exportWithCredential = exportWithCredential;
    }

    /** @return the scope chosen by the user (default {@link Scope#ALL}). */
    public Scope getScope() {
        return scope;
    }
}
