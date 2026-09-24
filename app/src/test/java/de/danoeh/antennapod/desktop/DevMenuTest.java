package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The dev-only helpers behind the More menu: shown for runs from source, never installed. */
public class DevMenuTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testRunningFromSourceIsADevBuild() {
        // tests run from plain class files, so no Implementation-Version is on the manifest
        assertTrue(DesktopApp.isDevBuild());
    }

    @Test
    public void testProjectDirFindsACheckout() throws Exception {
        File root = tempFolder.getRoot();
        assertNull(DesktopApp.projectDir(root));
        Files.writeString(new File(root, "settings.gradle").toPath(), "");
        assertEquals(root.getCanonicalFile(), DesktopApp.projectDir(root));
    }

    @Test
    public void testProjectDirFindsTheCheckoutAboveAModuleDir() throws Exception {
        // Gradle runs the app with the working directory set to the app module
        File root = tempFolder.getRoot();
        Files.writeString(new File(root, "settings.gradle").toPath(), "");
        File module = new File(root, "app");
        assertTrue(module.mkdir());
        assertEquals(root.getCanonicalFile(), DesktopApp.projectDir(module));
    }

    @Test
    public void testProjectDirAcceptsTheWrapperAlone() throws Exception {
        File root = tempFolder.getRoot();
        Files.writeString(new File(root, "gradlew.bat").toPath(), "");
        assertEquals(root.getCanonicalFile(), DesktopApp.projectDir(root));
    }

    @Test
    public void testProjectDirRejectsOtherFolders() {
        assertNull(DesktopApp.projectDir(new File(tempFolder.getRoot(), "nope")));
    }
}
