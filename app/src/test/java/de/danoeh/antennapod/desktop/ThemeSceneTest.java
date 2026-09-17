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

    @Test
    public void testNewWindowsReceiveActiveTheme() throws Exception {
        String previousMode = DesktopPreferences.getThemeMode();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = null;
            try {
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
