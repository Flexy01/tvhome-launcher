package com.gathin.tvhome;

/**
 * Calculates dock padding and grid viewport dimensions.
 * Pre-calculates target padding before initial layout to prevent inflating
 * off-screen rows and eliminate secondary layout passes.
 */
final class DockGeometry {

    private DockGeometry() {}

    static int calculatePaddingTop(int innerWidth, int gridHeight, int columns, int rowsBelowDock,
                                   boolean dockTitle, boolean dockRow, float density,
                                   boolean appLibrary, int heroHeight, int headerBottom) {
        int tileW = AppAdapter.tileWidthPx(innerWidth, gridHeight, columns, rowsBelowDock, dockTitle, dockRow, density);
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW, dockRow), dockRow, density);
        int dockRowH = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);
        int gridRows = itemH * rowsBelowDock;
        int shelfClearance = appLibrary ? heroHeight + (int) (40 * density) : 0;
        int want = Math.max(shelfClearance, gridHeight - dockRowH - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
        if (dockRow && dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
        if (!dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density) + headerBottom;
        return want;
    }

    static int calculatePaddingBottom(int gridHeight, int columns, boolean dockRow, float density,
                                      int tileW, int itemCount) {
        int bottom = (int) (40 * density);
        if (dockRow && itemCount > 0) {
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW, true), true, density);
            int rows = (Math.max(0, itemCount - columns) + columns - 1) / columns;
            int below = gridHeight - (int) ((10 + MainActivity.DOCK_PARK_TOP_DP + AppAdapter.DOCK_EXTRA_DP) * density) - itemH * (rows + 1);
            bottom = Math.max(bottom, below + (int) (8 * density));
        }
        return bottom;
    }
}
