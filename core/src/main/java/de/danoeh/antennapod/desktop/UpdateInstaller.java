package de.danoeh.antennapod.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Installs a downloaded update without the setup wizard and starts the new version.
 *
 * <p>The setup exe jpackage builds forwards its arguments to {@code msiexec /i}, so {@code /qn}
 * runs the install with no UI. It cannot run while this process still holds the files it
 * replaces, so the work is handed to a small hidden PowerShell helper: it waits for this process
 * to exit, runs the installer into the folder the app is installed in, and starts the app again.
 * When the quiet install fails (for example because Windows wants to ask for something), the
 * helper opens the regular setup wizard instead, so the user always ends up with a way forward.
 */
public final class UpdateInstaller {
    /** msiexec exit codes that mean the product is installed (3010/1641: a reboot is pending). */
    static final List<Integer> SUCCESS_CODES = List.of(0, 3010, 1641);
    /** How long the helper waits for the app to quit before it installs anyway. */
    static final int WAIT_FOR_EXIT_SECONDS = 90;

    private UpdateInstaller() {
    }

    /**
     * The helper script. Every value is embedded as a single-quoted PowerShell literal, so paths
     * with spaces, quotes or {@code $} cannot break out of it.
     *
     * @param pid the app's process id, waited on before installing
     * @param installer the downloaded setup exe
     * @param installDir the folder the app is installed in; the update goes to the same place
     * @param exe the app's exe, started again once the install has finished; null to not restart
     * @param log where msiexec writes its verbose log
     */
    static String helperScript(long pid, File installer, File installDir, File exe, File log) {
        String dir = stripTrailingSeparator(installDir.getAbsolutePath());
        // msiexec reads its own command line: the folder goes in double quotes, and a trailing
        // backslash before the closing quote would escape it, hence the stripped separator
        String msiArgs = "/qn /norestart INSTALLDIR=\"" + dir + "\" /l*v \"" + log.getAbsolutePath() + "\"";
        StringBuilder s = new StringBuilder();
        s.append("$ErrorActionPreference = 'Continue'\r\n");
        s.append("$installer = ").append(quote(installer.getAbsolutePath())).append("\r\n");
        s.append("$exe = ").append(exe == null ? "$null" : quote(exe.getAbsolutePath())).append("\r\n");
        s.append("try { Wait-Process -Id ").append(pid).append(" -Timeout ").append(WAIT_FOR_EXIT_SECONDS)
                .append(" -ErrorAction Stop } catch { }\r\n");
        // the JVM can outlive its last window by a moment while file handles close
        s.append("Start-Sleep -Milliseconds 700\r\n");
        s.append("$code = -1\r\n");
        s.append("try {\r\n");
        s.append("  $p = Start-Process -FilePath $installer -ArgumentList ").append(quote(msiArgs))
                .append(" -PassThru -WindowStyle Hidden\r\n");
        // reading Handle keeps it open, without which ExitCode comes back empty
        s.append("  $null = $p.Handle\r\n");
        s.append("  $p.WaitForExit()\r\n");
        s.append("  $code = $p.ExitCode\r\n");
        s.append("} catch { }\r\n");
        s.append("if (@(").append(joinCodes()).append(") -contains $code) {\r\n");
        s.append("  if ($exe -and (Test-Path -LiteralPath $exe)) { Start-Process -FilePath $exe }\r\n");
        s.append("} else {\r\n");
        // the wizard shows what went wrong and lets the user finish by hand
        s.append("  Start-Process -FilePath $installer\r\n");
        s.append("}\r\n");
        s.append("Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -ErrorAction SilentlyContinue\r\n");
        return s.toString();
    }

    /**
     * Writes the helper next to the installer and starts it, hidden and detached. The caller
     * quits right after; the helper takes it from there.
     */
    public static void launch(File installer, File installDir, File exe) throws IOException {
        File dir = installer.getAbsoluteFile().getParentFile();
        File script = new File(dir, "apply-update.ps1");
        File log = new File(dir, "install.log");
        String body = helperScript(ProcessHandle.current().pid(), installer, installDir, exe, log);
        // a BOM makes Windows PowerShell 5.1 read the file as UTF-8, so non-ASCII paths survive
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(text, 0, bytes, bom.length, text.length);
        Files.write(script.toPath(), bytes);
        new ProcessBuilder(command(script))
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    static List<String> command(File script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.getAbsolutePath());
    }

    /**
     * A PowerShell single-quoted literal: nothing inside is expanded, a quote is doubled.
     * PowerShell also takes the typographic single quotes as quote marks, so those double too.
     */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("'");
        for (char c : value.toCharArray()) {
            if (c == '\'' || c == '‘' || c == '’' || c == '‚' || c == '‛') {
                out.append(c);
            }
            out.append(c);
        }
        return out.append('\'').toString();
    }

    static String stripTrailingSeparator(String path) {
        String out = path;
        while (out.length() > 3 && (out.endsWith("\\") || out.endsWith("/"))) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String joinCodes() {
        StringBuilder out = new StringBuilder();
        for (int code : SUCCESS_CODES) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(code);
        }
        return out.toString();
    }
}
