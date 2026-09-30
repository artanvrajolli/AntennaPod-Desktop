package de.danoeh.antennapod.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A full backup of the profile in one zip: the whole database (subscriptions, episodes and their
 * positions and played state, history, favorites, queue, feed settings and logins) and every
 * setting. OPML export covers only the subscriptions. Downloaded files are left out; episodes
 * download again from their feeds.
 *
 * <p>A restore cannot swap the database under the running app, so {@link #stage} checks the zip
 * and puts its parts next to the database, and {@link #applyPending} moves them into place at the
 * next start, before the database is opened.
 */
public final class ProfileBackup {
    static final String DB_ENTRY = "antennapod.db";
    static final String SETTINGS_ENTRY = "settings.properties";
    static final String INFO_ENTRY = "backup-info.properties";
    static final String STAGED_DB = "restore-pending.db";
    static final String STAGED_SETTINGS = "restore-pending.properties";
    /** What the database a restore replaced is renamed to, in case the backup was the wrong one. */
    public static final String REPLACED_DB = "antennapod.db.before-restore";
    private static final String[] DB_SIDE_FILES = {"-journal", "-wal", "-shm"};

    private ProfileBackup() {
    }

    /** What a backup says about itself. */
    public static final class Info {
        public final String appVersion;
        public final long createdMs;
        public final int feedCount;

        Info(String appVersion, long createdMs, int feedCount) {
            this.appVersion = appVersion;
            this.createdMs = createdMs;
            this.feedCount = feedCount;
        }
    }

    /** Writes the backup zip; the database is snapshotted consistently while the app runs. */
    public static void write(DesktopDatabase database, File zip, String appVersion) throws Exception {
        write(database, zip, appVersion, DesktopPreferences.exportAll());
    }

    /** {@link #write} with the settings given, so tests never read the real registry node. */
    static void write(DesktopDatabase database, File zip, String appVersion, Properties settings)
            throws Exception {
        DesktopPreferences.getCacheDir().mkdirs();
        File snapshot = new File(DesktopPreferences.getCacheDir(),
                "backup-" + System.nanoTime() + ".db");
        File temp = new File(zip.getPath() + ".tmp");
        try {
            database.snapshotTo(snapshot);
            Properties info = new Properties();
            info.setProperty("appVersion", appVersion != null ? appVersion : "");
            info.setProperty("created", String.valueOf(System.currentTimeMillis()));
            info.setProperty("feeds", String.valueOf(countFeeds(snapshot)));
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp.toPath()))) {
                out.putNextEntry(new ZipEntry(INFO_ENTRY));
                info.store(out, "AntennaPod Desktop backup");
                out.closeEntry();
                out.putNextEntry(new ZipEntry(DB_ENTRY));
                Files.copy(snapshot.toPath(), out);
                out.closeEntry();
                out.putNextEntry(new ZipEntry(SETTINGS_ENTRY));
                settings.store(out, "AntennaPod Desktop settings");
                out.closeEntry();
            }
            Files.move(temp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            snapshot.delete();
            temp.delete();
        }
    }

    /**
     * Checks a backup and stages it for the next start. Nothing in use is touched, so a wrong or
     * damaged file fails here with the current profile intact.
     */
    public static Info stage(File zip, File dataDir) throws IOException {
        dataDir.mkdirs();
        File stagedDb = new File(dataDir, STAGED_DB);
        File stagedSettings = new File(dataDir, STAGED_SETTINGS);
        File dbTemp = new File(dataDir, STAGED_DB + ".tmp");
        File settingsTemp = new File(dataDir, STAGED_SETTINGS + ".tmp");
        Properties info = new Properties();
        boolean hasDb = false;
        boolean hasSettings = false;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                switch (entry.getName()) {
                    case DB_ENTRY:
                        Files.copy(in, dbTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        hasDb = true;
                        break;
                    case SETTINGS_ENTRY:
                        Files.copy(in, settingsTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        hasSettings = true;
                        break;
                    case INFO_ENTRY:
                        // load() leaves the stream open, so the zip goes on to the next entry
                        info.load(in);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            dbTemp.delete();
            settingsTemp.delete();
            throw new IOException("Not an AntennaPod Desktop backup: " + e.getMessage(), e);
        }
        try {
            if (!hasDb) {
                throw new IOException("Not an AntennaPod Desktop backup: it has no library in it");
            }
            int feeds;
            try {
                feeds = countFeeds(dbTemp);
            } catch (SQLException e) {
                throw new IOException("The library in this backup is damaged: " + e.getMessage(), e);
            }
            Files.move(dbTemp.toPath(), stagedDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (hasSettings) {
                Files.move(settingsTemp.toPath(), stagedSettings.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                stagedSettings.delete();
            }
            long created = parseLong(info.getProperty("created"));
            return new Info(info.getProperty("appVersion", ""), created, feeds);
        } finally {
            dbTemp.delete();
            settingsTemp.delete();
        }
    }

    public static boolean hasPending(File dataDir) {
        return new File(dataDir, STAGED_DB).isFile();
    }

    /** Drops a staged restore that was not applied yet. */
    public static void cancelPending(File dataDir) {
        new File(dataDir, STAGED_DB).delete();
        new File(dataDir, STAGED_SETTINGS).delete();
    }

    /**
     * Moves a staged restore into place. Call at startup, before the database is opened; the
     * database it replaces is kept as {@link #REPLACED_DB}. True when a restore was applied.
     */
    public static boolean applyPending(File dataDir) throws IOException {
        return applyPending(dataDir, DesktopPreferences::importAll);
    }

    /** {@link #applyPending} handing the settings to {@code importSettings}, for tests. */
    static boolean applyPending(File dataDir, java.util.function.Consumer<Properties> importSettings)
            throws IOException {
        File staged = new File(dataDir, STAGED_DB);
        if (!staged.isFile()) {
            return false;
        }
        File dbFile = new File(dataDir, DB_ENTRY);
        File replaced = new File(dataDir, REPLACED_DB);
        if (dbFile.exists()) {
            Files.move(dbFile.toPath(), replaced.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        // a journal left by the old database would be rolled into the restored one
        for (String suffix : DB_SIDE_FILES) {
            File side = new File(dataDir, DB_ENTRY + suffix);
            if (side.exists()) {
                Files.move(side.toPath(), new File(dataDir, REPLACED_DB + suffix).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(staged.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File settings = new File(dataDir, STAGED_SETTINGS);
        if (settings.isFile()) {
            Properties values = new Properties();
            try (InputStream in = Files.newInputStream(settings.toPath())) {
                values.load(in);
            }
            importSettings.accept(values);
            settings.delete();
        }
        return true;
    }

    private static int countFeeds(File databaseFile) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.getAbsolutePath() + "?open_mode=1");
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM feeds")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return value != null ? Long.parseLong(value.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
