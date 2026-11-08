package com.gathin.tvhome;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic content identity and artwork matching for Top Shelf.
 * Matches TvContract programs with alternative artwork sources using title,
 * year, movie/series type, and trusted external IDs. Falls back to the provider
 * image whenever matching is ambiguous or uncertain.
 */
final class ShelfIdentity {

    private static final float CONFIDENCE_THRESHOLD = 0.65f;
    private static final float AMBIGUITY_MARGIN = 0.15f;

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");
    private static final Pattern TITLE_YEAR_PATTERN = Pattern.compile("[(\\[](19\\d\\d|20\\d\\d)[)\\]]");
    private static final Pattern SEASON_EPISODE_PATTERN = Pattern.compile(
            "\\b(season|sezon|episode|bölüm|bolum|part|kısım|kisim|vol|volume|s\\d+|e\\d+)\\s*\\d*\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NETFLIX_ID = Pattern.compile("(?:nflx://.*|netflix\\.com/.*)title/(\\d+)");
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:watch\\?(?:[^#?]*&)?v=|youtu\\.be/|vnd\\.youtube:(?://)?)([a-zA-Z0-9_-]{11})(?![a-zA-Z0-9_-])");
    private static final Pattern AMAZON_GTI = Pattern.compile("[?&]g=(amzn1\\.dv\\.gti\\.[a-zA-Z0-9_-]+)");
    private static final Pattern AMAZON_ASIN = Pattern.compile("\\b([B0-9][A-Z0-9]{9})\\b");
    private static final Pattern IMDB_ID = Pattern.compile("(?:title/|imdb:)(tt\\d+)");
    private static final Pattern TMDB_ID = Pattern.compile("tmdb:(\\d+)");

    private static final Set<String> STOP_WORDS = new HashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "and", "of", "in", "on", "at", "to", "for", "with",
            "bir", "ve", "ile", "de", "da", "der", "die", "das", "le", "la", "les"
    ));

    enum Type {
        UNKNOWN, MOVIE, SERIES, EPISODE, CLIP
    }

    static final class Query {
        final String title;
        final int year;
        final Type type;
        final String externalId;
        final String fallbackPoster;

        Query(String title, int year, Type type, String externalId, String fallbackPoster) {
            this.title = title != null ? title.trim() : "";
            this.year = year;
            this.type = type != null ? type : Type.UNKNOWN;
            this.externalId = externalId;
            this.fallbackPoster = fallbackPoster;
        }
    }

    static final class Candidate {
        final String id;
        final String title;
        final int year;
        final Type type;
        final String externalId;
        final String artworkUri;

        Candidate(String id, String title, int year, Type type, String externalId, String artworkUri) {
            this.id = id != null ? id : "";
            this.title = title != null ? title.trim() : "";
            this.year = year;
            this.type = type != null ? type : Type.UNKNOWN;
            this.externalId = externalId;
            this.artworkUri = artworkUri;
        }
    }

    static final class MatchResult {
        final Candidate matched;
        final String resolvedArtwork;
        final float confidence;
        final boolean isFallback;

        MatchResult(Candidate matched, String resolvedArtwork, float confidence, boolean isFallback) {
            this.matched = matched;
            this.resolvedArtwork = resolvedArtwork;
            this.confidence = confidence;
            this.isFallback = isFallback;
        }
    }

    private ShelfIdentity() {}

    static Query parse(String title, String intentUri, String fallbackPoster, int typeCode, String releaseDate) {
        String safeTitle = title != null ? title.trim() : "";
        int year = parseYear(safeTitle, releaseDate);
        Type type = parseType(typeCode, safeTitle, fallbackPoster, intentUri);
        String externalId = extractExternalId(intentUri);
        return new Query(safeTitle, year, type, externalId, fallbackPoster);
    }

    static MatchResult match(Query query, List<Candidate> candidates) {
        if (query == null || candidates == null || candidates.isEmpty()) {
            return new MatchResult(null, query != null ? query.fallbackPoster : null, 0.0f, true);
        }

        // 1. Trusted external ID match (highest priority, 1.0 confidence)
        if (query.externalId != null && !query.externalId.isEmpty()) {
            for (Candidate c : candidates) {
                if (c != null && query.externalId.equalsIgnoreCase(c.externalId)) {
                    return new MatchResult(c, c.artworkUri, 1.0f, false);
                }
            }
        }

        // 2. Score candidate similarity across title, year, and type
        Candidate best = null;
        float bestScore = -1.0f;
        float secondScore = -1.0f;

        Set<String> queryTokens = extractTokens(query.title);
        if (queryTokens.isEmpty()) {
            return new MatchResult(null, query.fallbackPoster, 0.0f, true);
        }

        for (Candidate c : candidates) {
            if (c == null) continue;
            float score = scoreCandidate(query, queryTokens, c);
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = c;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        // 3. Fallback on low confidence or ambiguous matches
        if (best == null || bestScore < CONFIDENCE_THRESHOLD) {
            return new MatchResult(null, query.fallbackPoster, Math.max(0.0f, bestScore), true);
        }

        if (secondScore >= 0 && (bestScore - secondScore) < AMBIGUITY_MARGIN && bestScore < 0.95f) {
            // Ambiguous match: multiple candidates score too close to each other
            return new MatchResult(null, query.fallbackPoster, bestScore, true);
        }

        return new MatchResult(best, best.artworkUri, bestScore, false);
    }

    private static float scoreCandidate(Query query, Set<String> queryTokens, Candidate candidate) {
        Set<String> candidateTokens = extractTokens(candidate.title);
        if (candidateTokens.isEmpty()) return 0.0f;

        // 1. HARD TYPE CONFLICT REJECTION: Strictly reject Movie vs Series / Clip conflicts
        if (query.type != Type.UNKNOWN && candidate.type != Type.UNKNOWN) {
            boolean qMovie = query.type == Type.MOVIE;
            boolean cMovie = candidate.type == Type.MOVIE;
            boolean qTv = (query.type == Type.SERIES || query.type == Type.EPISODE);
            boolean cTv = (candidate.type == Type.SERIES || candidate.type == Type.EPISODE);
            boolean qClip = query.type == Type.CLIP;
            boolean cClip = candidate.type == Type.CLIP;

            if (qMovie && cTv) return 0.0f;
            if (qTv && cMovie) return 0.0f;
            if (qClip && (cMovie || cTv)) return 0.0f;
            if ((qMovie || qTv) && cClip) return 0.0f;
        }

        // 2. HARD YEAR CONFLICT REJECTION: Strictly reject conflicting years
        if (query.year > 0 && candidate.year > 0) {
            int diff = Math.abs(query.year - candidate.year);
            boolean hasSeason = SEASON_EPISODE_PATTERN.matcher(query.title).find();

            if (query.type == Type.MOVIE || candidate.type == Type.MOVIE) {
                // Movies cannot differ by more than 1 year (international release differences)
                if (diff > 1) return 0.0f;
            } else if (hasSeason) {
                // For a specific season query, candidate series premiere must be <= season release year
                if (candidate.year > query.year + 1 || diff > 15) return 0.0f;
            } else {
                // TV series without season in title cannot differ by more than 1 year
                if (diff > 1) return 0.0f;
            }
        }

        // 3. Title token similarity (Jaccard + subset ratio)
        int intersection = 0;
        for (String t : queryTokens) {
            if (candidateTokens.contains(t)) intersection++;
        }
        if (intersection == 0) return 0.0f;

        float jaccard = (float) intersection / (queryTokens.size() + candidateTokens.size() - intersection);
        float subsetRatio = (float) intersection / Math.min(queryTokens.size(), candidateTokens.size());
        float titleScore = 0.5f * jaccard + 0.5f * subsetRatio;

        // 4. Type compatibility adjustment
        float typeScore = 0.0f;
        boolean typeVerified = false;
        if (query.type != Type.UNKNOWN && candidate.type != Type.UNKNOWN) {
            if (query.type == candidate.type) {
                typeScore = 0.15f;
                typeVerified = true;
            } else if ((query.type == Type.SERIES && candidate.type == Type.EPISODE)
                    || (query.type == Type.EPISODE && candidate.type == Type.SERIES)) {
                typeScore = 0.05f;
                typeVerified = true;
            }
        }

        // 5. Year compatibility adjustment
        float yearScore = 0.0f;
        boolean yearVerified = false;
        if (query.year > 0 && candidate.year > 0) {
            int diff = Math.abs(query.year - candidate.year);
            if (diff == 0) {
                yearScore = 0.15f;
                yearVerified = true;
            } else if (diff == 1) {
                yearScore = 0.08f;
                yearVerified = true;
            } else {
                yearScore = 0.02f;
                yearVerified = true;
            }
        }

        float total = (titleScore * 0.70f) + typeScore + yearScore;

        // 6. Missing metadata rule: NEVER give 1.00 confidence on incomplete data
        // 1.00 confidence requires verified title match, verified year match, and verified type match
        if (!yearVerified || !typeVerified) {
            float cap = (!yearVerified && !typeVerified) ? 0.70f : 0.85f;
            total = Math.min(total, cap);
        }

        return Math.max(0.0f, Math.min(1.0f, total));
    }

    static Set<String> extractTokens(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        String s = stripDiacritics(raw).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[:\\-_–—|·•,/()\\[\\]{}]", " ");
        s = SEASON_EPISODE_PATTERN.matcher(s).replaceAll(" ");
        s = s.replaceAll("[^a-z0-9\\s]", "");
        Set<String> tokens = new HashSet<>();
        for (String w : s.split("\\s+")) {
            if (w.length() >= 2 && !STOP_WORDS.contains(w) && !isYearToken(w)) {
                tokens.add(w);
            }
        }
        return tokens;
    }

    private static boolean isYearToken(String w) {
        if (w.length() != 4) return false;
        try {
            int y = Integer.parseInt(w);
            return y >= 1900 && y <= 2099;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String stripDiacritics(String str) {
        String normalized = Normalizer.normalize(str, Normalizer.Form.NFD);
        // Replace Turkish dotless i and dotted I explicitly if needed
        normalized = normalized.replace('ı', 'i').replace('İ', 'I');
        return normalized.replaceAll("\\p{M}", "");
    }

    private static int parseYear(String title, String releaseDate) {
        if (releaseDate != null) {
            Matcher m = YEAR_PATTERN.matcher(releaseDate);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        if (title != null) {
            Matcher m = TITLE_YEAR_PATTERN.matcher(title);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static Type parseType(int typeCode, String title, String fallbackPoster, String intentUri) {
        if (title != null && SEASON_EPISODE_PATTERN.matcher(title).find()) {
            return Type.SERIES;
        }

        // Check poster badges (e.g. Amazon Prime Video badge overlays "YENİ DİZİLER", "YENİ SEZON", "YENİ FİLM")
        if (fallbackPoster != null) {
            String decoded = fallbackPoster;
            try {
                decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
                decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
            } catch (Exception ignored) {}
            String lp = stripDiacritics(decoded).toLowerCase(Locale.ROOT);
            if (lp.contains("dizi") || lp.contains("series") || lp.contains("sezon") || lp.contains("season")) {
                return Type.SERIES;
            }
            if (lp.contains("film") || lp.contains("movie")) {
                return Type.MOVIE;
            }
        }

        if (intentUri != null && (intentUri.contains("youtube.com") || intentUri.contains("youtu.be"))) {
            return Type.CLIP;
        }

        if (typeCode == 0) return Type.MOVIE;
        if (typeCode == 1 || typeCode == 2) return Type.SERIES;
        if (typeCode == 3) return Type.EPISODE;
        if (typeCode == 4) {
            // Streaming Leanback preview clips represent promotional previews for movies or series
            return Type.UNKNOWN;
        }

        return Type.UNKNOWN;
    }

    /** The Netflix title id that a row's intent names, else null. */
    static String netflixIdIn(String intentUri) {
        if (intentUri == null || intentUri.isEmpty()) return null;
        Matcher m = NETFLIX_ID.matcher(intentUri);
        return m.find() ? m.group(1) : null;
    }

    /** The verified 11-character YouTube video id that a row's intent names, else null. */
    static String youtubeIdIn(String intentUri) {
        if (intentUri == null || intentUri.isEmpty()) return null;
        Matcher m = YOUTUBE_ID.matcher(intentUri);
        return m.find() ? m.group(1) : null;
    }

    private static String extractExternalId(String intentUri) {
        if (intentUri == null || intentUri.isEmpty()) return null;

        String netflix = netflixIdIn(intentUri);
        if (netflix != null) return "netflix:" + netflix;

        String youtube = youtubeIdIn(intentUri);
        if (youtube != null) return "youtube:" + youtube;

        Matcher m = AMAZON_GTI.matcher(intentUri);
        if (m.find()) return "amazon:" + m.group(1);

        m = IMDB_ID.matcher(intentUri);
        if (m.find()) return "imdb:" + m.group(1);

        m = TMDB_ID.matcher(intentUri);
        if (m.find()) return "tmdb:" + m.group(1);

        return null;
    }
}
