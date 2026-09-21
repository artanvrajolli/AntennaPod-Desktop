package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import javafx.scene.Cursor;
import org.junit.Test;

/** The window chrome geometry, which needs no toolkit to check. */
public class WindowChromeTest {
    private static final double W = 800;
    private static final double H = 600;

    @Test
    public void testMiddleOfTheWindowIsNotAResizeZone() {
        assertEquals(WindowChrome.ZONE_NONE, WindowChrome.zoneAt(400, 300, W, H));
    }

    @Test
    public void testEachEdgeResizes() {
        assertEquals(WindowChrome.ZONE_NORTH, WindowChrome.zoneAt(400, 0, W, H));
        assertEquals(WindowChrome.ZONE_SOUTH, WindowChrome.zoneAt(400, H, W, H));
        assertEquals(WindowChrome.ZONE_WEST, WindowChrome.zoneAt(0, 300, W, H));
        assertEquals(WindowChrome.ZONE_EAST, WindowChrome.zoneAt(W, 300, W, H));
    }

    @Test
    public void testEachCornerResizesBothWays() {
        assertEquals(WindowChrome.ZONE_NORTH | WindowChrome.ZONE_WEST,
                WindowChrome.zoneAt(0, 0, W, H));
        assertEquals(WindowChrome.ZONE_NORTH | WindowChrome.ZONE_EAST,
                WindowChrome.zoneAt(W, 0, W, H));
        assertEquals(WindowChrome.ZONE_SOUTH | WindowChrome.ZONE_WEST,
                WindowChrome.zoneAt(0, H, W, H));
        assertEquals(WindowChrome.ZONE_SOUTH | WindowChrome.ZONE_EAST,
                WindowChrome.zoneAt(W, H, W, H));
    }

    @Test
    public void testTheZoneEndsAtTheResizeMargin() {
        assertEquals(WindowChrome.ZONE_WEST,
                WindowChrome.zoneAt(WindowChrome.RESIZE_MARGIN, 300, W, H));
        assertEquals(WindowChrome.ZONE_NONE,
                WindowChrome.zoneAt(WindowChrome.RESIZE_MARGIN + 1, 300, W, H));
    }

    @Test
    public void testPointsOutsideTheWindowAreNotResizeZones() {
        assertEquals(WindowChrome.ZONE_NONE, WindowChrome.zoneAt(-4, 300, W, H));
        assertEquals(WindowChrome.ZONE_NONE, WindowChrome.zoneAt(400, H + 4, W, H));
    }

    @Test
    public void testEveryZoneHasItsOwnCursor() {
        assertEquals(Cursor.DEFAULT, WindowChrome.cursorFor(WindowChrome.ZONE_NONE));
        assertEquals(Cursor.N_RESIZE, WindowChrome.cursorFor(WindowChrome.ZONE_NORTH));
        assertEquals(Cursor.S_RESIZE, WindowChrome.cursorFor(WindowChrome.ZONE_SOUTH));
        assertEquals(Cursor.W_RESIZE, WindowChrome.cursorFor(WindowChrome.ZONE_WEST));
        assertEquals(Cursor.E_RESIZE, WindowChrome.cursorFor(WindowChrome.ZONE_EAST));
        assertEquals(Cursor.NW_RESIZE,
                WindowChrome.cursorFor(WindowChrome.ZONE_NORTH | WindowChrome.ZONE_WEST));
        assertEquals(Cursor.NE_RESIZE,
                WindowChrome.cursorFor(WindowChrome.ZONE_NORTH | WindowChrome.ZONE_EAST));
        assertEquals(Cursor.SW_RESIZE,
                WindowChrome.cursorFor(WindowChrome.ZONE_SOUTH | WindowChrome.ZONE_WEST));
        assertEquals(Cursor.SE_RESIZE,
                WindowChrome.cursorFor(WindowChrome.ZONE_SOUTH | WindowChrome.ZONE_EAST));
    }

    @Test
    public void testResizeIsHeldInsideTheStageLimits() {
        assertEquals(1000, WindowChrome.clamp(400, 1000, 4000), 0.01);
        assertEquals(4000, WindowChrome.clamp(9000, 1000, 4000), 0.01);
        assertEquals(1500, WindowChrome.clamp(1500, 1000, 4000), 0.01);
    }
}
