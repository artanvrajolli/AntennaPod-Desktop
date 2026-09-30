package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/** The message bundle: lookups, formatting, and that code and bundle agree on the keys. */
public class MessagesTest {
    private static final Pattern CALL = Pattern.compile("Messages\\.(get|format)\\(\\s*\"([^\"]+)\"");

    @Test
    public void plainKeyReturnsItsText() {
        assertEquals("Skip silence", Messages.get("player.skip_silence"));
        assertEquals("Nothing playing", Messages.get("player.nothing_playing"));
    }

    @Test
    public void formattedKeyFillsItsPlaceholders() {
        assertEquals("Subscribed to Syntax", Messages.format("status.subscribed", "Syntax"));
        assertEquals("Refreshed Syntax: 3 new episodes",
                Messages.format("status.refresh.feed_done", "Syntax", 3));
    }

    @Test
    public void numbersAreInsertedWithoutGrouping() {
        assertEquals("12345 new episodes", Messages.format("count.new_episode.other", 12345));
    }

    @Test
    public void apostropheInPlainTextStaysSingle() {
        assertEquals("Search this podcast's episodes", Messages.get("a11y.episode_filter"));
        assertEquals("Let the keyboard's media keys control playback from any window",
                Messages.get("settings.media_keys"));
    }

    @Test
    public void apostropheInFormattedTextStaysSingle() {
        // stored as '' in the bundle because MessageFormat reads the value
        assertTrue(Messages.format("sync.status.saved_automatically", "Sync is disabled.")
                .startsWith("Sync is disabled."));
        assertEquals("Could not play \"Ep. 1\": boom",
                Messages.format("playback.error.could_not_play", "Ep. 1", "boom"));
    }

    @Test
    public void missingKeyFallsBackToTheKey() {
        assertEquals("no.such.key", Messages.get("no.such.key"));
        assertEquals("no.such.key", Messages.format("no.such.key", "x", 1));
    }

    @Test
    public void everyKeyUsedInCodeIsInTheBundleAndEveryBundleKeyIsUsed() throws IOException {
        Properties bundle = loadBundle();
        Set<String> used = new TreeSet<>();
        Set<String> formatted = new TreeSet<>();
        for (Path root : sourceRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    Matcher matcher = CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        used.add(matcher.group(2));
                        if ("format".equals(matcher.group(1))) {
                            formatted.add(matcher.group(2));
                        }
                    }
                }
            }
        }
        assertFalse("no Messages calls found; wrong source roots?", used.isEmpty());
        List<String> missing = new ArrayList<>();
        for (String key : used) {
            if (!bundle.containsKey(key)) {
                missing.add(key);
            }
        }
        assertTrue("keys used in code but missing from the bundle: " + missing, missing.isEmpty());
        List<String> dead = new ArrayList<>();
        for (String key : bundle.stringPropertyNames()) {
            if (!used.contains(key)) {
                dead.add(key);
            }
        }
        assertTrue("keys in the bundle that no code uses: " + dead, dead.isEmpty());
        for (String key : formatted) {
            // every formatted value must be a valid MessageFormat pattern
            new MessageFormat(bundle.getProperty(key));
        }
    }

    private static Properties loadBundle() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessagesTest.class.getResourceAsStream("/i18n/messages.properties")) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    /** The test runs in the core module directory; the app sources sit next to it. */
    private static List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (String dir : new String[]{"src/main/java", "../app/src/main/java"}) {
            File root = new File(dir);
            assertTrue("missing source root " + root.getAbsolutePath(), root.isDirectory());
            roots.add(root.toPath());
        }
        return roots;
    }
}
