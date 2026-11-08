package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.Locale;

/**
 * tvOS-style Top Shelf content hierarchy.
 * Content apps (Netflix, Prime Video, YouTube, etc.) feature content titles and metadata,
 * keeping the service brand secondary.
 */
final class TopShelf {

    static final class Content {
        final String brand;
        final String title;
        final String meta;

        Content(String brand, String title, String meta) {
            this.brand = brand;
            this.title = title;
            this.meta = meta;
        }
    }

    private TopShelf() {
    }

    static Content get(Context context, String pkg, String label) {
        if (pkg == null) {
            return new Content(label != null ? label.toUpperCase(Locale.ROOT) : "", label != null ? label : "", "");
        }

        // 1. Try querying live Leanback preview programs if accessible on this TV
        Content live = queryPreviewProgram(context, pkg, label);
        if (live != null) return live;

        boolean isTr = "tr".equalsIgnoreCase(Locale.getDefault().getLanguage());
        String p = pkg.toLowerCase(Locale.ROOT);

        // 2. Curated tvOS content presets for primary streaming services
        if (p.contains("netflix")) {
            return new Content(
                    "NETFLIX",
                    "Şahmaran",
                    isTr ? "1. Sezon • Fantastik Drama • 4K HDR • Netflix Orijinal" : "Season 1 • Fantasy Drama • 4K HDR • Netflix Original"
            );
        } else if (p.contains("amazon") || p.contains("prime")) {
            return new Content(
                    "PRIME VIDEO",
                    isTr ? "Yüzüklerin Efendisi: Güç Yüzükleri" : "The Rings of Power",
                    isTr ? "2. Sezon • Epik Fantezi • 4K HDR • Amazon Orijinal" : "Season 2 • Epic Fantasy • 4K HDR • Amazon Original"
            );
        } else if (p.contains("youtube") && !p.contains("music")) {
            return new Content(
                    "YOUTUBE",
                    "Macbook PROLED XDR",
                    isTr ? "Marques Brownlee • Teknoloji İncelemesi" : "Marques Brownlee • Tech Review"
            );
        } else if (p.contains("music")) {
            return new Content(
                    isTr ? "MÜZİK" : "MUSIC",
                    isTr ? "Kişisel Çalma Listeleri" : "Personalized Playlists",
                    isTr ? "Milyonlarca Şarkı • Albümler • Sanatçılar" : "Millions of Songs • Albums • Artists"
            );
        } else if (p.contains("turkcell") || p.contains("ott")) {
            return new Content(
                    "TV+",
                    isTr ? "Canlı TV & Seç-İzle" : "Live TV & On Demand",
                    isTr ? "Yüzlerce Kanal • Popüler Dizi ve Filmler" : "Hundreds of Channels • Hit Movies & Series"
            );
        } else if (p.contains("ttnet") || p.contains("tivibu")) {
            return new Content(
                    "TİVİBU GO",
                    isTr ? "Canlı Yayın & Sinema" : "Live Broadcast & Cinema",
                    isTr ? "Spor Kanalları • Vizyon Filmleri • HD" : "Sports Channels • Hit Movies • HD"
            );
        } else if (p.contains("browser") || p.contains("tcl")) {
            return new Content(
                    "BROWSEHERE",
                    isTr ? "Web'de Gezinin" : "Explore the Web",
                    isTr ? "TV İçin Optimize Edilmiş Tarayıcı • Hızlı Web" : "Optimized TV Browser • Fast & Secure"
            );
        } else if (isContentApp(p)) {
            return new Content(
                    label != null ? label.toUpperCase(Locale.ROOT) : (isTr ? "İÇERİK" : "CONTENT"),
                    isTr ? "Öne Çıkan İçerikler" : "Featured Content",
                    isTr ? "İzlemeye Başlayın • HD & 4K" : "Stream Now • HD & 4K"
            );
        }

        // 3. General application fallback (brand secondary, app label primary)
        return new Content(
                label != null ? label.toUpperCase(Locale.ROOT) : "",
                label != null ? label : "",
                isTr ? "Uygulamayı açmak için seçin" : "Select to open application"
        );
    }

    private static boolean isContentApp(String p) {
        return p.contains("video") || p.contains("movie") || p.contains("tv")
                || p.contains("disney") || p.contains("hbo") || p.contains("max")
                || p.contains("stream") || p.contains("cinema") || p.contains("film");
    }

    private static Content queryPreviewProgram(Context context, String pkg, String label) {
        try {
            Uri uri = Uri.parse("content://android.media.tv/preview_program");
            String[] projection = {"title", "description", "genre"};
            Cursor c = context.getContentResolver().query(uri, projection, "package_name=?",
                    new String[]{pkg}, "weight DESC");
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int titleIdx = c.getColumnIndex("title");
                        int descIdx = c.getColumnIndex("description");
                        int genreIdx = c.getColumnIndex("genre");
                        String title = titleIdx >= 0 ? c.getString(titleIdx) : null;
                        String desc = descIdx >= 0 ? c.getString(descIdx) : null;
                        String genre = genreIdx >= 0 ? c.getString(genreIdx) : null;
                        if (title != null && !title.trim().isEmpty()) {
                            String meta = desc != null && !desc.isEmpty() ? desc : (genre != null ? genre : "4K Ultra HD");
                            return new Content(label != null ? label.toUpperCase(Locale.ROOT) : "", title, meta);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
