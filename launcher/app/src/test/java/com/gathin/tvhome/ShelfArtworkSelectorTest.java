package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.*;

public class ShelfArtworkSelectorTest {

    static final class MockPixelSource implements ShelfArtworkSelector.PixelSource {
        private final int width;
        private final int height;
        private final int[] pixels;

        MockPixelSource(int width, int height, int defaultColor) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
            java.util.Arrays.fill(this.pixels, defaultColor);
        }

        void fillZone(int startX, int endX, int startY, int endY, int color) {
            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    pixels[y * width + x] = color;
                }
            }
        }

        @Override public int getPixel(int x, int y) { return pixels[y * width + x]; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
    }

    @Test
    public void non16x9ImagesAreStrictlyRejected() {
        // Vertical 2:3 movie poster (720x1080)
        ShelfArtworkSelector.Evaluation dikey = ShelfArtworkSelector.evaluate(null, 720, 1080, 5);
        assertFalse("2:3 movie poster must be rejected", dikey.is16x9);
        assertEquals(0.0f, dikey.totalScore, 0.001f);

        // 16:10 banner (1680x1050)
        ShelfArtworkSelector.Evaluation wide1610 = ShelfArtworkSelector.evaluate(null, 1680, 1050, 8);
        assertFalse("16:10 banner must be rejected", wide1610.is16x9);
        assertEquals(0.0f, wide1610.totalScore, 0.001f);

        // Exact 16:9 (1920x1080)
        ShelfArtworkSelector.Evaluation valid169 = ShelfArtworkSelector.evaluate(null, 1920, 1080, 5);
        assertTrue("1920x1080 must be accepted as 16:9", valid169.is16x9);
        assertTrue(valid169.totalScore > 0.5f);
    }

    @Test
    public void darkQuietTitleZoneScoresHigherThanBlownOutBrightTitleZone() {
        // 1. Ideal candidate (like B99 winner 33713): Dark navy sky top-left (#142030), characters lower
        MockPixelSource good = new MockPixelSource(64, 36, 0xFF142030); // dark background
        good.fillZone(24, 64, 15, 30, 0xFFE0E0E0); // characters in center-right
        ShelfArtworkSelector.Evaluation goodEval = ShelfArtworkSelector.evaluate(good, 1920, 1080, 4);

        // 2. Bad candidate (like B99 yellow or Ghosts white): Bright blown-out top-left (#FFFFFF)
        MockPixelSource bad = new MockPixelSource(64, 36, 0xFFFFFFFF); // bright white
        bad.fillZone(24, 64, 15, 30, 0xFF202020);
        ShelfArtworkSelector.Evaluation badEval = ShelfArtworkSelector.evaluate(bad, 1920, 1080, 4);

        assertTrue("Dark title zone must score higher on title contrast",
                goodEval.titleContrast > badEval.titleContrast);
        assertEquals(1.0f, goodEval.titleContrast, 0.001f);
        assertEquals(0.0f, badEval.titleContrast, 0.001f);
        assertTrue("Overall score for calm dark layout must beat bright blown-out layout",
                goodEval.totalScore > badEval.totalScore);
    }

    @Test
    public void dockBaselineProvidesPositiveContrast() {
        // Dark bottom zone (y: 72%..100%) vs bright white bottom zone
        MockPixelSource darkDock = new MockPixelSource(64, 36, 0xFF202020);
        ShelfArtworkSelector.Evaluation darkEval = ShelfArtworkSelector.evaluate(darkDock, 1920, 1080, 0);

        MockPixelSource whiteDock = new MockPixelSource(64, 36, 0xFF202020);
        whiteDock.fillZone(0, 64, 26, 36, 0xFFFFFFFF); // bright white bottom
        ShelfArtworkSelector.Evaluation whiteEval = ShelfArtworkSelector.evaluate(whiteDock, 1920, 1080, 0);

        assertTrue("Dark dock baseline must score higher for glass contrast",
                darkEval.dockContrast > whiteEval.dockContrast);
        assertEquals(1.0f, darkEval.dockContrast, 0.001f);
        assertEquals(0.1f, whiteEval.dockContrast, 0.001f);
    }

    @Test
    public void highDetailHiddenByDockRanksBelowTheSameDetailAboveIt() {
        MockPixelSource visible = new MockPixelSource(64, 36, 0xFF202020);
        MockPixelSource covered = new MockPixelSource(64, 36, 0xFF202020);
        // Equal mean luminance in the framing zone; only the detail location differs.
        for (int y = 0; y < 6; y++) {
            for (int x = 30; x < 58; x++) {
                int color = ((x + y) & 1) == 0 ? 0xFF000000 : 0xFF404040;
                visible.fillZone(x, x + 1, y + 16, y + 17, color);
                covered.fillZone(x, x + 1, y + 26, y + 27, color);
            }
        }
        ShelfArtworkSelector.Evaluation visibleEval = ShelfArtworkSelector.evaluate(visible, 1920, 1080, 4);
        ShelfArtworkSelector.Evaluation coveredEval = ShelfArtworkSelector.evaluate(covered, 1920, 1080, 4);
        assertTrue("Equal-brightness detail behind the dock must reduce the final score",
                visibleEval.totalScore > coveredEval.totalScore + 0.02f);
    }

    @Test
    public void uniformTextureIsNotMistakenForDetailConcentratedBehindDock() {
        MockPixelSource texture = new MockPixelSource(64, 36, 0xFF202020);
        for (int y = 0; y < 36; y++) {
            for (int x = 0; x < 64; x++) {
                texture.fillZone(x, x + 1, y, y + 1,
                        ((x + y) & 1) == 0 ? 0xFF000000 : 0xFF404040);
            }
        }
        assertEquals("Uniform texture must not incur an excess dock-concentration penalty", 0.0f,
                ShelfArtworkSelector.evaluate(texture, 1280, 720, 0).dockOverlap, 0.0001f);
    }

    @Test
    public void missingOrTinyProbeDoesNotInventOverlapOrProduceNonFiniteScores() {
        assertEquals(0.0f, ShelfArtworkSelector.evaluate(null, 1280, 720, 4).dockOverlap, 0.0f);
        for (int size : new int[] {1, 2, 3, 36}) {
            ShelfArtworkSelector.Evaluation result = ShelfArtworkSelector.evaluate(
                    new MockPixelSource(size, size, 0xFF202020), 1280, 720, 4);
            assertTrue(Float.isFinite(result.totalScore));
            assertEquals(0.0f, result.dockOverlap, 0.0001f);
        }
    }

    @Test
    public void ghostsUSStrictlyRejectsGhostsUK() {
        // Query is Ghosts US (2021)
        ShelfIdentity.Query ghostsUS = ShelfIdentity.parse("Ghosts", "nflx://www.netflix.com/title/80186863", null, 1, "2021");
        assertEquals(2021, ghostsUS.year);

        // Ghosts UK candidate (2019, TVDB 361701)
        boolean allowed = ShelfArtworkSelector.isAdaptationCompatible(ghostsUS, 2019, "tvdb:361701");
        assertFalse("Ghosts US (2021) must strictly reject Ghosts UK (2019) candidate", allowed);

        // Ghosts US candidate (2021, TVDB 394200)
        boolean usAllowed = ShelfArtworkSelector.isAdaptationCompatible(ghostsUS, 2021, "tvdb:394200");
        assertTrue("Ghosts US (2021) must accept verified US candidate", usAllowed);
    }

    @Test
    public void memoryBudgetEvaluationIsUnder10KiB() {
        // Probe size is 64x36 pixels
        int probePixels = ShelfArtworkSelector.PROBE_WIDTH * ShelfArtworkSelector.PROBE_HEIGHT;
        int memoryBytes = probePixels * 4; // 32-bit ARGB
        assertTrue("Probe memory must be less than 10 KiB (9.2 KiB)", memoryBytes < 10 * 1024);
        assertEquals(2304 * 4, memoryBytes); // exactly 9216 bytes
    }

    @Test
    public void likesBonusIsTieBreakerAndNeverOverturnsVisualQualityDifferenceGreaterThanFiveThousandths() {
        MockPixelSource superiorVisual = new MockPixelSource(64, 36, 0xFF142030); // dark navy
        superiorVisual.fillZone(24, 64, 15, 30, 0xFFE0E0E0); // characters center-right

        MockPixelSource slightlyWorseVisual = new MockPixelSource(64, 36, 0xFF142030);
        // Title luminance exceeds the 0.35 contrast threshold, yielding a visual difference > 0.005.
        slightlyWorseVisual.fillZone(0, 26, 0, 14, 0xFF5E5E5E);
        slightlyWorseVisual.fillZone(24, 64, 15, 30, 0xFFE0E0E0);

        ShelfArtworkSelector.Evaluation superiorZeroLikes = ShelfArtworkSelector.evaluate(superiorVisual, 1920, 1080, 0);
        ShelfArtworkSelector.Evaluation worseTenLikes = ShelfArtworkSelector.evaluate(slightlyWorseVisual, 1920, 1080, 10);

        // Visual difference > 0.005 must NOT be overturned by 10 likes
        ShelfArtworkSelector.Evaluation worseZeroLikes = ShelfArtworkSelector.evaluate(slightlyWorseVisual, 1920, 1080, 0);
        float visualDifference = superiorZeroLikes.totalScore - worseZeroLikes.totalScore;
        assertTrue("Visual difference must be greater than 0.005 (" + visualDifference + ")",
                visualDifference > 0.005f);
        assertTrue("Superior visual candidate (0 likes) must beat worse candidate (10 likes) when visual diff > 0.005",
                superiorZeroLikes.totalScore > worseTenLikes.totalScore);

        // Tie-breaker when visual quality is identical: candidate with more likes wins
        ShelfArtworkSelector.Evaluation tieZeroLikes = ShelfArtworkSelector.evaluate(superiorVisual, 1920, 1080, 0);
        ShelfArtworkSelector.Evaluation tieTenLikes = ShelfArtworkSelector.evaluate(superiorVisual, 1920, 1080, 10);
        assertTrue("Candidate with more likes must win on equal visual score",
                tieTenLikes.totalScore > tieZeroLikes.totalScore);
        assertEquals("Max likes bonus at 10 likes must be exactly 0.005",
                0.005f, tieTenLikes.totalScore - tieZeroLikes.totalScore, 0.0001f);
    }
}
