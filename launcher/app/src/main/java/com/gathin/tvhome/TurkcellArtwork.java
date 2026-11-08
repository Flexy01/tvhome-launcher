package com.gathin.tvhome;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies one Turkcell TV+ film row. The row's content id (a TV+ page id) must be listed in the official film
 * sitemap; that page's title must equal the row's title; its IMDb link is then checked through Wikidata. Nothing
 * here is keyed by a fixed id: every film is found from the content id the device provides.
 */
final class TurkcellArtwork {

    static final String HOST_TVPLUS = "tvplus";
    static final String FILM_SITEMAP = "https://tvplus.com.tr/sitemaps/filmler_sitemap.xml";
    private static final Pattern DIGITS = Pattern.compile("\\d{6,12}");
    private static final Pattern PAGE_TITLE = Pattern.compile("<title>([^<]*?) İzle \\| ((?:19|20)\\d\\d) \\| TV\\+</title>");
    private static final Pattern PAGE_IMDB = Pattern.compile("imdb\\.com/title/(tt\\d{7,9})");

    private TurkcellArtwork() {}

    static boolean isContentId(String contentId) {
        return contentId != null && DIGITS.matcher(contentId).matches();
    }

    /** The verified IMDb film of one TV+ content id and row title; BASARISIZ or BELIRSIZ whenever any link is missing. */
    static PrimeImdb.Result verify(String contentId, String rowTitle, PrimeImdb.Net net) {
        if (!isContentId(contentId)) return PrimeImdb.Result.of(PrimeImdb.BASARISIZ);
        try {
            String page = officialPage(contentId, net);
            if (page == null) return PrimeImdb.Result.of(PrimeImdb.BASARISIZ);
            Matcher title = PAGE_TITLE.matcher(page);
            Matcher imdb = PAGE_IMDB.matcher(page);
            if (!title.find() || !imdb.find()) return PrimeImdb.Result.of(PrimeImdb.BELIRSIZ);
            if (!PrimeImdb.exactNorm(title.group(1)).equals(PrimeImdb.exactNorm(rowTitle))) {
                return PrimeImdb.Result.of(PrimeImdb.BASARISIZ);
            }
            return PrimeImdb.verifyImdbFilm(imdb.group(1), rowTitle, Integer.parseInt(title.group(2)), net);
        } catch (PrimeImdb.NetworkError e) {
            return PrimeImdb.Result.network(e);
        }
    }

    /** The official page of a content id, found in the film sitemap; null when the id is not listed. */
    private static String officialPage(String contentId, PrimeImdb.Net net) throws PrimeImdb.NetworkError {
        String xml = net.get(HOST_TVPLUS, FILM_SITEMAP);
        Matcher loc = Pattern.compile("<loc>(https://tvplus\\.com\\.tr/film-izle/[^<]*--" + contentId + ")</loc>")
                .matcher(xml);
        if (!loc.find()) return null;
        return net.get(HOST_TVPLUS, loc.group(1));
    }
}
