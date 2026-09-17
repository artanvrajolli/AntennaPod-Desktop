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
        javafx.scene.layout.HBox footer = (javafx.scene.layout.HBox) panel.getChildren().get(3);
        for (javafx.scene.Node node : footer.getChildren()) {
            ((javafx.scene.control.Button) node).fire();
        }
        org.junit.Assert.assertEquals(java.util.List.of("previous", "back", "play", "forward", "next",
                "seek:120000", "show", "exit"), actions);
        manager.remove();
        manager.remove();
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
