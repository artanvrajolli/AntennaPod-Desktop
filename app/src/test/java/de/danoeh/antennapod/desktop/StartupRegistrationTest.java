package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Only the pure parts: the tests never write the real Run key. */
public class StartupRegistrationTest {
    private static final String EXE = "C:\\Users\\me\\AppData\\Local\\AntennaPod-Desktop\\AntennaPod-Desktop.exe";

    @Test
    public void testCommandQuotesThePathAndStartsInTheTray() {
        assertEquals("\"" + EXE + "\" --minimized", StartupRegistration.commandFor(EXE));
    }

    @Test
    public void testValueMatchesOnlyThisExe() {
        assertTrue(StartupRegistration.pointsAt(StartupRegistration.commandFor(EXE), EXE));
        assertTrue("paths compare as Windows does", StartupRegistration.pointsAt(
                StartupRegistration.commandFor(EXE.toUpperCase()), EXE));
        assertTrue(StartupRegistration.pointsAt("C:\\apps\\ap.exe --minimized", "C:\\apps\\ap.exe"));
        assertFalse("a moved portable copy is not this one", StartupRegistration.pointsAt(
                StartupRegistration.commandFor("D:\\stick\\AntennaPod-Desktop.exe"), EXE));
        assertFalse(StartupRegistration.pointsAt(null, EXE));
        assertFalse(StartupRegistration.pointsAt("\"", EXE));
    }
}
