package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class MiddleClickScrollTest {
    @Test
    public void pointerAtAnchorDoesNotDrift() {
        assertEquals(0, MiddleClickScroll.speed(0), 0);
        assertEquals(0, MiddleClickScroll.speed(12), 0);
        assertEquals(0, MiddleClickScroll.speed(-12), 0);
    }

    @Test
    public void pointerControlsDirectionAndSpeed() {
        assertTrue(MiddleClickScroll.speed(40) > 0);
        assertTrue(MiddleClickScroll.speed(-40) < 0);
        assertTrue(MiddleClickScroll.speed(80) > MiddleClickScroll.speed(40));
        assertEquals(-MiddleClickScroll.speed(80), MiddleClickScroll.speed(-80), 0);
        assertEquals(1400, MiddleClickScroll.speed(10000), 0);
        assertEquals(-1400, MiddleClickScroll.speed(-10000), 0);
    }
}
