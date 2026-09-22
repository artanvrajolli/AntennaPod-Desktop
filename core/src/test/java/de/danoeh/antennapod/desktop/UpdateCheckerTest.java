package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two decisions the updater makes: whether a release is newer than this build, and which file
 * of a release is the one to install.
 */
public class UpdateCheckerTest {
    private static String releaseJson(String tag, String body, String... assetNames) {
        StringBuilder assets = new StringBuilder();
        for (String name : assetNames) {
            if (assets.length() > 0) {
                assets.append(',');
            }
            assets.append("{\"name\":\"").append(name)
                    .append("\",\"size\":12345,\"browser_download_url\":\"https://example.com/")
                    .append(name).append("\"}");
        }
        return "{\"tag_name\":\"" + tag + "\",\"body\":\"" + body
                + "\",\"html_url\":\"https://example.com/release\",\"assets\":["
                + assets + "]}";
    }

    // ------------------------------------------------------------------ versions

    @Test
    public void testAHigherPatchIsNewer() {
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"));
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.9"));
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"));
    }

    @Test
    public void testTenComesAfterNineRatherThanBeforeIt() {
        // the whole reason this is not a string comparison
        assertTrue("0.1.10".compareTo("0.1.9") < 0);
        assertTrue(UpdateChecker.compareVersions("0.1.10", "0.1.9") > 0);
    }

    @Test
    public void testTheSameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.9", "0.1.9"));
        assertEquals(0, UpdateChecker.compareVersions("0.2", "0.2.0"));
    }

    @Test
    public void testAnOlderReleaseIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.8", "0.1.9"));
    }

    @Test
    public void testADevelopmentBuildIsNeverOffered() {
        assertFalse(UpdateChecker.isComparable(UpdateChecker.DEV_VERSION));
        assertFalse(UpdateChecker.isComparable(""));
        assertFalse(UpdateChecker.isComparable(null));
        assertFalse(UpdateChecker.isNewer("9.9.9", UpdateChecker.DEV_VERSION));
    }

    @Test
    public void testASuffixDoesNotConfuseTheComparison() {
        assertEquals(0, UpdateChecker.compareVersions("1.2.3-beta1", "1.2.3"));
        assertTrue(UpdateChecker.compareVersions("1.2.4-rc1", "1.2.3") > 0);
    }

    @Test
    public void testNonNumericPartsDoNotThrow() {
        UpdateChecker.compareVersions("not.a.version", "0.1.9");
        UpdateChecker.compareVersions("", "");
    }

    // ------------------------------------------------------------------ parsing

    @Test
    public void testParsesTheReleaseAndItsInstaller() {
        UpdateChecker.Release release = UpdateChecker.parse(releaseJson("v0.2.0", "Some notes",
                "AntennaPod-Desktop-Windows.zip", "AntennaPod-Desktop-Setup-0.2.0.exe"));
        assertEquals("0.2.0", release.version);
        assertEquals("Some notes", release.notes);
        assertEquals("https://example.com/release", release.pageUrl);
        assertTrue(release.hasInstaller());
        assertEquals("AntennaPod-Desktop-Setup-0.2.0.exe", release.installerName);
        assertEquals(12345, release.installerSize);
    }

    @Test
    public void testTheLeadingVIsStrippedFromTheTag() {
        assertEquals("1.2.3", UpdateChecker.parse(releaseJson("v1.2.3", "x")).version);
        assertEquals("1.2.3", UpdateChecker.parse(releaseJson("1.2.3", "x")).version);
    }

    @Test
    public void testAReleaseWithOnlyAZipHasNoInstaller() {
        UpdateChecker.Release release = UpdateChecker.parse(
                releaseJson("v0.2.0", "x", "AntennaPod-Desktop-Windows.zip"));
        assertFalse("a zip is not something the app can run", release.hasInstaller());
        assertEquals("https://example.com/release", release.pageUrl);
    }

    @Test
    public void testAReleaseWithNoAssetsHasNoInstaller() {
        assertFalse(UpdateChecker.parse(releaseJson("v0.2.0", "x")).hasInstaller());
    }

    @Test
    public void testTheInstallersDigestIsRead() {
        String hash = "ab".repeat(32);
        String json = "{\"tag_name\":\"v0.3.0\",\"assets\":[{\"name\":\"Setup.exe\",\"size\":10,"
                + "\"browser_download_url\":\"https://example.com/Setup.exe\","
                + "\"digest\":\"sha256:" + hash.toUpperCase() + "\"}]}";
        assertEquals(hash, UpdateChecker.parse(json).installerSha256);
    }

    @Test
    public void testAMissingOrForeignDigestIsIgnored() {
        assertNull(UpdateChecker.parse(releaseJson("v0.3.0", "x", "Setup.exe")).installerSha256);
        assertNull(UpdateChecker.sha256Of("md5:0123"));
        assertNull(UpdateChecker.sha256Of("sha256:not-hex"));
        assertNull(UpdateChecker.sha256Of(null));
    }

    @Test
    public void testAReleaseWithoutATagIsIgnored() {
        assertNull(UpdateChecker.parse("{\"body\":\"x\",\"assets\":[]}"));
    }
}
