package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.*;

public class ShelfPostersTest {

    @Test
    public void maxRamBytesIsAtMost8MiB() {
        assertEquals(8 * 1024 * 1024, ShelfPosters.MAX_RAM_BYTES);
    }

    @Test
    public void sampleSizeTargets1280x720() {
        // 1080p Fanart / provider banner (1920x1080) must downsample to fit 1280x720
        assertEquals(2, ShelfPosters.sampleSize(1920, 1080));

        // Exact 720p image stays 1:1
        assertEquals(1, ShelfPosters.sampleSize(1280, 720));

        // Smaller provider images (e.g. 851x479) stay 1:1
        assertEquals(1, ShelfPosters.sampleSize(851, 479));

        // 4K (3840x2160) must downsample by 4 to 960x540 <= 1280x720
        assertEquals(4, ShelfPosters.sampleSize(3840, 2160));
    }

    @Test
    public void twoDecoded1080pPostersFitWithin8MiBLruBudget() {
        int sampleSize = ShelfPosters.sampleSize(1920, 1080);
        int decodedWidth = 1920 / sampleSize;
        int decodedHeight = 1080 / sampleSize;
        long bytesPerPoster = (long) decodedWidth * decodedHeight * 4; // ARGB_8888 = 4 bytes/pixel

        // Single 1080p downsampled poster must be ~2 MiB
        assertEquals(2073600L, bytesPerPoster);

        // Single poster must never exceed the cache capacity (previously 8.29 MiB > 4 MiB evicted instantly)
        assertTrue("Single poster must fit in cache", bytesPerPoster <= ShelfPosters.MAX_RAM_BYTES);

        // Two posters must comfortably fit in 8 MiB without evicting each other
        long twoPostersBytes = bytesPerPoster * 2;
        assertTrue("Two posters must fit in 8 MiB budget", twoPostersBytes <= ShelfPosters.MAX_RAM_BYTES);
    }

    @Test
    public void twoDecoded720pPostersFitWithin8MiBLruBudget() {
        int sampleSize = ShelfPosters.sampleSize(1280, 720);
        int decodedWidth = 1280 / sampleSize;
        int decodedHeight = 720 / sampleSize;
        long bytesPerPoster = (long) decodedWidth * decodedHeight * 4;

        assertEquals(3686400L, bytesPerPoster);
        assertTrue("Single 720p poster must fit in cache", bytesPerPoster <= ShelfPosters.MAX_RAM_BYTES);

        long twoPostersBytes = bytesPerPoster * 2;
        assertTrue("Two 720p posters must fit in 8 MiB budget", twoPostersBytes <= ShelfPosters.MAX_RAM_BYTES);
    }
}
