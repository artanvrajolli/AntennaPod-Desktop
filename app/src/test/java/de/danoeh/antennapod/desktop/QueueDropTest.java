package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Where a dragged queue row lands: before the row it is dropped on, or last past the end. */
public class QueueDropTest {
    @Test
    public void testDraggingUpLandsOnTheDropRow() {
        // [A B C D], drag D onto A -> [D A B C]
        assertEquals(0, DesktopApp.queueDropTarget(3, 0, 4));
        assertEquals(1, DesktopApp.queueDropTarget(3, 1, 4));
    }

    @Test
    public void testDraggingDownAccountsForTheGap() {
        // [A B C D], drag A onto C -> before C, which is index 1 once A is out: [B A C D]
        assertEquals(1, DesktopApp.queueDropTarget(0, 2, 4));
        // onto the empty space past D -> last
        assertEquals(3, DesktopApp.queueDropTarget(0, 4, 4));
    }

    @Test
    public void testDroppingOnItselfOrJustBelowStaysPut() {
        assertEquals(2, DesktopApp.queueDropTarget(2, 2, 4));
        assertEquals(2, DesktopApp.queueDropTarget(2, 3, 4));
    }

    @Test
    public void testOutOfRangeIsClamped() {
        assertEquals(0, DesktopApp.queueDropTarget(2, -5, 4));
        assertEquals(3, DesktopApp.queueDropTarget(1, 99, 4));
    }
}
