package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies dock padding and geometry calculations.
 * Ensures the initial paddingTop places the dock row at the target vertical position
 * on the first layout pass, preventing off-screen rows from inflating and eliminating
 * the secondary layout pass.
 */
public class DockGeometryTest {

    private static final float DENSITY_MI_STICK = 213f / 160f; // 1.33125
    private static final int WIDTH_MI_STICK = 1280;
    private static final int HEIGHT_MI_STICK = 720;
    private static final int INNER_MI_STICK = WIDTH_MI_STICK - (int) (64 * DENSITY_MI_STICK); // 1195

    @Test
    public void miStickColdStartPaddingTopLeavesRoomOnlyForDockRow() {
        int paddingTop = DockGeometry.calculatePaddingTop(
                INNER_MI_STICK, HEIGHT_MI_STICK, 6, 0, true, true, DENSITY_MI_STICK, false, 0, 0);

        // At 1280x720 with 6 columns, paddingTop should be exactly 514 px
        assertEquals(514, paddingTop);

        // Verify remaining vertical space below paddingTop
        int spaceBelow = HEIGHT_MI_STICK - paddingTop;
        int tileW = AppAdapter.tileWidthPx(INNER_MI_STICK, HEIGHT_MI_STICK, 6, 0, true, true, DENSITY_MI_STICK);
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW, true), true, DENSITY_MI_STICK);
        int dockRowH = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * DENSITY_MI_STICK);

        // Space below must fit dock row + bottom room, but must NOT fit a second row
        assertTrue("Must fit dock row", spaceBelow >= dockRowH);
        assertTrue("Must not fit row 1", spaceBelow < dockRowH + itemH);
    }

    @Test
    public void paddingBottomAccountsForParkedDockAndGridRows() {
        int tileW = AppAdapter.tileWidthPx(INNER_MI_STICK, HEIGHT_MI_STICK, 6, 0, true, true, DENSITY_MI_STICK);
        // 15 apps total: 6 in dock + 9 in grid (2 grid rows below dock)
        int bottom = DockGeometry.calculatePaddingBottom(
                HEIGHT_MI_STICK, 6, true, DENSITY_MI_STICK, tileW, 15);

        // Minimum bottom padding is 40dp (53px at density 1.33125)
        assertTrue(bottom >= (int) (40 * DENSITY_MI_STICK));
    }

    @Test
    public void plainGridUsesTopRoomAndHeader() {
        int headerBottom = 120;
        int paddingTop = DockGeometry.calculatePaddingTop(
                INNER_MI_STICK, HEIGHT_MI_STICK, 6, 3, false, false, DENSITY_MI_STICK, false, 0, headerBottom);
        assertEquals((int) (AppAdapter.TOP_ROOM_DP * DENSITY_MI_STICK) + headerBottom, paddingTop);
    }
}
