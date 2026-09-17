package de.danoeh.antennapod.desktop;

import java.awt.AWTException;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

        void onShow();

        void onExit();
    }

    private volatile TrayIcon trayIcon;
    private Stage controlsWindow;
    private Button playPauseButton;
    private Label nowPlayingLabel;
    private BufferedImage defaultIcon;
    private java.awt.Image currentIcon;
    private Image pendingArtwork;

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

    VBox buildControls(Callbacks callbacks) {
        nowPlayingLabel = new Label("Nothing playing");
        nowPlayingLabel.setWrapText(true);
        nowPlayingLabel.setMaxWidth(260);
        playPauseButton = control(Icons.play(), "Play", callbacks::onPlayPause);
        HBox transport = new HBox(8,
                control(Icons.previous(), "Previous episode", callbacks::onPrevious),
                control(Icons.replay10(), "Skip back", callbacks::onSkipBack),
                playPauseButton,
                control(Icons.forward30(), "Skip forward", callbacks::onSkipForward),
                control(Icons.next(), "Next episode", callbacks::onNext));
        transport.setAlignment(Pos.CENTER);
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
        HBox actions = new HBox(8, show, exit);
        actions.setAlignment(Pos.CENTER);
        VBox panel = new VBox(12, nowPlayingLabel, transport, actions);
        panel.setPadding(new Insets(14));
        panel.setPrefWidth(288);
        return panel;
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
        playPauseButton.setGraphic(playing ? Icons.pause() : Icons.play());
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
        applyArtwork(artwork);
    }

    public void remove() {
        hideControls();
        TrayIcon removed = trayIcon;
        trayIcon = null;
        pendingArtwork = null;
        if (removed != null) {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(removed));
        }
    }

    private void applyArtwork(Image artwork) {
        if (artwork == null || artwork.isError()) {
            setIcon(defaultIcon);
            return;
        }
        if (artwork.getProgress() < 1) {
            setIcon(defaultIcon);
            if (artwork != pendingArtwork) {
                pendingArtwork = artwork;
                artwork.progressProperty().addListener((obs, oldProgress, progress) -> {
                    if (progress.doubleValue() >= 1 && !artwork.isError()) {
                        setIcon(artworkIcon(artwork));
                    }
                });
            }
            return;
        }
        setIcon(artworkIcon(artwork));
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

    private BufferedImage artworkIcon(Image artwork) {
        BufferedImage source = toBufferedImage(artwork);
        if (source == null) {
            return defaultIcon;
        }
        Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
        return toTrayIcon(source, Math.max(traySize.width, 16), Math.max(traySize.height, 16));
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
