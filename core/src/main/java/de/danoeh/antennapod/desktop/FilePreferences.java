package de.danoeh.antennapod.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

/**
 * Settings kept in a properties file instead of the Windows registry, for portable mode: the
 * whole profile then lives in one folder that can be carried to another machine. A single flat
 * node is all {@link DesktopPreferences} uses; every change is written through at once.
 */
final class FilePreferences extends AbstractPreferences {
    private final File file;
    private final Properties values = new Properties();

    FilePreferences(File file) {
        super(null, "");
        this.file = file;
        load();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            values.load(in);
        } catch (IOException e) {
            // an unreadable file behaves like a fresh profile rather than stopping the app
            values.clear();
        }
    }

    private void save() {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            File temp = new File(file.getPath() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp.toPath())) {
                values.store(out, "AntennaPod Desktop settings (portable mode)");
            }
            // replace in one step, so a crash mid-write cannot leave half a file behind
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }

    @Override
    protected void putSpi(String key, String value) {
        values.setProperty(key, value);
        save();
    }

    @Override
    protected String getSpi(String key) {
        return values.getProperty(key);
    }

    @Override
    protected void removeSpi(String key) {
        if (values.remove(key) != null) {
            save();
        }
    }

    @Override
    protected void removeNodeSpi() throws BackingStoreException {
        throw new BackingStoreException("the portable settings node cannot be removed");
    }

    @Override
    protected String[] keysSpi() {
        return values.stringPropertyNames().toArray(new String[0]);
    }

    @Override
    protected String[] childrenNamesSpi() {
        return new String[0];
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        throw new UnsupportedOperationException("portable settings are a single node");
    }

    @Override
    protected void syncSpi() {
        load();
    }

    @Override
    protected void flushSpi() {
        save();
    }
}
