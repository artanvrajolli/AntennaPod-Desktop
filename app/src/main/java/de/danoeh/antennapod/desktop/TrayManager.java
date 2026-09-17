package de.danoeh.antennapod.desktop;

import java.awt.AWTException;
import java.awt.Font;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import javafx.application.Platform;

public final class TrayManager {
    public interface Callbacks {
        void onPlayPause();

        void onNext();

        void onShow();

        void onExit();
    }

    private TrayIcon trayIcon;
    private MenuItem playPauseItem;

    public boolean init(Callbacks callbacks) {
        if (!SystemTray.isSupported()) {
            return false;
        }
        PopupMenu menu = new PopupMenu();
        playPauseItem = new MenuItem("Play/Pause");
        playPauseItem.addActionListener(fx(callbacks::onPlayPause));
        MenuItem nextItem = new MenuItem("Next episode");
        nextItem.addActionListener(fx(callbacks::onNext));
        MenuItem showItem = new MenuItem("Show AntennaPod");
        showItem.addActionListener(fx(callbacks::onShow));
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(fx(callbacks::onExit));
        menu.add(playPauseItem);
        menu.add(nextItem);
        menu.addSeparator();
        menu.add(showItem);
        menu.add(exitItem);
        trayIcon = new TrayIcon(createIcon(), "AntennaPod Desktop", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(fx(callbacks::onShow));
        try {
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void update(boolean playing, String nowPlaying) {
        if (trayIcon == null) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            playPauseItem.setLabel(playing ? "Pause" : "Play");
            String tooltip = nowPlaying != null && !nowPlaying.isEmpty()
                    ? (playing ? "Playing: " : "Paused: ") + nowPlaying
                    : "AntennaPod Desktop";
            if (tooltip.length() > 120) {
                tooltip = tooltip.substring(0, 117) + "…";
            }
            trayIcon.setToolTip(tooltip);
        });
    }

    public void remove() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    private static ActionListener fx(Runnable action) {
        return event -> Platform.runLater(action);
    }

    private static BufferedImage createIcon() {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
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
