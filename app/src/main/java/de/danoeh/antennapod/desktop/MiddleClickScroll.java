package de.danoeh.antennapod.desktop;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Popup;
import javafx.stage.Window;

/** Browser-style middle-click scrolling, without changing the list selection. */
final class MiddleClickScroll {
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("auto-scrolling");
    private final ListView<?> list;
    private final Popup marker = new Popup();
    private Scene scene;
    private Window window;
    private VirtualFlow<?> flow;
    private Cursor previousCursor;
    private double anchorY;
    private double distance;
    private long lastFrame;

    private final EventHandler<MouseEvent> move = event ->
            distance = event.getScreenY() - anchorY;
    private final EventHandler<MouseEvent> cancelClick = event -> {
        stop();
        event.consume();
    };
    private final EventHandler<KeyEvent> cancelKey = event -> {
        if (event.getCode() == KeyCode.ESCAPE) {
            stop();
            event.consume();
        }
    };
    private final ChangeListener<Boolean> focus = (obs, was, focused) -> {
        if (!focused) {
            stop();
        }
    };
    private final ChangeListener<Window> sceneWindow = (obs, was, current) -> stop();
    private final ChangeListener<Boolean> showing = (obs, was, shown) -> {
        if (!shown) {
            stop();
        }
    };
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (lastFrame != 0) {
                // Cap elapsed time so a busy UI never produces a sudden jump.
                double seconds = Math.min((now - lastFrame) / 1_000_000_000.0, 0.05);
                flow.scrollPixels(speed(distance) * seconds);
            }
            lastFrame = now;
        }
    };

    static void install(ListView<?> list) {
        new MiddleClickScroll(list);
    }

    private MiddleClickScroll(ListView<?> list) {
        this.list = list;
        Label icon = new Label("\u2195");
        icon.getStyleClass().add("auto-scroll-marker");
        icon.setMouseTransparent(true);
        marker.getContent().add(icon);
        marker.setAutoFix(false);
        marker.setHideOnEscape(false);
        marker.getScene().getRoot().setMouseTransparent(true);
        list.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.MIDDLE && start(event)) {
                event.consume();
            }
        });
        // Do not let a middle click reach episode double-click playback handlers.
        list.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.MIDDLE) {
                event.consume();
            }
        });
        list.sceneProperty().addListener((obs, was, current) -> stop());
        list.visibleProperty().addListener((obs, was, visible) -> {
            if (!visible) {
                stop();
            }
        });
    }

    private boolean start(MouseEvent event) {
        Scene current = list.getScene();
        if (current == null || current.getWindow() == null) {
            return false;
        }
        list.applyCss();
        ScrollBar vertical = null;
        for (var node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                vertical = bar;
                break;
            }
        }
        if (vertical == null || !vertical.isVisible() || vertical.getMax() <= vertical.getMin()
                || !(list.lookup(".virtual-flow") instanceof VirtualFlow<?> virtualFlow)) {
            return false;
        }
        scene = current;
        window = current.getWindow();
        flow = virtualFlow;
        anchorY = event.getScreenY();
        distance = 0;
        lastFrame = 0;
        previousCursor = scene.getCursor();
        scene.setCursor(Cursor.V_RESIZE);
        scene.getRoot().pseudoClassStateChanged(ACTIVE, true);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, move);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, move);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, cancelClick);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, cancelKey);
        window.focusedProperty().addListener(focus);
        window.showingProperty().addListener(showing);
        scene.windowProperty().addListener(sceneWindow);
        ThemeManager.style(marker.getScene());
        marker.show(window, event.getScreenX() - 15, anchorY - 15);
        timer.start();
        return true;
    }

    private void stop() {
        if (scene == null) {
            return;
        }
        timer.stop();
        marker.hide();
        scene.setCursor(previousCursor);
        scene.getRoot().pseudoClassStateChanged(ACTIVE, false);
        scene.removeEventFilter(MouseEvent.MOUSE_MOVED, move);
        scene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, move);
        scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, cancelClick);
        scene.removeEventFilter(KeyEvent.KEY_PRESSED, cancelKey);
        window.focusedProperty().removeListener(focus);
        window.showingProperty().removeListener(showing);
        scene.windowProperty().removeListener(sceneWindow);
        scene = null;
        window = null;
        flow = null;
    }

    /** A small dead zone lets the pointer rest at the anchor without drifting. */
    static double speed(double distance) {
        double beyondDeadZone = Math.max(0, Math.abs(distance) - 12);
        return Math.copySign(Math.min(1400, beyondDeadZone * 5), distance);
    }
}
