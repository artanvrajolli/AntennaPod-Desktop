package de.danoeh.antennapod.desktop;

import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The floating card in the window's bottom-right corner that a background update reports
 * through: what it is doing, a progress bar, and the actions that fit the moment (cancel, restart
 * now, retry). It only shows what it is told; the update flow in {@code DesktopApp} decides.
 */
final class UpdatePill extends VBox {
    private final Label title = new Label();
    private final Label detail = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final HBox actions = new HBox(6);
    private final Button close = new Button("", Icons.windowClose());
    private Runnable onClose = () -> { };

    UpdatePill() {
        getStyleClass().add("update-pill");
        setSpacing(8);
        setPadding(new Insets(10, 10, 12, 14));
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setMinWidth(300);
        setPrefWidth(340);
        setPickOnBounds(false);
        StackPane.setAlignment(this, Pos.BOTTOM_RIGHT);

        Node icon = Icons.accent(Icons.download());
        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("update-pill-badge");
        badge.setMinSize(32, 32);
        badge.setMaxSize(32, 32);

        title.getStyleClass().add("update-pill-title");
        detail.getStyleClass().addAll("update-pill-detail", "muted-label");
        detail.setWrapText(true);
        progress.getStyleClass().add("update-pill-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(6);

        VBox text = new VBox(3, title, detail, progress);
        text.setFillWidth(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        close.getStyleClass().addAll("icon-button", "update-pill-close");
        close.setTooltip(new Tooltip(Messages.get("common.close")));
        DesktopApp.nameFromTooltip(close);
        close.setOnAction(event -> onClose.run());

        // the buttons get a row of their own under the text, so their labels never truncate
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox top = new HBox(12, badge, text, close);
        top.setAlignment(Pos.TOP_LEFT);

        getChildren().addAll(top, actions);
        // stays managed: the StackPane only places managed children in its corner
        setVisible(false);
    }

    /**
     * Shows the given state.
     *
     * @param progressValue 0..1, {@link ProgressBar#INDETERMINATE_PROGRESS}, or null to hide the bar
     * @param closable whether the close button shows; it calls the handler set with {@link #setOnClose}
     */
    void show(String titleText, String detailText, Double progressValue, boolean closable,
            List<Button> buttons) {
        title.setText(titleText);
        detail.setText(detailText == null ? "" : detailText);
        detail.setVisible(detailText != null && !detailText.isEmpty());
        detail.setManaged(detail.isVisible());
        progress.setVisible(progressValue != null);
        progress.setManaged(progressValue != null);
        if (progressValue != null) {
            progress.setProgress(progressValue);
        }
        close.setVisible(closable);
        close.setManaged(closable);
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        actions.getChildren().setAll(buttons);
        actions.setVisible(!buttons.isEmpty());
        actions.setManaged(!buttons.isEmpty());
        if (!isVisible()) {
            appear();
        }
    }

    /** Moves the bar without rebuilding anything else; called for every downloaded chunk. */
    void setProgress(double value, String detailText) {
        progress.setProgress(value);
        if (detailText != null && !detailText.equals(detail.getText())) {
            detail.setText(detailText);
            detail.setVisible(true);
            detail.setManaged(true);
        }
    }

    void setOnClose(Runnable handler) {
        onClose = handler == null ? () -> { } : handler;
    }

    void hide() {
        if (!isVisible()) {
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(160), this);
        fade.setToValue(0);
        fade.setOnFinished(event -> {
            setVisible(false);
            setOpacity(1);
        });
        fade.play();
    }

    private void appear() {
        setOpacity(0);
        setTranslateY(12);
        setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(200), this);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(200), this);
        slide.setToY(0);
        new ParallelTransition(fade, slide).play();
    }
}
