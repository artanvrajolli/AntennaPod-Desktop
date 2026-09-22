package de.danoeh.antennapod.desktop;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Priority;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

public final class TrayManager {
    public interface Callbacks {
        void onPlayPause();

        void onPrevious();

        void onNext();

        void onSkipBack();

        void onSkipForward();

        void onSeek(int positionMs);

        void onShow();

        void onExit();

        /** Whether silence skipping is on right now, so the tray can show its current state. */
        boolean isSilenceSkipping();

        void onSilenceSkipping(boolean enabled);
    }

    private volatile TrayIcon trayIcon;
    private Callbacks callbacks;
    private Stage controlsWindow;
    private CheckBox silenceToggle;
    private Button playPauseButton;
    private Label nowPlayingLabel;
    private Label positionLabel;
    private Slider progressSlider;
    private boolean traySeeking;
    private BufferedImage defaultIcon;
    private java.awt.Image currentIcon;
    private Image pendingArtwork;
    /** The artwork of the latest update; a load that finishes later only counts if it is this one. */
    private volatile Image wantedArtwork;
    /** The tray icon without the progress fill, rebuilt when the artwork changes. */
    private BufferedImage iconBase;
    /** Last position/duration reported, so the icon fill can follow playback. */
    private int lastPositionMs;
    private int lastDurationMs;
    /** Progress fraction already drawn, so the icon is only rebuilt on visible movement. */
    private double paintedProgress = -1;
    /** The blue the played run is drawn in, matching the placeholder icon. */
    private static final Color PROGRESS_FILL = new Color(0x1F, 0x6F, 0xEB);
    /** The dark run behind the fill and the unplayed remainder of the bar. */
    private static final Color PROGRESS_REST = new Color(0x00, 0x00, 0x00, 170);

