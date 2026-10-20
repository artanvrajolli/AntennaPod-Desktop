package de.danoeh.antennapod.parser.feed.util;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DateUtilsTest {
    @Test
    public void testManyCommaSeparatedPrefixesDoNotRecurse() {
        String crafted = "a, ".repeat(20_000) + "01 Sep 2025 10:00:00 GMT";
        assertNull(DateUtils.parse(crafted));
    }
}
