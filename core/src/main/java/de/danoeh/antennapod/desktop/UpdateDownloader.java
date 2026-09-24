package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the installer for a release.
 *
 * <p>Attempts stream into a unique temp file and only move into place once every byte the
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

    /** How many times a flaky download is tried before giving up. */
    static final int MAX_ATTEMPTS = 3;
    /** How many times the finished file move is retried while Windows holds it. */
    static final int MOVE_ATTEMPTS = 5;

    /**
     * Downloads the release's installer and returns the finished file.
     *
     * <p>A flaky connection or a virus scanner holding the file can fail any single attempt, so a
     * failed attempt is retried from the start. Only the finished installer keeps its release name;
     * attempts stream into a unique temp file, so two overlapping downloads never share one.
     *
     * @throws IOException with a message safe to show the user (never a bare file path)
     */
    public static File download(UpdateChecker.Release release, ProgressListener listener)
            throws IOException {
        if (!release.hasInstaller()) {
            throw new IOException("This release has no installer to download");
        }
        File dir = updateDir();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create the update folder, check that "
                    + DesktopPreferences.getCacheDir() + " is writable");
        }
        // the name comes from the release; only its last part may pick where the file goes
        String safeName = new File(release.installerName).getName();
        if (safeName.isEmpty()) {
            throw new IOException("This release has no installer to download");
        }
        File target = new File(dir, safeName);
        // best effort: drop a stale partial file from an older version of this download
        try {
            Files.deleteIfExists(new File(target.getAbsolutePath() + ".part").toPath());
        } catch (IOException ignored) {
            // the temp file below gets a unique name, so a stale file never blocks us
        }
        Path temp;
        try {
            temp = Files.createTempFile(dir.toPath(), safeName + "-", ".part");
        } catch (IOException | RuntimeException e) {
            throw friendlyFileError("Could not write to the update folder", e);
        }
        try {
            IOException lastFailure = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    downloadOnce(release, listener, temp);
                    lastFailure = null;
                    break;
                } catch (IOException e) {
                    if (isCancellation(e)) {
                        throw e;
                    }
                    lastFailure = e;
                    if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                        throw e;
                    }
                    backoff(attempt);
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
            moveIntoPlace(temp, target);
            return target;
        } catch (IOException | RuntimeException e) {
            // never leave a partial installer behind where it could be run
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                temp.toFile().delete();
            }
            throw e;
        }
    }

    private static void downloadOnce(UpdateChecker.Release release, ProgressListener listener,
            Path temp) throws IOException {
        // installers are far bigger than the shared 20MB HTTP cache, so they bypass it entirely
        OkHttpClient client = AntennapodHttpClient.getHttpClient().newBuilder()
                .cache(null)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(release.installerUrl)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw retryableForStatus(response.code(),
                        "Download failed (HTTP " + response.code() + ")");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw retryable("Download returned nothing");
            }
            long expected = release.installerSize;
            long bytesRead = 0;
            MessageDigest sha256 = newSha256();
            try (InputStream in = new DigestInputStream(body.byteStream(), sha256);
                 OutputStream out = Files.newOutputStream(temp,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download cancelled");
                    }
                    out.write(buffer, 0, read);
                    bytesRead += read;
                    if (listener != null) {
                        listener.onProgress(bytesRead, expected);
                    }
                }
            } catch (NoSuchFileException | AccessDeniedException e) {
                throw friendlyFileError("Could not write to the update folder", e);
            } catch (IOException e) {
                if (isCancellation(e)) {
                    throw e;
                }
                throw retryable("Download was interrupted (" + e.getMessage() + ")");
            }
            if (bytesRead != expected) {
                throw retryable("Download is incomplete (got " + bytesRead + " bytes, expected "
                        + expected + "). Check the connection and try again");
            }
            if (release.installerSha256 != null) {
                String actual = HexFormat.of().formatHex(sha256.digest());
                if (!actual.equals(release.installerSha256)) {
                    throw new IOException("Download does not match the release's checksum");
                }
            }
        } catch (IOException | RuntimeException e) {
            // truncate so the next attempt starts clean; the caller deletes on final failure
            try {
                Files.newOutputStream(temp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).close();
            } catch (IOException ignored) {
                // best effort only
            }
            throw e;
        }
    }

    private static void moveIntoPlace(Path temp, File target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(target.toPath());
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException | NoSuchFileException e) {
                lastFailure = friendlyFileError(
                        "Could not save the update, the file may still be scanned", e);
            } catch (IOException e) {
                lastFailure = friendlyFileError("Could not save the update", e);
            }
            if (attempt < MOVE_ATTEMPTS) {
                backoff(attempt);
            }
        }
        throw lastFailure;
    }

    private static IOException retryable(String message) {
        return new RetryableIOException(message);
    }

    private static IOException retryableForStatus(int code, String message) {
        if (code == 429 || (code >= 500 && code < 600)) {
            return new RetryableIOException(message);
        }
        return new IOException(message);
    }

    private static boolean isRetryable(IOException e) {
        if (e instanceof RetryableIOException) {
            return true;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.US);
        return e instanceof InterruptedIOException
                || e instanceof java.net.SocketException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException
                || message.contains("timeout")
                || message.contains("reset")
                || message.contains("unexpected end of stream");
    }

    private static boolean isCancellation(IOException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.US).contains("cancelled");
    }

    private static void backoff(int attempt) throws IOException {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download cancelled");
        }
    }

    /**
     * A file error whose message stays readable: the raw path is kept as the cause for logs, but
     * the user is told what to do instead of being shown a bare {@code .part} path.
     */
    private static IOException friendlyFileError(String what, Exception cause) {
        String detail = "";
        if (!(cause instanceof NoSuchFileException) && !(cause instanceof AccessDeniedException)
                && cause.getMessage() != null && !cause.getMessage().isBlank()
                && !looksLikeAPath(cause.getMessage())) {
            detail = " (" + cause.getMessage() + ")";
        }
        IOException out = new IOException(what + ": close any installer left open and try again"
                + detail, cause);
        if (cause instanceof RetryableIOException) {
            return new RetryableIOException(out.getMessage(), out.getCause());
        }
        return out;
    }

    private static boolean looksLikeAPath(String message) {
        String trimmed = message.trim();
        return trimmed.matches("(?i)^[a-z]:\\\\.*")
                || trimmed.matches("^/[^\\n]*")
                || trimmed.endsWith(".part");
    }

    /** Marker for failures worth trying again (flaky network, busy file). */
    private static final class RetryableIOException extends IOException {
        RetryableIOException(String message) {
            super(message);
        }

        RetryableIOException(String message, Throwable cause) {
            super(message, cause);
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
