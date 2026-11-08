package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ShelfIdentityTest {

    @Test
    public void trustedExternalIdMatchesImmediatelyWithFullConfidence() {
        // Netflix URL with title ID
        ShelfIdentity.Query netflix = ShelfIdentity.parse(
                "Stranger Things", "nflx://www.netflix.com/title/80057281",
                "https://cdn.netflix/default.jpg", 1, null);
        assertEquals("netflix:80057281", netflix.externalId);

        ShelfIdentity.Candidate c1 = new ShelfIdentity.Candidate(
                "c1", "Stranger Things", 2016, ShelfIdentity.Type.SERIES,
                "netflix:80057281", "https://artwork.apple/st-4k.jpg");
        ShelfIdentity.Candidate c2 = new ShelfIdentity.Candidate(
                "c2", "Stranger Things", 2016, ShelfIdentity.Type.SERIES,
                "imdb:tt4574334", "https://artwork.apple/other.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(netflix, Arrays.asList(c2, c1));
        assertFalse(res.isFallback);
        assertEquals(c1, res.matched);
        assertEquals("https://artwork.apple/st-4k.jpg", res.resolvedArtwork);
        assertEquals(1.0f, res.confidence, 0.001f);
    }

    @Test
    public void youtubeVideoIdMatchesTrustedVideoCandidate() {
        ShelfIdentity.Query yt = ShelfIdentity.parse(
                "500 TL'lik Restoran", "https://www.youtube.com/watch?v=CkcGpXnt1uI",
                "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg", 4, null);
        assertEquals("youtube:CkcGpXnt1uI", yt.externalId);
        assertEquals(ShelfIdentity.Type.CLIP, yt.type);

        ShelfIdentity.Candidate c = new ShelfIdentity.Candidate(
                "yt1", "500 TL vs 7500 TL", 2024, ShelfIdentity.Type.CLIP,
                "youtube:CkcGpXnt1uI", "https://artwork.clean/yt-clean.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(yt, Collections.singletonList(c));
        assertFalse(res.isFallback);
        assertEquals("https://artwork.clean/yt-clean.jpg", res.resolvedArtwork);
        assertEquals(1.0f, res.confidence, 0.001f);
    }

    @Test
    public void titleAndYearMatchSeasonVariantToParentSeries() {
        // Prime Video: "Supernatural - 3. Sezon"
        ShelfIdentity.Query q = ShelfIdentity.parse(
                "Supernatural - 3. Sezon", "https://app.primevideo.com/watch?g=amzn1.dv.gti.123",
                "https://images.amazon/sn-fallback.jpg", 2, "2007");

        ShelfIdentity.Candidate series = new ShelfIdentity.Candidate(
                "spn", "Supernatural", 2005, ShelfIdentity.Type.SERIES,
                "imdb:tt0460681", "https://artwork.apple/spn-cinematic.jpg");
        ShelfIdentity.Candidate movie = new ShelfIdentity.Candidate(
                "spn-mov", "Supernatural Activity", 2012, ShelfIdentity.Type.MOVIE,
                "imdb:tt2112253", "https://artwork.apple/spn-movie.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(q, Arrays.asList(movie, series));
        assertFalse(res.isFallback);
        assertEquals("spn", res.matched.id);
        assertEquals("https://artwork.apple/spn-cinematic.jpg", res.resolvedArtwork);
        assertTrue(res.confidence >= 0.75f);
    }

    @Test
    public void typeIncompatibilityPreventsMovieFromMatchingSeries() {
        // Query is definitely a TV series with seasons
        ShelfIdentity.Query tvQuery = ShelfIdentity.parse(
                "Dune: The Sisterhood", "intent://dune",
                "https://provider/dune.jpg", 1, "2024");

        // Catalog only has Dune (1984 movie) and Dune (2021 movie)
        ShelfIdentity.Candidate movie1984 = new ShelfIdentity.Candidate(
                "dune1", "Dune", 1984, ShelfIdentity.Type.MOVIE, null, "https://art/dune1984.jpg");
        ShelfIdentity.Candidate movie2021 = new ShelfIdentity.Candidate(
                "dune2", "Dune", 2021, ShelfIdentity.Type.MOVIE, null, "https://art/dune2021.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(tvQuery, Arrays.asList(movie1984, movie2021));
        assertTrue("Movie candidate must not match TV series query", res.isFallback);
        assertEquals("https://provider/dune.jpg", res.resolvedArtwork);
    }

    @Test
    public void ambiguousMatchesBetweenCloseCandidatesFallbackToProvider() {
        // Query has ambiguous title "Ghosts" with no year or ID
        ShelfIdentity.Query q = ShelfIdentity.parse(
                "Ghosts", "intent://ghosts",
                "https://provider/ghosts-sd.jpg", -1, null);

        // Catalog has 2 distinct series with identical title: UK (2019) and US (2021)
        ShelfIdentity.Candidate uk = new ShelfIdentity.Candidate(
                "gh-uk", "Ghosts", 2019, ShelfIdentity.Type.SERIES, null, "https://art/ghosts-uk.jpg");
        ShelfIdentity.Candidate us = new ShelfIdentity.Candidate(
                "gh-us", "Ghosts", 2021, ShelfIdentity.Type.SERIES, null, "https://art/ghosts-us.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(q, Arrays.asList(uk, us));
        assertTrue("Ambiguous candidates without year must fall back to provider image", res.isFallback);
        assertEquals("https://provider/ghosts-sd.jpg", res.resolvedArtwork);
    }

    @Test
    public void resolvedWhenDisambiguatedByYear() {
        // Same query "Ghosts" but with year 2021
        ShelfIdentity.Query q = ShelfIdentity.parse(
                "Ghosts", "intent://ghosts",
                "https://provider/ghosts-sd.jpg", -1, "2021");

        ShelfIdentity.Candidate uk = new ShelfIdentity.Candidate(
                "gh-uk", "Ghosts", 2019, ShelfIdentity.Type.SERIES, null, "https://art/ghosts-uk.jpg");
        ShelfIdentity.Candidate us = new ShelfIdentity.Candidate(
                "gh-us", "Ghosts", 2021, ShelfIdentity.Type.SERIES, null, "https://art/ghosts-us.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(q, Arrays.asList(uk, us));
        assertFalse(res.isFallback);
        assertEquals("gh-us", res.matched.id);
        assertEquals("https://art/ghosts-us.jpg", res.resolvedArtwork);
    }

    @Test
    public void unknownOrLowConfidenceCandidateFallsBackSafely() {
        ShelfIdentity.Query q = ShelfIdentity.parse(
                "Rastgele Yerel Program Başlığı", "intent://local",
                "https://provider/local.jpg", 0, null);

        ShelfIdentity.Candidate batman = new ShelfIdentity.Candidate(
                "bm", "The Batman", 2022, ShelfIdentity.Type.MOVIE, null, "https://art/batman.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(q, Collections.singletonList(batman));
        assertTrue(res.isFallback);
        assertEquals("https://provider/local.jpg", res.resolvedArtwork);
        assertNull(res.matched);
    }

    @Test
    public void dynamicExtractionHandlesTurkishDiacriticsAndYearInTitle() {
        // "Örümcek-Adam: Örümcek Evreninde (2018)"
        ShelfIdentity.Query q = ShelfIdentity.parse(
                "Örümcek-Adam: Örümcek Evreninde (2018)", "intent://spider",
                "https://provider/fallback.jpg", 0, null);
        assertEquals(2018, q.year);
        assertEquals(ShelfIdentity.Type.MOVIE, q.type);

        ShelfIdentity.Candidate c = new ShelfIdentity.Candidate(
                "sm1", "Orumcek Adam Orumcek Evreninde", 2018, ShelfIdentity.Type.MOVIE,
                null, "https://artwork.clean/spider-verse.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(q, Collections.singletonList(c));
        assertFalse(res.isFallback);
        assertEquals("sm1", res.matched.id);
    }

    @Test
    public void conflictingMovieYearsAreStrictlyRejected() {
        // Gladiator (2000) query must strictly reject Gladiator II (2024)
        ShelfIdentity.Query q = ShelfIdentity.parse(
                "Gladiator", "intent://glad", "https://provider/gladiator.jpg", 0, "2000");

        ShelfIdentity.Candidate c2024 = new ShelfIdentity.Candidate(
                "g2", "Gladiator II", 2024, ShelfIdentity.Type.MOVIE, null, "https://art/glad2.jpg");
        ShelfIdentity.Candidate c2000 = new ShelfIdentity.Candidate(
                "g1", "Gladiator", 2000, ShelfIdentity.Type.MOVIE, null, "https://art/glad1.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(q, Arrays.asList(c2024, c2000));
        assertFalse(res.isFallback);
        assertEquals("g1", res.matched.id);

        // If only the 2024 sequel exists, it must fall back rather than match
        ShelfIdentity.MatchResult resFallback = ShelfIdentity.match(q, Collections.singletonList(c2024));
        assertTrue("Conflicting year (2000 vs 2024) must strictly fall back", resFallback.isFallback);
    }

    @Test
    public void conflictingMovieAndSeriesTypesAreStrictlyRejected() {
        // Movie query must strictly reject TV series candidate with identical title
        ShelfIdentity.Query movieQuery = ShelfIdentity.parse(
                "Carrie", "intent://carrie_movie", "https://provider/carrie_movie.jpg", 0, "1976");
        ShelfIdentity.Candidate seriesCandidate = new ShelfIdentity.Candidate(
                "c_series", "Carrie", 2026, ShelfIdentity.Type.SERIES, null, "https://art/carrie_series.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(movieQuery, Collections.singletonList(seriesCandidate));
        assertTrue("Movie query must strictly reject TV series candidate", res.isFallback);
    }

    @Test
    public void missingMetadataDoesNotProduceFullConfidence() {
        // Query has no year and no type: exact title match cannot reach 1.00 confidence
        ShelfIdentity.Query unkQuery = new ShelfIdentity.Query("The Mentalist", 0, ShelfIdentity.Type.UNKNOWN, null, "https://art/fallback.jpg");
        ShelfIdentity.Candidate candidate = new ShelfIdentity.Candidate(
                "tm", "The Mentalist", 2008, ShelfIdentity.Type.SERIES, null, "https://art/tm.jpg");

        ShelfIdentity.MatchResult res = ShelfIdentity.match(unkQuery, Collections.singletonList(candidate));
        assertFalse(res.isFallback);
        assertTrue("Incomplete metadata must cap confidence below 1.00", res.confidence <= 0.85f);
        assertTrue(res.confidence >= 0.65f);
    }

    @Test
    public void carrieRealTvContractBadgeIdentifiesSeriesAndRejects1976Movie() {
        // Real Mi TV Stick TvContract entry for Prime Video Carrie:
        // typeCode=4 (Leanback preview clip), releaseDate=null, poster containing "ZAYENI%20D%C4%B0Z%C4%B0LER" ("YENİ DİZİLER")
        ShelfIdentity.Query primeCarrie = ShelfIdentity.parse(
                "Carrie",
                "intent:#Intent;component=com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity;S.com.amazon.ignition.DeepLinkIntent.DEEP_LINK=detail%3Fgti%3Damzn1.dv.gti.bf6e4509;end",
                "https://images-eu.ssl-images-amazon.com/images/S/pv-target-images/..._ZAYENI%2520D%25C4%25B0Z%25C4%25B0LER...png",
                4,
                null
        );

        // Type is correctly extracted from the poster badge as SERIES, release date is null (year=0)
        assertEquals(ShelfIdentity.Type.SERIES, primeCarrie.type);
        assertEquals(0, primeCarrie.year);

        ShelfIdentity.Candidate movie1976 = new ShelfIdentity.Candidate(
                "itunes:387099242", "Carrie", 1976, ShelfIdentity.Type.MOVIE, null, "https://mzstatic/1976.jpg");
        ShelfIdentity.Candidate movie2013 = new ShelfIdentity.Candidate(
                "itunes:714768024", "Carrie", 2013, ShelfIdentity.Type.MOVIE, null, "https://mzstatic/2013.jpg");
        ShelfIdentity.Candidate series2026 = new ShelfIdentity.Candidate(
                "tvmaze:45562", "Carrie", 2026, ShelfIdentity.Type.SERIES, "imdb:tt11540908", "https://tvmaze/2026_backdrop.jpg");

        // The 1976 and 2013 movies are strictly rejected by type conflict
        ShelfIdentity.MatchResult res = ShelfIdentity.match(primeCarrie, Arrays.asList(movie1976, movie2013, series2026));
        assertFalse(res.isFallback);
        assertEquals("tvmaze:45562", res.matched.id);
        assertEquals("https://tvmaze/2026_backdrop.jpg", res.resolvedArtwork);
        // Confidence is capped at 0.85 because release date is missing in TvContract
        assertEquals(0.85f, res.confidence, 0.001f);
    }
}
