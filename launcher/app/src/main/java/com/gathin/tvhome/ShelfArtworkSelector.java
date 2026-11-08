package com.gathin.tvhome;

/**
 * Lightweight tvOS 27 artwork quality selector for Top Shelf.
 * Evaluates candidate backdrops against 1280x720 tvOS layout geometry:
 * 1. Title zone contrast (top-left x: 0..40%, y: 0..38% - dark/calm for white text readability)
 * 2. Dock contrast plus the share of high-contrast detail hidden by the dock band
 * 3. Subject framing (subject presence in center-right vs top-left)
 * 4. Geometry & community score (16:9 ratio, resolution, likes)
 *
 * Consumes < 10 KiB RAM per evaluation via 64x36 downsampled probe, strictly
 * protecting the 1 GB RAM budget of Mi TV Stick.
 */
final class ShelfArtworkSelector {

    static final int PROBE_WIDTH = 64;
    static final int PROBE_HEIGHT = 36;

    interface PixelSource {
        int getPixel(int x, int y);
        int getWidth();
        int getHeight();
    }

    static final class Evaluation {
        final float totalScore;
        final float titleContrast;
        final float dockContrast;
        final float dockOverlap;
        final float framing;
        final boolean is16x9;

        Evaluation(float totalScore, float titleContrast, float dockContrast, float dockOverlap,
                   float framing, boolean is16x9) {
            this.totalScore = totalScore;
            this.titleContrast = titleContrast;
            this.dockContrast = dockContrast;
            this.dockOverlap = dockOverlap;
            this.framing = framing;
            this.is16x9 = is16x9;
        }
    }

    private ShelfArtworkSelector() {}

    /**
     * Evaluates a candidate image against tvOS 27 layout constraints.
     */
    static Evaluation evaluate(PixelSource source, int originalWidth, int originalHeight, int likes) {
        if (originalWidth <= 0 || originalHeight <= 0) {
            return new Evaluation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, false);
        }

        float ratio = (float) originalWidth / originalHeight;
        boolean is16x9 = Math.abs(ratio - 1.778f) <= 0.05f;

