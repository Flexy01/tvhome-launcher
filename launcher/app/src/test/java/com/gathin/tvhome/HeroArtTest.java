package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class HeroArtTest {
    @Test public void landscapeArtworkKeepsItsAspectInsideTheHero() {
        assertArrayEquals(new int[]{0, 55, 640, 305}, HeroArt.fitBounds(320, 125, 640, 360));
    }

    @Test public void squareIconIsCenteredWithoutStretching() {
        assertArrayEquals(new int[]{140, 0, 500, 360}, HeroArt.fitBounds(128, 128, 640, 360));
    }

    @Test public void artworkWithoutIntrinsicDimensionsFillsTheHero() {
        assertArrayEquals(new int[]{0, 0, 640, 360}, HeroArt.fitBounds(-1, -1, 640, 360));
    }

    @Test public void generatedShelfIsFullBleedBrandWashWithoutLogoPixels() {
        assert HeroArt.shelfDrawsFullBleedWash();
    }
}
