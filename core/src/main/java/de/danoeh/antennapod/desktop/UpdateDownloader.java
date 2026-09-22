package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the installer for a release.
 *
 * <p>The file is written beside its final name and only moved into place once every byte the
 * release said it would have has arrived. A short file is deleted rather than kept, because the
 * one thing this must never do is hand a half-downloaded executable to the user to run.
 *
 * <p>When GitHub reports a SHA-256 digest for the asset, the download has to match it, so a file
 * altered in transit or on a mirror is refused. The releases carry no code signature, so beyond
 * that the protection is HTTPS to GitHub and the length the API reported.
 */
public final class UpdateDownloader {
    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    private UpdateDownloader() {
    }

    /** Where downloaded installers are kept, inside the app's own cache. */
    public static File updateDir() {
        return new File(DesktopPreferences.getCacheDir(), "updates");
    }

    /**
     * Downloads the release's installer and returns the finished file.
     *
     * @throws IOException if the download failed, or arrived a different length than the release
     *                     said it would be
     */
    public static File download(UpdateChecker.Release release, ProgressListener listener)
            throws IOException {
        if (!release.hasInstaller()) {
            throw new IOException("This release has no installer to download");
        }
        File dir = updateDir();
        dir.mkdirs();
        // the name comes from the release; only its last part may pick where the file goes
        File target = new File(dir, new File(release.installerName).getName());
        File part = new File(target.getAbsolutePath() + ".part");
        Request request = new Request.Builder().url(release.installerUrl).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Download returned nothing");
            }
            long expected = release.installerSize;
            long bytesRead = 0;
            MessageDigest sha256 = newSha256();
            try (InputStream in = new DigestInputStream(body.byteStream(), sha256);
                 OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    out.write(buffer, 0, read);
                    bytesRead += read;
                    if (listener != null) {
                        listener.onProgress(bytesRead, expected);
                    }
                }
            }
            if (bytesRead != expected) {
                throw new IOException("Download is " + bytesRead + " bytes, expected " + expected);
            }
            if (release.installerSha256 != null) {
                String actual = HexFormat.of().formatHex(sha256.digest());
                if (!actual.equals(release.installerSha256)) {
                    throw new IOException("Download does not match the release's checksum");
                }
            }
            Files.deleteIfExists(target.toPath());
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException | RuntimeException e) {
            // never leave a partial installer behind where it could be run
            part.delete();
            throw e;
        }
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /** Removes anything left in the update folder, so a stale installer is not kept around. */
    public static void clear() {
        File[] files = updateDir().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            file.delete();
        }
    }
}