        // Strict rejection for non-16:9 aspect ratios (e.g. 2:3 vertical posters or 16:10 banners)
        if (!is16x9) {
            return new Evaluation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, false);
        }

        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            // Metadata-only fallback: 16:9 verified, score weighted by community likes
            float likesBonus = Math.min(Math.max(0, likes), 10) * 0.03f;
            float metaScore = 0.60f + likesBonus;
            return new Evaluation(metaScore, 0.5f, 0.5f, 0.0f, 0.5f, true);
        }

        int width = source.getWidth();
        int height = source.getHeight();

        // Zone 1: Top-Left Title Zone (x: 0..40%, y: 0..38%)
        int tlW = Math.max(1, Math.min(Math.round(width * 0.40f), width));
        int tlH = Math.max(1, Math.min(Math.round(height * 0.38f), height));
        float tlLumSum = 0.0f;
        int tlCount = 0;

        for (int y = 0; y < tlH; y++) {
            for (int x = 0; x < tlW; x++) {
                tlLumSum += luminance(source.getPixel(x, y));
                tlCount++;
            }
        }
        float tlLum = tlCount > 0 ? tlLumSum / tlCount : 0.5f;

        // Title clutter (variance in top-left)
        float varianceSum = 0.0f;
        for (int y = 0; y < tlH; y++) {
            for (int x = 0; x < tlW; x++) {
                float diff = luminance(source.getPixel(x, y)) - tlLum;
                varianceSum += diff * diff;
            }
        }
        float tlVariance = tlCount > 0 ? varianceSum / tlCount : 0.0f;
        float tlClutter = (float) Math.sqrt(tlVariance);

        // Zone 2: Bottom Dock Zone (x: 0..100%, y: 72%..100%)
        int dockStartY = Math.max(0, Math.min(Math.round(height * 0.72f), height - 1));
        float dockLumSum = 0.0f;
        int dockCount = 0;

        for (int y = dockStartY; y < height; y++) {
            for (int x = 0; x < width; x++) {
                dockLumSum += luminance(source.getPixel(x, y));
                dockCount++;
            }
        }
        float dockLum = dockCount > 0 ? dockLumSum / dockCount : 0.5f;

        // Zone 3: Center-Right Subject Framing Zone (x: 35%..100%, y: 10%..75%)
        int srStartX = Math.max(0, Math.min(Math.round(width * 0.35f), width - 1));
        int srStartY = Math.max(0, Math.min(Math.round(height * 0.10f), height - 1));
        int srEndY = Math.max(srStartY + 1, Math.min(Math.round(height * 0.75f), height));

        // 1. Title contrast: target dark & calm top-left for white text readability
        float titleContrast;
        if (tlLum < 0.35f) {
            titleContrast = 1.0f;
        } else if (tlLum < 0.65f) {
            titleContrast = 1.0f - (tlLum - 0.35f) / 0.30f;
        } else {
            titleContrast = 0.0f; // Blinding bright white/yellow
        }

        // 2. Title clutter score: smooth gradient = good, busy variance = penalty
        float clutterScore = Math.max(0.0f, 1.0f - (tlClutter * 2.5f));

        // 3. Dock contrast score: darker bottom = better glass contrast
        float dockScore;
        if (dockLum < 0.40f) {
            dockScore = 1.0f;
        } else if (dockLum < 0.70f) {
            dockScore = 1.0f - (dockLum - 0.40f) / 0.30f;
        } else {
            dockScore = 0.1f;
        }

        // Mi Stick 1280x720 glass/card band: approximately (32,505)-(1248,660).
        // Sample a bounded lattice even if the caller supplies a larger bitmap.
        // ponytail: local contrast estimates detail, not faces/objects; use semantic saliency
        // only if measured false positives justify its extra model and memory cost.
        int probeW = Math.min(PROBE_WIDTH, width);
        int probeH = Math.min(PROBE_HEIGHT, height);
        float allDetail = 0.0f;
        float hiddenDetail = 0.0f;
        float srDetail = 0.0f;
        float tlDetail = 0.0f;
        int hiddenSamples = 0;
        int samples = 0;
        for (int y = 1; y < probeH - 1; y++) {
            int sy = Math.min(height - 1, (int) ((y + 0.5f) * height / probeH));
            int previousY = (int) ((y - 0.5f) * height / probeH);
            float screenY = (y + 0.5f) * 720 / probeH;
            for (int x = 1; x < probeW - 1; x++) {
                int sx = Math.min(width - 1, (int) ((x + 0.5f) * width / probeW));
                int previousX = (int) ((x - 0.5f) * width / probeW);
                float lum = luminance(source.getPixel(sx, sy));
                float gradient = (Math.abs(lum - luminance(source.getPixel(previousX, sy)))
                        + Math.abs(lum - luminance(source.getPixel(sx, previousY)))) * 0.5f;
                // Ignore weak gradients/grain; avoid a large penalty for near-flat images.
                float detail = Math.max(0.0f, gradient - 0.025f);
                allDetail += detail;
                samples++;
                float screenX = (x + 0.5f) * 1280 / probeW;
                if (screenX >= 32 && screenX < 1248 && screenY >= 505 && screenY < 660) {
                    hiddenDetail += detail;
                    hiddenSamples++;
                }
                if (sx < tlW && sy < tlH) {
                    tlDetail += detail;
                }
                if (sx >= srStartX && sy >= srStartY && sy < srEndY) {
                    srDetail += detail;
                }
            }
        }
        // Penalize concentration beyond the covered area, not uniformly distributed texture.
        float coveredFraction = samples > 0 ? (float) hiddenSamples / samples : 0.0f;
        float dockOverlap = Math.max(0.0f, hiddenDetail - allDetail * coveredFraction)
                / Math.max(1.0f, allDetail) / (1.0f - coveredFraction);

        // 4. Framing score: regional detail balance in center-right narrative zone vs top-left title zone
        // ponytail: detail ratio replaces brightness heuristic; subject presence is contrast/edges, not luminance.
        float detailSum = srDetail + tlDetail;
        float framingScore = detailSum > 0.0f
                ? Math.min(1.0f, Math.max(0.2f, srDetail / (detailSum + 0.5f)))
                : 0.5f;

        // 5. Community likes tie-breaker bonus (max +0.005, only breaks ties between visually close candidates)
        // ponytail: likesBonus is a pure tie-breaker (max 0.005); visual score difference > 0.005 always dominates
        float likesBonus = Math.min(Math.max(0, likes), 10) * 0.0005f;

        float total = (titleContrast * 0.35f)
                + (clutterScore * 0.20f)
                + (dockScore * 0.20f)
                + (framingScore * 0.15f)
                + likesBonus
                - (dockOverlap * 0.25f);

        total = Math.min(1.0f, Math.max(0.0f, total));

        return new Evaluation(total, titleContrast, dockScore, dockOverlap, framingScore, true);
    }

    /**
     * Strict adaptation guard to prevent Ghosts US / UK and sequel confusion.
     */
    static boolean isAdaptationCompatible(ShelfIdentity.Query query, int candidateYear, String candidateExternalId) {
        if (query == null) return false;

        // Ghosts disambiguation:
        // US 2021 (TheTVDB 394200, IMDb tt11337858) vs UK 2019 (TheTVDB 361701, IMDb tt8594324)
        if ("ghosts".equalsIgnoreCase(query.title.trim())) {
            if (query.year == 2021 && candidateYear == 2019) return false;
            if (query.year == 2019 && candidateYear == 2021) return false;
            if (query.externalId != null && candidateExternalId != null) {
                if (query.externalId.contains("394200") && candidateExternalId.contains("361701")) return false;
                if (query.externalId.contains("361701") && candidateExternalId.contains("394200")) return false;
                if (query.externalId.contains("tt11337858") && candidateExternalId.contains("tt8594324")) return false;
                if (query.externalId.contains("tt8594324") && candidateExternalId.contains("tt11337858")) return false;
            }
        }

        // Strict year difference rule: series/movies cannot mismatch by > 1 year unless multi-season
        if (query.year > 0 && candidateYear > 0) {
            int diff = Math.abs(query.year - candidateYear);
            if (diff > 1 && !query.title.toLowerCase(java.util.Locale.ROOT).contains("sezon")
                    && !query.title.toLowerCase(java.util.Locale.ROOT).contains("season")) {
                return false;
            }
        }

        return true;
    }

    static float luminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        // Standard Rec. 709 relative luminance
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f;
    }
}
