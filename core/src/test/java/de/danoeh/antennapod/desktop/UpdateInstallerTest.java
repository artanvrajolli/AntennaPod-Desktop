package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.Test;

public class UpdateInstallerTest {
    private static final File INSTALLER = new File("C:\\Users\\A\\cache\\updates\\Setup-0.5.0.exe");
    private static final File DIR = new File("C:\\Users\\A\\AppData\\Local\\AntennaPod-Desktop\\");
    private static final File EXE = new File(DIR, "AntennaPod-Desktop.exe");
    private static final File LOG = new File("C:\\Users\\A\\cache\\updates\\install.log");

    @Test
    public void quoteDoublesSingleQuotesAndLeavesTheRestLiteral() {
        assertEquals("'C:\\it''s $HOME `x'", UpdateInstaller.quote("C:\\it's $HOME `x"));
        assertEquals("'a\u2019\u2019b'", UpdateInstaller.quote("a\u2019b"));
    }

    @Test
    public void trailingSeparatorIsStrippedButADriveRootIsKept() {
        assertEquals("C:\\Apps\\AP", UpdateInstaller.stripTrailingSeparator("C:\\Apps\\AP\\"));
        assertEquals("C:\\", UpdateInstaller.stripTrailingSeparator("C:\\"));
    }

    @Test
    public void scriptWaitsForTheAppThenInstallsQuietlyIntoTheSameFolder() {
        String script = UpdateInstaller.helperScript(4242, INSTALLER, DIR, EXE, LOG);
        int wait = script.indexOf("Wait-Process -Id 4242");
        int install = script.indexOf("Start-Process -FilePath $installer -ArgumentList");
        assertTrue(wait >= 0);
        assertTrue("waits before installing", install > wait);
        assertTrue(script.contains("/qn /norestart INSTALLDIR=\"C:\\Users\\A\\AppData\\Local\\AntennaPod-Desktop\""));
        assertFalse("no backslash before msiexec's closing quote", script.contains("\\\" /l*v"));
        assertTrue(script.contains("$installer = '" + INSTALLER.getAbsolutePath() + "'"));
    }

    @Test
    public void successRestartsTheAppAndFailureOpensTheWizard() {
        String script = UpdateInstaller.helperScript(1, INSTALLER, DIR, EXE, LOG);
        assertTrue(script.contains("@(0,3010,1641) -contains $code"));
        assertTrue(script.contains("$exe = '" + EXE.getAbsolutePath() + "'"));
        assertTrue(script.contains("Start-Process -FilePath $exe"));
        int orElse = script.indexOf("} else {");
        assertTrue(script.indexOf("Start-Process -FilePath $installer\r\n", orElse) > orElse);
    }

    @Test
    public void noExeMeansNoRestart() {
        String script = UpdateInstaller.helperScript(1, INSTALLER, DIR, null, LOG);
        assertTrue(script.contains("$exe = $null"));
    }

    @Test
    public void helperRunsHiddenWithoutProfileOrPolicyPrompts() {
        List<String> command = UpdateInstaller.command(new File("C:\\t\\apply-update.ps1"));
        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains("-NoProfile"));
        assertTrue(command.contains("Bypass"));
        assertEquals("Hidden", command.get(command.indexOf("-WindowStyle") + 1));
        assertEquals(new File("C:\\t\\apply-update.ps1").getAbsolutePath(), command.get(command.size() - 1));
    }
}
