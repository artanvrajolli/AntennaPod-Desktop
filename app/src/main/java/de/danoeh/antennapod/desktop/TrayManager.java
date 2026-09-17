package de.danoeh.antennapod.desktop;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
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

    private TrayIcon trayIcon;
    private MenuItem playPauseItem;
    private MenuItem nowPlayingItem;
    private BufferedImage defaultIcon;
    private java.awt.Image currentIcon;
    private Image pendingArtwork;

    public boolean init(Callbacks callbacks) {
        if (!SystemTray.isSupported()) {
            return false;
        }
        defaultIcon = createDefaultIcon();
        PopupMenu menu = new PopupMenu();
        nowPlayingItem = new MenuItem("Nothing playing");
        nowPlayingItem.setEnabled(false);
        playPauseItem = new MenuItem("Play");
        playPauseItem.addActionListener(fx(callbacks::onPlayPause));
        MenuItem previousItem = new MenuItem("Previous episode");
        previousItem.addActionListener(fx(callbacks::onPrevious));
        MenuItem nextItem = new MenuItem("Next episode");
        nextItem.addActionListener(fx(callbacks::onNext));
        MenuItem skipBackItem = new MenuItem("Skip back");
        skipBackItem.addActionListener(fx(callbacks::onSkipBack));
        MenuItem skipForwardItem = new MenuItem("Skip forward");
        skipForwardItem.addActionListener(fx(callbacks::onSkipForward));
        MenuItem showItem = new MenuItem("Show AntennaPod");
        showItem.addActionListener(fx(callbacks::onShow));
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(fx(callbacks::onExit));
        menu.add(nowPlayingItem);
        menu.addSeparator();
        menu.add(previousItem);
        menu.add(playPauseItem);
        menu.add(nextItem);
        menu.addSeparator();
        menu.add(skipBackItem);
        menu.add(skipForwardItem);
        menu.addSeparator();
        menu.add(showItem);
        menu.add(exitItem);
        trayIcon = new TrayIcon(defaultIcon, "AntennaPod Desktop", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(fx(callbacks::onShow));
        try {
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException e) {
            return false;
        }
    }

    public void update(boolean playing, String nowPlaying, Image artwork) {
        if (trayIcon == null) {
            return;
        }
        boolean hasEpisode = nowPlaying != null && !nowPlaying.isEmpty();
        EventQueue.invokeLater(() -> {
            playPauseItem.setLabel(playing ? "Pause" : "Play");
            nowPlayingItem.setLabel(hasEpisode ? truncate(nowPlaying, 70) : "Nothing playing");
            String tooltip = hasEpisode
                    ? (playing ? "Playing: " : "Paused: ") + nowPlaying
                    : "AntennaPod Desktop";
            trayIcon.setToolTip(truncate(tooltip, 120));
        });
        applyArtwork(artwork);
    }

    public void remove() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
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
