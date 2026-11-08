package com.gathin.tvhome;

/** Pure rules for the content the TV provider actually exposes. */
final class ShelfLogic {
    private static final long DAY_MS = 86_400_000L;
    private ShelfLogic() {}

    static boolean previewEligible(int type, int live) {
        // Unknown live state is excluded rather than claiming it is on-demand.
        return live == 0 && type >= 0 && type <= 4;
    }

    static boolean continueEligible(int type, long position, long duration) {
        return type == 0 && position > 0 && (duration <= 0 || position < duration);
    }

    static int shelfTop(int dockTop, int height, int headerBottom, int gap) {
        return Math.max(headerBottom, dockTop - gap - height);
    }

    static boolean httpsImage(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (RuntimeException | java.net.URISyntaxException e) {
            return false;
        }
    }

    static String upgradeImageUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.contains("images-amazon.com") || url.contains("media-amazon.com") || url.contains("ssl-images-amazon.com")) {
            return url.replaceAll("_(SX|SY|UX|UY)[0-9]+_", "_SX1920_");
        }
        return url;
    }

    static boolean samePackage(String source, String destination) {
        return source != null && !source.isEmpty() && source.equals(destination);
    }

    private static final java.util.Set<String> STOP_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "and", "of", "in", "on", "at", "to", "for", "with",
            "bir", "ve", "ile", "de", "da", "der", "die", "das", "le", "la", "les"
    ));

    static java.util.Set<String> significantTokens(String title) {
        if (title == null) return java.util.Collections.emptySet();
        String s = title.toLowerCase(java.util.Locale.ROOT);
        s = s.replaceAll("[:\\-_–—|·•,/]", " ");
        s = s.replaceAll("\\b(season|sezon|episode|bölüm|part|kısım|vol|volume|s\\d+|e\\d+)\\s*\\d*\\b", " ");
        s = s.replaceAll("[^a-z0-9çğıöşü\\s]", "");
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (String w : s.split("\\s+")) {
            if (w.length() >= 2 && !STOP_WORDS.contains(w)) {
                tokens.add(w);
            }
        }
        return tokens;
    }

    static boolean contentMatches(String a, String b) {
        if (a == null || b == null) return false;
        java.util.Set<String> s1 = significantTokens(a);
        java.util.Set<String> s2 = significantTokens(b);
        if (s1.isEmpty() || s2.isEmpty()) return false;
        int inter = 0;
        for (String token : s1) {
            if (s2.contains(token)) inter++;
        }
        if (inter >= 2) return true;
        return s1.size() == 1 && s2.size() == 1 && s1.equals(s2);
    }

    static long progressScore(long position, long duration) {
        if (position <= 0 || duration <= 0) return 0;
        if (position >= duration) return 200;
        double ratio = (double) position / (double) duration;
        if (ratio < 0.05) return 500;
        if (ratio <= 0.85) {
            return (long) (3500 * (1.0 - Math.abs(ratio - 0.5) * 0.8));
        }
        return 1000;
    }

    static long discoveryAndRepetitionAdjustment(long id, long day, boolean hasDirectWatchHistory) {
        long cyclePhase = Math.floorMod(id ^ (day * 31L), 7L);
        if (!hasDirectWatchHistory) {
            if (cyclePhase <= 1) return 1500; // discovery bonus for new / unengaged catalog
            if (cyclePhase >= 5) return -1200; // repetition penalty for stale items
            return 0;
        } else {
            long activePhase = Math.floorMod(id ^ (day * 17L), 14L);
            if (activePhase >= 12) return -600;
            return 0;
        }
    }

    static long scoreItem(ShelfData.Item item, java.util.List<ShelfData.Item> watching, long day, long now, long lastAppOpened) {
        return scoreItem(item, watching, day, now, lastAppOpened, null);
    }

    static long scoreItem(ShelfData.Item item, java.util.List<ShelfData.Item> watching, long day, long now,
                          long lastAppOpened, ShelfInteractions.Summary summary) {
        ShelfData.Item matched = null;
        if (watching != null) {
            for (ShelfData.Item w : watching) {
                if (item.pkg != null && item.pkg.equals(w.pkg) && contentMatches(item.title, w.title)) {
                    if (matched == null || w.engaged > matched.engaged) {
                        matched = w;
                    }
                }
            }
        }

        long contentAffinity = 0;
        long progressBonus = progressScore(item.position, item.duration);
        boolean hasDirectMatch = matched != null;

        if (hasDirectMatch) {
            long recencyDays = matched.engaged > 0 ? Math.max(0, (now - matched.engaged) / DAY_MS) : 180;
            long recencyDecay = Math.min(recencyDays, 90) * 80;
            int type = matched.watchNextType;
            long baseTypeScore;
            if (type == 0) baseTypeScore = 20_000;       // Continue watching
            else if (type == 1) baseTypeScore = 18_000;  // Next episode
            else if (type == 2) baseTypeScore = 15_000;  // New episode from followed series
            else if (type == 3) baseTypeScore = 12_000;  // Watchlist
            else baseTypeScore = 10_000;

            contentAffinity = Math.max(3_000, baseTypeScore - recencyDecay);
            if (progressBonus == 0) {
                progressBonus = progressScore(matched.position, matched.duration);
            }
        } else if (watching != null) {
            long pkgWatched = 0;
            for (ShelfData.Item w : watching) {
                if (item.pkg != null && item.pkg.equals(w.pkg) && w.engaged > pkgWatched) pkgWatched = w.engaged;
            }
            if (pkgWatched > 0) {
                long age = Math.max(0, (now - pkgWatched) / DAY_MS);
                long base = age == 0 ? 4_000 : (age == 1 ? 3_200 : Math.max(0, 2_800 - Math.min(age, 365) * 20));
                contentAffinity = base;
            }
        }

        // App launch warmth: small general nudge, NOT proof that a specific series was watched
        long appOpenedBonus = 0;
        if (lastAppOpened > 0) {
            long openedAge = Math.max(0, (now - lastAppOpened) / DAY_MS);
            if (openedAge < 30) appOpenedBonus = Math.max(0, 500 - openedAge * 15);
        }

        long discoveryOrPenalty = discoveryAndRepetitionAdjustment(item.id, day, hasDirectMatch);

        // Daily deterministic jitter (0..499)
        long z = item.id ^ (day * 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        long jitter = Long.remainderUnsigned(z ^ (z >>> 31), 500);

        return contentAffinity + progressBonus + appOpenedBonus + discoveryOrPenalty + jitter + interactionAdjustment(summary, now);
    }

    private static long interactionAdjustment(ShelfInteractions.Summary summary, long now) {
        if (summary == null) return 0;
        long strength, timestamp;
        if (summary.selections > 0) {
            strength = Math.min(summary.selections, 3) * 500;
            timestamp = summary.lastSelectionAt;
        } else {
            // ponytail: aggregate-only penalty for never-selected content; per-exposure fatigue needs richer summaries.
            strength = -Math.min(Math.max(0, summary.impressions - 2), 6) * 50;
            timestamp = summary.lastImpressionAt;
        }
        if (timestamp <= 0) return 0;
        double age = Math.max(0L, now - timestamp) / (7.0 * DAY_MS);
        return (long) (strength / (1.0 + age));
    }

    static long score(long id, long day, long now, long lastWatched, long lastOpened) {
        long age = lastWatched > 0 ? Math.max(0, (now - lastWatched) / DAY_MS) : Long.MAX_VALUE;
        long watched = age < 365 ? 20_000 - Math.min(age, 365) * 20 : 0;
        long openedAge = lastOpened > 0 ? Math.max(0, (now - lastOpened) / DAY_MS) : Long.MAX_VALUE;
        long opened = openedAge < 90 ? 5_000 - openedAge * 40 : 0;
        // ponytail: daily deterministic rotation, not learned preferences; add a model only with measured need.
        long z = id ^ (day * 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return watched + opened + Long.remainderUnsigned(z ^ (z >>> 31), 1000);
    }
}
