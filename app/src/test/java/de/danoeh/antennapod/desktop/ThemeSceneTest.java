package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
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
        javafx.scene.layout.HBox transport = (javafx.scene.layout.HBox) panel.getChildren().get(1);
        org.junit.Assert.assertEquals(5, transport.getChildren().size());
        for (javafx.scene.Node node : transport.getChildren()) {
            javafx.scene.control.Button button = (javafx.scene.control.Button) node;
            assertTrue(button.getText().isEmpty());
            org.junit.Assert.assertNotNull(button.getGraphic());
            org.junit.Assert.assertNotNull(button.getTooltip());
            button.fire();
        }
        javafx.scene.layout.HBox progressRow = (javafx.scene.layout.HBox) panel.getChildren().get(2);
        javafx.scene.control.Slider progressSlider =
                (javafx.scene.control.Slider) progressRow.getChildren().get(1);
        org.junit.Assert.assertTrue(progressSlider.isDisable());
        manager.updateProgress(65000, 3600000);
        org.junit.Assert.assertFalse(progressSlider.isDisable());
        org.junit.Assert.assertEquals(65000.0, progressSlider.getValue(), 0.01);
        progressSlider.setValue(120000);
        progressSlider.getOnMousePressed().handle(null);
        progressSlider.getOnMouseReleased().handle(null);
        javafx.scene.layout.HBox options = (javafx.scene.layout.HBox) panel.getChildren().get(3);
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
        javafx.scene.layout.HBox footer = (javafx.scene.layout.HBox) panel.getChildren().get(4);
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
    }
}
