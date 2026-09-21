package de.danoeh.antennapod.desktop;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * The window's own title bar, drawn in place of the one Windows provides so the top of the window
 * follows the app theme instead of staying system grey.
 *
 * <p>An undecorated stage gives up everything the system frame did for us, so the pieces worth
 * keeping are rebuilt here: dragging the bar moves the window, double-clicking it maximises,
 * dragging against a screen edge snaps, and the outer six pixels of the window resize it. What
 * cannot be rebuilt from this side is the keyboard half of Aero Snap ({@code Win}+arrow) and the
 * Snap Layouts flyout, both of which Windows only offers to windows that kept the system frame.
 * Starting the app with {@code -Dantennapod.desktop.customchrome=false} restores that frame.
 */
public final class WindowChrome {
    /** Matches the caption height Windows 11 uses, so the window does not look unusually tall. */
    static final double BAR_HEIGHT = 34;
    /** How far inside the window edge a press still counts as a resize rather than a click. */
    static final double RESIZE_MARGIN = 6;
    /** How close to a screen edge the pointer must be dropped for the window to snap to it. */
    static final double SNAP_MARGIN = 8;
    /** Edge length of the app icon in the corner of the bar. */
    static final double ICON_SIZE = 16;

    static final int ZONE_NONE = 0;
    static final int ZONE_NORTH = 1;
    static final int ZONE_SOUTH = 2;
    static final int ZONE_WEST = 4;
    static final int ZONE_EAST = 8;

    private final Stage stage;
    private final Button maximizeButton = new Button();
    private final Label titleLabel = new Label();
    private final String version;
    private HBox captionButtons;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging;
    private boolean dragMoved;
    /** Size to go back to when a snapped window is dragged loose again. */
    private double unsnappedWidth;
    private double unsnappedHeight;
    private boolean snapped;

    private int resizeZone = ZONE_NONE;
    private boolean resizing;
    private double pressScreenX;
    private double pressScreenY;
    private double pressX;
    private double pressY;
    private double pressWidth;
    private double pressHeight;

    private WindowChrome(Stage stage, String version) {
        this.stage = stage;
        this.version = version;
    }

    /**
     * Builds the scene root: the custom bar above {@code content}. The bar deliberately sits
     * outside the content, so the in-app modal overlays dim the app without covering the window
     * controls and trapping the window.
     */
    public static Region install(Stage stage, Region content, String version) {
        WindowChrome chrome = new WindowChrome(stage, version);
        VBox root = new VBox(chrome.buildBar(), content);
        root.getStyleClass().add("window-shell");
        VBox.setVgrow(content, Priority.ALWAYS);
        chrome.bindMaximizedClass(root);
        chrome.installResizeHandlers(root);
        return root;
    }

    /** A maximised window has no edge to show, so the outer border comes off with it. */
    private void bindMaximizedClass(Region root) {
        stage.maximizedProperty().addListener((obs, wasMaximized, isMaximized) ->
                setMaximizedClass(root, isMaximized));
        setMaximizedClass(root, stage.isMaximized());
    }

    private static void setMaximizedClass(Region root, boolean maximized) {
        root.getStyleClass().remove("window-shell-maximized");
        if (maximized) {
            root.getStyleClass().add("window-shell-maximized");
        }
    }