    public boolean init(Callbacks callbacks) {
        if (!SystemTray.isSupported()) {
            return false;
        }
        defaultIcon = createDefaultIcon();
        controlsWindow = new Stage(StageStyle.UNDECORATED);
        controlsWindow.setAlwaysOnTop(true);
        controlsWindow.setResizable(false);
        controlsWindow.setScene(new Scene(buildControls(callbacks)));
        ThemeManager.style(controlsWindow.getScene());
        controlsWindow.focusedProperty().addListener((obs, previous, focused) -> {
            if (!focused) {
                controlsWindow.hide();
            }
        });
        controlsWindow.getScene().setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                controlsWindow.hide();
            }
        });
        trayIcon = new TrayIcon(defaultIcon, "AntennaPod Desktop");
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON3) {
                    Platform.runLater(TrayManager.this::showControls);
                }
            }
        });
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(fx(callbacks::onShow));
        try {
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException e) {
            remove();
            return false;
        }
    }

    /**
     * A balloon from the tray icon. Used once, to say where the window went the first time
     * closing it leaves the app running rather than quitting it.
     */
    public void notify(String caption, String text) {
        TrayIcon icon = trayIcon;
        if (icon == null) {
            return;
        }
        try {
            icon.displayMessage(caption, text, TrayIcon.MessageType.INFO);
        } catch (Exception e) {
            // a notification is a nicety; the tray icon itself is the signpost that matters
        }
    }

    VBox buildControls(Callbacks callbacks) {
        this.callbacks = callbacks;
        nowPlayingLabel = new Label("Nothing playing");
        nowPlayingLabel.setWrapText(true);
        nowPlayingLabel.setMaxWidth(260);
        playPauseButton = control(Icons.accent(Icons.play()), "Play", callbacks::onPlayPause);
        HBox transport = new HBox(8,
                control(Icons.previous(), "Previous episode", callbacks::onPrevious),
                control(Icons.replay10(), "Skip back", callbacks::onSkipBack),
                playPauseButton,
                control(Icons.forward30(), "Skip forward", callbacks::onSkipForward),
                control(Icons.next(), "Next episode", callbacks::onNext));
        transport.setAlignment(Pos.CENTER);
        positionLabel = new Label("");
        positionLabel.getStyleClass().add("muted-label");
        progressSlider = new Slider(0, 1, 0);
        progressSlider.setMaxWidth(Double.MAX_VALUE);
        progressSlider.setDisable(true);
        progressSlider.setOnMousePressed(event -> traySeeking = true);
        progressSlider.setOnMouseReleased(event -> {
            if (traySeeking) {
                traySeeking = false;
                callbacks.onSeek((int) progressSlider.getValue());
            }
        });
        HBox progressRow = new HBox(6, positionLabel, progressSlider);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressSlider, Priority.ALWAYS);
        Button show = new Button("Show AntennaPod");
        show.setOnAction(event -> {
            hideControls();
            callbacks.onShow();
        });
        Button exit = new Button("Exit");
        exit.setOnAction(event -> {
            hideControls();
            callbacks.onExit();
        });
        silenceToggle = new CheckBox("Skip silence");
        silenceToggle.setSelected(callbacks.isSilenceSkipping());
        silenceToggle.setTooltip(new Tooltip("Play through quiet passages faster"));
        silenceToggle.setOnAction(event ->
                callbacks.onSilenceSkipping(silenceToggle.isSelected()));
        HBox options = new HBox(8, silenceToggle);
        options.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, show, exit);
        actions.setAlignment(Pos.CENTER);
        VBox panel = new VBox(12, nowPlayingLabel, transport, progressRow, options, actions);
        panel.setPadding(new Insets(14));
        panel.setPrefWidth(288);
        return panel;
    }

    /** Keeps the tray's own toggle in step when silence skipping is switched elsewhere. */
    public void updateSilenceSkipping(boolean enabled) {
        if (silenceToggle != null) {
            silenceToggle.setSelected(enabled);
        }
    }

    void updateProgress(int positionMs, int durationMs) {
        if (progressSlider == null) {
            return;
        }
        if (durationMs <= 0) {
            progressSlider.setDisable(true);
            progressSlider.setValue(0);
            positionLabel.setText("");
            lastPositionMs = 0;
            lastDurationMs = 0;
            repaintTrayIcon();
            return;
        }
        progressSlider.setDisable(false);
        progressSlider.setMax(durationMs);
        if (!traySeeking) {
            progressSlider.setValue(Math.min(positionMs, durationMs));
        }
        positionLabel.setText(formatTime(positionMs) + " / " + formatTime(durationMs));
        lastPositionMs = Math.max(positionMs, 0);
        lastDurationMs = durationMs;
        repaintTrayIcon();
    }

    private static String formatTime(int millis) {
        int totalSeconds = Math.max(millis / 1000, 0);
        long minutes = totalSeconds / 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, totalSeconds % 60);
    }

    private static Button control(javafx.scene.Node icon, String label, Runnable action) {
        Button button = new Button("", icon);
        button.getStyleClass().add("icon-button");
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(label));
        button.setMinSize(36, 32);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void hideControls() {
        if (controlsWindow != null) {
            controlsWindow.hide();
        }
    }

    private void showControls() {
        if (trayIcon == null || controlsWindow == null) {
            return;
        }
        if (controlsWindow.isShowing()) {
            hideControls();
            return;
        }
        if (silenceToggle != null && callbacks != null) {
            // read it on the way up: it can have been switched in the window since the last look
            silenceToggle.setSelected(callbacks.isSilenceSkipping());
        }
        Point2D pointer = new javafx.scene.robot.Robot().getMousePosition();
        Rectangle2D bounds = Screen.getScreensForRectangle(pointer.getX(), pointer.getY(), 1, 1)
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        controlsWindow.show();
        controlsWindow.sizeToScene();
        controlsWindow.setX(Math.max(bounds.getMinX(), Math.min(pointer.getX() - controlsWindow.getWidth(),
                bounds.getMaxX() - controlsWindow.getWidth())));
        controlsWindow.setY(Math.max(bounds.getMinY(), Math.min(pointer.getY() - controlsWindow.getHeight(),
                bounds.getMaxY() - controlsWindow.getHeight())));
        controlsWindow.requestFocus();
    }

    public void update(boolean playing, String nowPlaying, Image artwork) {
        if (trayIcon == null) {
            return;
        }
        boolean hasEpisode = nowPlaying != null && !nowPlaying.isEmpty();
        playPauseButton.setGraphic(Icons.accent(playing ? Icons.pause() : Icons.play()));
        playPauseButton.setAccessibleText(playing ? "Pause" : "Play");
        playPauseButton.getTooltip().setText(playing ? "Pause" : "Play");
        nowPlayingLabel.setText(hasEpisode ? truncate(nowPlaying, 100) : "Nothing playing");
        EventQueue.invokeLater(() -> {
            if (trayIcon == null) {
                return;
            }
            String tooltip = hasEpisode
                    ? (playing ? "Playing: " : "Paused: ") + nowPlaying
                    : "AntennaPod Desktop";
            trayIcon.setToolTip(truncate(tooltip, 120));
        });
        wantedArtwork = artwork;
        applyArtwork(artwork);
    }

    public void remove() {
        hideControls();
        TrayIcon removed = trayIcon;
        trayIcon = null;
        pendingArtwork = null;
        if (removed == null) {
            return;
        }
        if (EventQueue.isDispatchThread()) {
            SystemTray.getSystemTray().remove(removed);
            return;
        }
        try {
            EventQueue.invokeAndWait(() -> SystemTray.getSystemTray().remove(removed));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            // the icon is already gone
        }
    }

    private void applyArtwork(Image artwork) {
        if (artwork == null || artwork.isError() || artwork.getProgress() < 1) {
            iconBase = defaultIcon;
            lastDurationMs = 0;
            paintedProgress = -1;
            setIcon(defaultIcon);
            if (artwork != null && !artwork.isError() && artwork.getProgress() < 1
                    && artwork != pendingArtwork) {
                pendingArtwork = artwork;
                artwork.progressProperty().addListener((obs, oldProgress, progress) -> {
                    // the episode may have changed while this loaded; its art must not come back
                    if (progress.doubleValue() >= 1 && !artwork.isError() && artwork == wantedArtwork) {
                        applyArtwork(artwork);
                    }
                });
            }
            return;
        }
        iconBase = composeBase(artwork);
        paintedProgress = -1;
        repaintTrayIcon();
    }

    /**
     * Draws the played fraction as a filled bar along the bottom of the tray icon, so progress
     * stays visible even when the controls window is closed.
     */
    private void repaintTrayIcon() {
        BufferedImage base = iconBase;
        if (trayIcon == null || base == null) {
            return;
        }
        if (lastDurationMs <= 0) {
            paintedProgress = -1;
            setIcon(base);
            return;
        }
        double fraction = Math.max(0, Math.min(1, lastPositionMs / (double) lastDurationMs));
        if (Math.abs(fraction - paintedProgress) < 0.02) {
            // position ticks arrive many times a second; only rebuild on visible movement
            return;
        }
        paintedProgress = fraction;
        setIcon(withProgress(base, fraction));
    }

    static BufferedImage withProgress(BufferedImage base, double fraction) {
        int width = base.getWidth();
        int height = base.getHeight();
        BufferedImage icon = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.drawImage(base, 0, 0, null);
        int barHeight = Math.max(2, height / 6);
        int barY = height - barHeight;
        g.setColor(PROGRESS_REST);
        g.fillRect(0, barY, width, barHeight);
        g.setColor(PROGRESS_FILL);
        g.fillRect(0, barY, (int) Math.round(width * fraction), barHeight);
        g.dispose();
        return icon;
    }

    private void setIcon(java.awt.Image image) {
        if (image == currentIcon) {
            return;
        }
        currentIcon = image;
        EventQueue.invokeLater(() -> {
            if (trayIcon != null) {
                trayIcon.setImage(image);
            }
        });
    }

    private BufferedImage composeBase(Image artwork) {
        BufferedImage source = toBufferedImage(artwork);
        if (source == null) {
            return defaultIcon;
        }
        Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
        int width = Math.max(traySize.width, 16);
        int height = Math.max(traySize.height, 16);
        if (!TaskbarIcon.isEnabled()) {
            // the escape hatch: the artwork on its own, the way the tray drew it before
            return toTrayIcon(source, width, height);
        }
        // the same icon the taskbar button shows: the app icon with the artwork in the middle
        return TaskbarIcon.compose(TaskbarIcon.scale(defaultIcon, width, height), source);
    }

    static BufferedImage toTrayIcon(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new RoundRectangle2D.Float(0, 0, width, height, width / 3f, height / 3f));
        double scale = Math.min((double) width / source.getWidth(),
                (double) height / source.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        g.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2,
                drawWidth, drawHeight, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage toBufferedImage(Image image) {
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return null;
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return buffered;
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max - 3) + "…" : text;
    }

    private static ActionListener fx(Runnable action) {
        return event -> Platform.runLater(action);
    }

    private static BufferedImage createDefaultIcon() {
        try (java.io.InputStream stream =
                     TrayManager.class.getResourceAsStream("/icons/app-icon-32.png")) {
            if (stream != null) {
                BufferedImage loaded = javax.imageio.ImageIO.read(stream);
                if (loaded != null) {
                    return loaded;
                }
            }
        } catch (Exception e) {
            // fall through to the drawn placeholder
        }
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1F, 0x6F, 0xEB));
        g.fillRoundRect(0, 0, size, size, 8, 8);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        java.awt.FontMetrics metrics = g.getFontMetrics();
        String text = "AP";
        int x = (size - metrics.stringWidth(text)) / 2;
        int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(text, x, y);
        g.dispose();
        return image;
    }
}
