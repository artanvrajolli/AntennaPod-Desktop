package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.jna.Pointer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class ThemeSceneTest {
    @BeforeClass
    public static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assume.assumeTrue("JavaFX toolkit did not start", started.await(10, TimeUnit.SECONDS));
        } catch (Throwable t) {
            Assume.assumeNoException("JavaFX toolkit unavailable", t);
        }
    }

    @AfterClass
    public static void stopToolkit() {
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            return;
        }
    }

    private static void verifyTrayControls() {
        java.util.List<String> actions = new java.util.ArrayList<>();
        boolean[] silenceSkipping = {false};
        TrayManager manager = new TrayManager();
        javafx.scene.layout.VBox panel = manager.buildControls(new TrayManager.Callbacks() {
            public void onPlayPause() { actions.add("play"); }
            public void onPrevious() { actions.add("previous"); }
            public void onNext() { actions.add("next"); }
            public void onSkipBack() { actions.add("back"); }
            public void onSkipForward() { actions.add("forward"); }
            public void onSeek(int positionMs) { actions.add("seek:" + positionMs); }
            public void onShow() { actions.add("show"); }
            public void onExit() { actions.add("exit"); }
            public boolean isSilenceSkipping() { return silenceSkipping[0]; }
            public void onSilenceSkipping(boolean enabled) {
                silenceSkipping[0] = enabled;
                actions.add("silence:" + enabled);
            }
        });
        // header (artwork + titles), transport, progress — each behind a separator
        javafx.scene.layout.HBox header = (javafx.scene.layout.HBox) panel.getChildren().get(0);
        org.junit.Assert.assertEquals(2, header.getChildren().size());
        javafx.scene.layout.VBox titles =
                (javafx.scene.layout.VBox) header.getChildren().get(1);
        org.junit.Assert.assertEquals("Nothing playing",
                ((javafx.scene.control.Label) titles.getChildren().get(0)).getText());
        org.junit.Assert.assertTrue(panel.getChildren().get(1)
                instanceof javafx.scene.control.Separator);
        javafx.scene.layout.HBox transport = (javafx.scene.layout.HBox) panel.getChildren().get(2);
        org.junit.Assert.assertEquals(5, transport.getChildren().size());
        // nothing loaded yet: the transport row stays disabled with the player idle
        assertTrue(transport.isDisable());
        transport.setDisable(false);
        for (javafx.scene.Node node : transport.getChildren()) {
            javafx.scene.control.Button button = (javafx.scene.control.Button) node;
            assertTrue(button.getText().isEmpty());
            org.junit.Assert.assertNotNull(button.getGraphic());
            org.junit.Assert.assertNotNull(button.getTooltip());
            button.fire();
        }
        javafx.scene.layout.HBox progressRow = (javafx.scene.layout.HBox) panel.getChildren().get(3);
        javafx.scene.control.Slider progressSlider =
                (javafx.scene.control.Slider) progressRow.getChildren().get(1);
        org.junit.Assert.assertTrue(progressSlider.isDisable());
        manager.updateProgress(65000, 3600000);
        org.junit.Assert.assertFalse(progressSlider.isDisable());
        org.junit.Assert.assertEquals(65000.0, progressSlider.getValue(), 0.01);
        progressSlider.setValue(120000);
        progressSlider.getOnMousePressed().handle(null);
        progressSlider.getOnMouseReleased().handle(null);
        org.junit.Assert.assertTrue(panel.getChildren().get(4)
                instanceof javafx.scene.control.Separator);
        javafx.scene.layout.HBox options = (javafx.scene.layout.HBox) panel.getChildren().get(5);
        javafx.scene.control.CheckBox silence =
                (javafx.scene.control.CheckBox) options.getChildren().get(0);
        org.junit.Assert.assertEquals("Skip silence", silence.getText());
        assertFalse("the tray has to open showing the state it really is in", silence.isSelected());
        silence.fire();
        assertTrue(silence.isSelected());
        assertTrue(silenceSkipping[0]);
        // switched in the window instead: the tray toggle has to follow
        manager.updateSilenceSkipping(false);
        assertFalse(silence.isSelected());
        javafx.scene.layout.HBox footer = (javafx.scene.layout.HBox) panel.getChildren().get(6);
        for (javafx.scene.Node node : footer.getChildren()) {
            ((javafx.scene.control.Button) node).fire();
        }
        org.junit.Assert.assertEquals(java.util.List.of("previous", "back", "play", "forward", "next",
                "seek:120000", "silence:true", "show", "exit"), actions);
        manager.remove();
        manager.remove();
    }

    private static Label labelWithClass(javafx.scene.Parent parent, String styleClass) {
        for (javafx.scene.Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Label && node.getStyleClass().contains(styleClass)) {
                return (Label) node;
            }
        }
        throw new AssertionError("no label with style class " + styleClass);
    }

    /**
     * The custom title bar, checked in this class because it needs the same toolkit session: the
     * JavaFX toolkit can only be started once per JVM.
     */
    private static void verifyWindowChrome() {
        Stage stage = new Stage();
        try {
            stage.setTitle("Chrome test");
            stage.setMinWidth(400);
            stage.setMinHeight(300);
            stage.getIcons().add(new javafx.scene.image.WritableImage(32, 32));
            javafx.scene.layout.Region root =
                    WindowChrome.install(stage, new StackPane(new Label("content")), "9.9.9");
            assertTrue(root.getStyleClass().contains("window-shell"));

            javafx.scene.layout.HBox bar =
                    (javafx.scene.layout.HBox) ((javafx.scene.layout.VBox) root).getChildren().get(0);
            assertTrue(bar.getStyleClass().contains("window-bar"));
            org.junit.Assert.assertEquals(WindowChrome.BAR_HEIGHT, bar.getPrefHeight(), 0.01);

            javafx.scene.layout.HBox dragArea = (javafx.scene.layout.HBox) bar.getChildren().get(0);
            // the window's own icon leads the bar, so the name is not simply the first child
            assertTrue(dragArea.getChildren().get(0) instanceof javafx.scene.image.ImageView);
            Label title = labelWithClass(dragArea, "window-title");
            org.junit.Assert.assertEquals("Chrome test", title.getText());
            stage.setTitle("Renamed");
            org.junit.Assert.assertEquals("Renamed", title.getText());
            org.junit.Assert.assertEquals("9.9.9",
                    labelWithClass(dragArea, "window-version").getText());

            javafx.scene.layout.HBox buttons = (javafx.scene.layout.HBox) bar.getChildren().get(1);
            org.junit.Assert.assertEquals(3, buttons.getChildren().size());
            for (javafx.scene.Node node : buttons.getChildren()) {
                javafx.scene.control.Button button = (javafx.scene.control.Button) node;
                assertTrue(button.getStyleClass().contains("window-button"));
                org.junit.Assert.assertNotNull(button.getGraphic());
                org.junit.Assert.assertNotNull(button.getTooltip());
                assertFalse(button.isFocusTraversable());
            }

            javafx.scene.control.Button minimize =
                    (javafx.scene.control.Button) buttons.getChildren().get(0);
            javafx.scene.control.Button maximize =
                    (javafx.scene.control.Button) buttons.getChildren().get(1);
            javafx.scene.control.Button close =
                    (javafx.scene.control.Button) buttons.getChildren().get(2);

            javafx.scene.Node restoredIcon = maximize.getGraphic();
            maximize.fire();
            assertTrue(stage.isMaximized());
            assertTrue("the glyph should switch to restore", maximize.getGraphic() != restoredIcon);
            assertTrue(root.getStyleClass().contains("window-shell-maximized"));
            maximize.fire();
            assertFalse(stage.isMaximized());
            assertFalse(root.getStyleClass().contains("window-shell-maximized"));

            minimize.fire();
            assertTrue(stage.isIconified());
            stage.setIconified(false);

            java.util.concurrent.atomic.AtomicInteger closeRequests =
                    new java.util.concurrent.atomic.AtomicInteger();
            // close must go through the stage, so close-to-tray keeps its say
            stage.setOnCloseRequest(event -> {
                closeRequests.incrementAndGet();
                event.consume();
            });
            close.fire();
            org.junit.Assert.assertEquals(1, closeRequests.get());
        } finally {
            stage.hide();
        }
    }

    /**
     * The media card through the same path the app uses at startup: a real window, the real
     * attach, then metadata read back through a fresh fetch for that window. Checked in this
     * class because it needs the same toolkit session.
     */
    private static void verifySmtcCard() throws Exception {
        CountDownLatch attached = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<SmtcManager> smtcRef = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Stage stage = new Stage();
                // undecorated, like the app's own custom-chrome window in production
                stage.initStyle(javafx.stage.StageStyle.UNDECORATED);
                stage.setTitle("smtc-app-test");
                stage.setScene(new Scene(new StackPane(new Label("smtc")), 200, 100));
                stage.show();
                stageRef.set(stage);
                SmtcManager smtc = new SmtcManager();
                smtcRef.set(smtc);
                smtc.attach(stage, new SmtcManager.Callbacks() {
                    public void onPlay() {
                    }

                    public void onPause() {
                    }

                    public void onStop() {
                    }

                    public void onNext() {
                    }

                    public void onPrevious() {
                    }

                    public void onSeek(int positionMs) {
                    }
                });
                smtc.setEpisode("Typical Story II", "Glum Aleks", "AntennaPod Desktop", null);
                smtc.setStatus(true, true);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                attached.countDown();
            }
        });
        assertTrue("Timed out showing the stage", attached.await(15, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        // off the FX thread: the card belongs to the window by now, read it back
        SmtcManager.ComBase.INSTANCE.RoInitialize(1);
        try {
            String title = "";
            int status = -1;
            long waited = 0;
            while (waited < 20_000 && (title.isEmpty() || status != SmtcManager.STATUS_PLAYING)) {
                Thread.sleep(200);
                waited += 200;
                title = readCardTitle();
                status = readCardStatus();
            }
            assertEquals("Typical Story II", title);
            assertEquals(SmtcManager.STATUS_PLAYING, status);
        } finally {
            SmtcManager.ComBase.INSTANCE.RoUninitialize();
            CountDownLatch hidden = new CountDownLatch(1);
            SmtcManager smtc = smtcRef.get();
            if (smtc != null) {
                smtc.shutdown();
            }
            Platform.runLater(() -> {
                if (stageRef.get() != null) {
                    stageRef.get().hide();
                }
                hidden.countDown();
            });
            hidden.await(10, TimeUnit.SECONDS);
        }
    }

    private static com.sun.jna.Pointer cardControls() {
        com.sun.jna.platform.win32.WinDef.HWND window =
                WindowsTaskbar.findWindow("smtc-app-test");
        org.junit.Assert.assertNotNull("test window not found", window);
        Pointer factory = SmtcManager.getActivationFactory(
                SmtcManager.CLASS_CONTROLS, SmtcManager.IID_INTEROP);
        org.junit.Assert.assertNotNull(factory);
        try {
            return SmtcManager.getForWindow(factory, window);
        } finally {
            SmtcManager.release(factory);
        }
    }

    private static String readCardTitle() {
        Pointer controls = cardControls();
        if (controls == null) {
            return "";
        }
        try {
            Pointer updater = new SmtcManager.Controls(controls).getDisplayUpdater();
            try {
                Pointer music = new SmtcManager.DisplayUpdater(updater).getMusicProperties();
                try {
                    return new SmtcManager.MusicProps(music).getTitle();
                } finally {
                    SmtcManager.release(music);
                }
            } finally {
                SmtcManager.release(updater);
            }
        } catch (Throwable t) {
            return "";
        } finally {
            SmtcManager.release(controls);
        }
    }

    private static int readCardStatus() {
        Pointer controls = cardControls();
        if (controls == null) {
            return -1;
        }
        try {
            return new SmtcManager.Controls(controls).getPlaybackStatus();
        } catch (Throwable t) {
            return -1;
        } finally {
            SmtcManager.release(controls);
        }
    }

    private static void verifySidebarSplitWidth() {
        double savedPosition = DesktopPreferences.getFeedSplitPosition();
        Stage stage = null;
        try {
            VBox feedPane = new VBox();
            feedPane.setMinWidth(180);
            VBox episodePane = new VBox();
            episodePane.setMinWidth(320);
            SplitPane split = new SplitPane(feedPane, episodePane);
            split.setDividerPositions(savedPosition);
            VBox sidebar = new VBox();
            sidebar.setPrefWidth(460);
            sidebar.setMinWidth(340);
            sidebar.setVisible(false);
            sidebar.setManaged(false);
            BorderPane root = new BorderPane();
            root.setCenter(split);
            root.setRight(sidebar);
            stage = new Stage();
            stage.setScene(new Scene(root, 1100, 700));
            stage.show();
            root.applyCss();
            root.layout();
            double originalWidth = feedPane.getWidth();

            sidebar.setVisible(true);
            sidebar.setManaged(true);
            root.applyCss();
            root.layout();
            DesktopApp.restoreFeedWidth(split, feedPane, episodePane, originalWidth);
            root.applyCss();
            root.layout();

            sidebar.setVisible(false);
            sidebar.setManaged(false);
            root.applyCss();
            root.layout();
            DesktopApp.restoreFeedWidth(split, feedPane, episodePane, originalWidth);
            root.applyCss();
            root.layout();

            assertEquals("sidebar toggle must restore the subscription width",
                    originalWidth, feedPane.getWidth(), 1.0);
            assertTrue("the episode pane must keep its minimum width",
                    episodePane.getWidth() >= episodePane.getMinWidth());
            assertEquals("layout corrections must not overwrite the saved divider",
                    savedPosition, DesktopPreferences.getFeedSplitPosition(), 0.001);
        } finally {
            if (stage != null) {
                stage.hide();
            }
            DesktopPreferences.setFeedSplitPosition(savedPosition);
        }
    }

    @Test
    public void testNewWindowsReceiveActiveTheme() throws Exception {
        String previousMode = DesktopPreferences.getThemeMode();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = null;
            try {
                verifyTrayControls();
                verifyWindowChrome();
                verifySidebarSplitWidth();
                DesktopPreferences.setThemeMode(ThemeManager.MODE_DARK);
                ThemeManager.init();
                stage = new Stage();
                stage.setScene(new Scene(new StackPane(new Label("theme test")), 80, 60));
                stage.show();
                assertTrue(stage.getScene().getStylesheets()
                        .contains(ThemeManager.DARK_STYLESHEET.toExternalForm()));
                DesktopPreferences.setThemeMode(ThemeManager.MODE_LIGHT);
                ThemeManager.applySavedMode();
                assertTrue(stage.getScene().getStylesheets()
                        .contains(ThemeManager.LIGHT_STYLESHEET.toExternalForm()));
                assertFalse(stage.getScene().getStylesheets()
                        .contains(ThemeManager.DARK_STYLESHEET.toExternalForm()));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                if (stage != null) {
                    stage.hide();
                }
                done.countDown();
            }
        });
        assertTrue("Timed out waiting for the JavaFX thread", done.await(15, TimeUnit.SECONDS));
        DesktopPreferences.setThemeMode(previousMode);
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win")) {
            verifySmtcCard();
        }
    }
}
