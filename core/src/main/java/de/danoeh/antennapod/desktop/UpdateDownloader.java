package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
 * <p>This checks that the download is complete, not that it is genuine. The releases carry no
 * signature to verify against, so the protection here is HTTPS to GitHub and the length the API
 * reported — nothing stronger.
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
        File target = new File(dir, release.installerName);
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
            try (InputStream in = body.byteStream();
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
            Files.deleteIfExists(target.toPath());
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException | RuntimeException e) {
            // never leave a partial installer behind where it could be run
            part.delete();
            throw e;
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