    private HBox buildBar() {
        titleLabel.getStyleClass().add("window-title");
        titleLabel.textProperty().bind(stage.titleProperty());
        Label versionLabel = new Label(version);
        versionLabel.getStyleClass().add("window-version");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox dragArea = new HBox(8);
        Node appIcon = appIconView();
        if (appIcon != null) {
            dragArea.getChildren().add(appIcon);
        }
        dragArea.getChildren().addAll(titleLabel, versionLabel, spacer);
        dragArea.getStyleClass().add("window-drag-area");
        dragArea.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(dragArea, Priority.ALWAYS);
        installDragHandlers(dragArea);

        Button minimizeButton = captionButton(Icons.windowMinimize(), "Minimise",
                () -> stage.setIconified(true));
        maximizeButton.setGraphic(Icons.windowMaximize());
        maximizeButton.getStyleClass().addAll("window-button", "window-button-maximize");
        maximizeButton.setTooltip(new Tooltip("Maximise"));
        maximizeButton.setFocusTraversable(false);
        maximizeButton.setOnAction(event -> toggleMaximized());
        stage.maximizedProperty().addListener((obs, wasMaximized, isMaximized) ->
                updateMaximizeButton(isMaximized));
        updateMaximizeButton(stage.isMaximized());
        // the close button goes through the stage so close-to-tray still decides what happens
        Node closeGlyph = Icons.windowClose();
        Button closeButton = captionButton(closeGlyph, "Close",
                () -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        closeButton.getStyleClass().add("window-button-close");
        // Icons carry their fill as an inline style, which a stylesheet cannot override, so the
        // cross is repainted here to stay legible against the red hover
        closeButton.hoverProperty().addListener((obs, wasHovered, isHovered) -> closeGlyph.setStyle(
                "-fx-background-color: " + (isHovered ? "white" : "-fx-text-background-color") + ";"));

        HBox buttons = new HBox(minimizeButton, maximizeButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        captionButtons = buttons;

        HBox bar = new HBox(dragArea, buttons);
        bar.getStyleClass().add("window-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMinHeight(BAR_HEIGHT);
        bar.setPrefHeight(BAR_HEIGHT);
        bar.setMaxHeight(BAR_HEIGHT);
        return bar;
    }

    /**
     * The window's own icon at caption size. Picks the smallest icon that is at least 32 px so it
     * still looks sharp on a scaled display, and falls back to the largest one there is.
     */
    private Node appIconView() {
        Image smallestSharp = null;
        Image largest = null;
        for (Image icon : stage.getIcons()) {
            if (icon.getWidth() <= 0) {
                continue;
            }
            if (largest == null || icon.getWidth() > largest.getWidth()) {
                largest = icon;
            }
            if (icon.getWidth() >= ICON_SIZE * 2
                    && (smallestSharp == null || icon.getWidth() < smallestSharp.getWidth())) {
                smallestSharp = icon;
            }
        }
        Image best = smallestSharp != null ? smallestSharp : largest;
        if (best == null) {
            return null;
        }
        ImageView view = new ImageView(best);
        view.setFitWidth(ICON_SIZE);
        view.setFitHeight(ICON_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    private Button captionButton(Node graphic, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(graphic);
        button.getStyleClass().add("window-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void updateMaximizeButton(boolean maximized) {
        maximizeButton.setGraphic(maximized ? Icons.windowRestore() : Icons.windowMaximize());
        maximizeButton.setTooltip(new Tooltip(maximized ? "Restore" : "Maximise"));
    }

    private void toggleMaximized() {
        if (stage.isMaximized()) {
            stage.setMaximized(false);
        } else {
            rememberUnsnapped();
            stage.setMaximized(true);
        }
        snapped = false;
    }

    // ------------------------------------------------------------------ moving

    private void installDragHandlers(Region dragArea) {
        dragArea.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            dragging = true;
            dragMoved = false;
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });
        dragArea.setOnMouseDragged(event -> {
            if (!dragging) {
                return;
            }
            dragMoved = true;
            if (stage.isMaximized() || snapped) {
                restoreUnderCursor(event);
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
        dragArea.setOnMouseReleased(event -> {
            if (dragging && dragMoved) {
                snapToEdge(event.getScreenX(), event.getScreenY());
            }
            dragging = false;
        });
        dragArea.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !dragMoved) {
                toggleMaximized();
            }
        });
    }

    /**
     * Drags a maximised or snapped window loose. The window comes back to its old size under the
     * pointer rather than jumping away from it, by keeping the grab point at the same fraction
     * across the title bar.
     */
    private void restoreUnderCursor(MouseEvent event) {
        double grabRatio = stage.getWidth() > 0
                ? (event.getScreenX() - stage.getX()) / stage.getWidth() : 0.5;
        if (stage.isMaximized()) {
            stage.setMaximized(false);
        } else {
            applyUnsnappedSize();
        }
        snapped = false;
        dragOffsetX = grabRatio * stage.getWidth();
        dragOffsetY = Math.min(event.getY(), BAR_HEIGHT);
    }

    private void rememberUnsnapped() {
        if (!stage.isMaximized() && !snapped) {
            unsnappedWidth = stage.getWidth();
            unsnappedHeight = stage.getHeight();
        }
    }

    private void applyUnsnappedSize() {
        if (unsnappedWidth > 0 && unsnappedHeight > 0) {
            stage.setWidth(unsnappedWidth);
            stage.setHeight(unsnappedHeight);
        }
    }

    /**
     * Stands in for the part of Aero Snap that an undecorated window loses: dropped against the
     * top of a screen the window maximises, and against the left or right edge it takes that half.
     */
    private void snapToEdge(double screenX, double screenY) {
        Rectangle2D bounds = visualBoundsAt(screenX, screenY);
        if (bounds == null) {
            return;
        }
        if (screenY <= bounds.getMinY() + SNAP_MARGIN) {
            rememberUnsnapped();
            stage.setMaximized(true);
            snapped = false;
        } else if (screenX <= bounds.getMinX() + SNAP_MARGIN) {
            snapToHalf(bounds, bounds.getMinX());
        } else if (screenX >= bounds.getMaxX() - SNAP_MARGIN) {
            snapToHalf(bounds, bounds.getMinX() + bounds.getWidth() / 2);
        }
    }

    private void snapToHalf(Rectangle2D bounds, double x) {
        double width = Math.max(bounds.getWidth() / 2, stage.getMinWidth());
        double height = Math.max(bounds.getHeight(), stage.getMinHeight());
        rememberUnsnapped();
        stage.setX(x);
        stage.setY(bounds.getMinY());
        stage.setWidth(width);
        stage.setHeight(height);
        snapped = true;
    }

    private static Rectangle2D visualBoundsAt(double screenX, double screenY) {
        for (Screen screen : Screen.getScreens()) {
            if (screen.getBounds().contains(screenX, screenY)) {
                return screen.getVisualBounds();
            }
        }
        Screen primary = Screen.getPrimary();
        return primary != null ? primary.getVisualBounds() : null;
    }

    // ------------------------------------------------------------------ resizing

    /**
     * Resizing runs as event filters on the root, so the outer edge of the window wins over
     * whatever control happens to sit underneath it.
     */
    private void installResizeHandlers(Region root) {
        root.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            resizeZone = stage.isMaximized() || overCaptionButtons(event) ? ZONE_NONE
                    : zoneAt(event.getX(), event.getY(), root.getWidth(), root.getHeight());
            root.setCursor(cursorFor(resizeZone));
        });
        root.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (!resizing) {
                resizeZone = ZONE_NONE;
                root.setCursor(Cursor.DEFAULT);
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || resizeZone == ZONE_NONE) {
                return;
            }
            resizing = true;
            pressScreenX = event.getScreenX();
            pressScreenY = event.getScreenY();
            pressX = stage.getX();
            pressY = stage.getY();
            pressWidth = stage.getWidth();
            pressHeight = stage.getHeight();
            event.consume();
        });
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!resizing) {
                return;
            }
            applyResize(event.getScreenX() - pressScreenX, event.getScreenY() - pressScreenY);
            event.consume();
        });
        root.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizing) {
                resizing = false;
                snapped = false;
                event.consume();
            }
        });
    }

    /**
     * The caption buttons sit in the top right corner, inside the resize edge. They win: a corner
     * that offers a click and then resizes instead is worse than losing one resize handle, and the
     * right edge just below the bar still resizes. This is what Windows apps with their own chrome
     * do too.
     */
    private boolean overCaptionButtons(MouseEvent event) {
        Node node = event.getTarget() instanceof Node ? (Node) event.getTarget() : null;
        while (node != null) {
            if (node == captionButtons) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void applyResize(double dx, double dy) {
        if ((resizeZone & ZONE_EAST) != 0) {
            stage.setWidth(clamp(pressWidth + dx, stage.getMinWidth(), stage.getMaxWidth()));
        } else if ((resizeZone & ZONE_WEST) != 0) {
            double width = clamp(pressWidth - dx, stage.getMinWidth(), stage.getMaxWidth());
            stage.setX(pressX + pressWidth - width);
            stage.setWidth(width);
        }
        if ((resizeZone & ZONE_SOUTH) != 0) {
            stage.setHeight(clamp(pressHeight + dy, stage.getMinHeight(), stage.getMaxHeight()));
        } else if ((resizeZone & ZONE_NORTH) != 0) {
            double height = clamp(pressHeight - dy, stage.getMinHeight(), stage.getMaxHeight());
            stage.setY(pressY + pressHeight - height);
            stage.setHeight(height);
        }
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Which edges of a window of this size the given point sits on, as a bitmask. */
    static int zoneAt(double x, double y, double width, double height) {
        int zone = ZONE_NONE;
        if (x >= 0 && x <= RESIZE_MARGIN) {
            zone |= ZONE_WEST;
        } else if (x >= width - RESIZE_MARGIN && x <= width) {
            zone |= ZONE_EAST;
        }
        if (y >= 0 && y <= RESIZE_MARGIN) {
            zone |= ZONE_NORTH;
        } else if (y >= height - RESIZE_MARGIN && y <= height) {
            zone |= ZONE_SOUTH;
        }
        return zone;
    }

    static Cursor cursorFor(int zone) {
        switch (zone) {
            case ZONE_NORTH: return Cursor.N_RESIZE;
            case ZONE_SOUTH: return Cursor.S_RESIZE;
            case ZONE_WEST: return Cursor.W_RESIZE;
            case ZONE_EAST: return Cursor.E_RESIZE;
            case ZONE_NORTH | ZONE_WEST: return Cursor.NW_RESIZE;
            case ZONE_NORTH | ZONE_EAST: return Cursor.NE_RESIZE;
            case ZONE_SOUTH | ZONE_WEST: return Cursor.SW_RESIZE;
            case ZONE_SOUTH | ZONE_EAST: return Cursor.SE_RESIZE;
            default: return Cursor.DEFAULT;
        }
    }

    /** Whether the app should draw its own title bar instead of letting Windows draw one. */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(
                System.getProperty("antennapod.desktop.customchrome", "true"));
    }
}
