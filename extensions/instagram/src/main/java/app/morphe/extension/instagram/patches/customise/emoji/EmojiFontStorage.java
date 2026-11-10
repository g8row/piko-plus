/*
 * Copyright (C) 2026 piko-plus <https://github.com/g8row/piko-plus>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.customise.emoji;

import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.morphe.extension.instagram.settings.RestartPolicy;
import app.morphe.extension.instagram.settings.SettingsRestart;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The downloaded emoji font: one file in the app's private storage, plus a small file naming which
 * {@link Source} it came from. Downloads go to a temporary file that only replaces the current font
 * once it is complete and loads as a font, so a failed or cancelled download leaves the old one.
 */
public final class EmojiFontStorage {

    private EmojiFontStorage() {
    }

    /** Fonts that can be downloaded. All are CBDT colour bitmap fonts, which Android can draw. */
    public enum Source {
        APPLE("Apple", 116,
                "https://github.com/samuelngs/apple-emoji-ttf/releases/latest/download/AppleColorEmoji-Linux.ttf"),
        NOTO("Google Noto", 11,
                "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/2D/fonts/NotoColorEmoji.ttf"),
        TWEMOJI("Twemoji", 7,
                "https://github.com/MawCeron/twemoji-cbdt/releases/latest/download/TwemojiCBDT.ttf"),
        FLUENT("Microsoft Fluent", 25,
                "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/FluentEmojiMagisk/main/system/fonts/fluent.ttf");

        public final String label;
        public final int approximateMegabytes;
        public final String url;

        Source(String label, int approximateMegabytes, String url) {
            this.label = label;
            this.approximateMegabytes = approximateMegabytes;
            this.url = url;
        }
    }

    public interface Progress {
        /** Bytes so far, and the total or -1 when the server did not say. */
        void onProgress(long done, long total);

        boolean isCancelled();
    }

    public enum Result {DONE, CANCELLED, NOT_A_FONT, FAILED}

    private static final String FILE_NAME = "piko_emoji_font.ttf";
    private static final String SOURCE_FILE_NAME = "piko_emoji_font.source";
    private static final String RESTART_KEY = "piko_emoji_font";
    private static final long MAX_SIZE_BYTES = 300L * 1024 * 1024;

    private static File dir() {
        return Utils.getContext().getFilesDir();
    }

    private static File file() {
        return new File(dir(), FILE_NAME);
    }

    private static File sourceFile() {
        return new File(dir(), SOURCE_FILE_NAME);
    }

    public static boolean hasFont() {
        File file = file();
        return file.isFile() && file.length() > 0;
    }

    /** The source of the stored font, or null when there is none. */
    public static Source current() {
        if (!hasFont()) {
            return null;
        }
        try {
            String name = new String(Files.readAllBytes(sourceFile().toPath()), StandardCharsets.UTF_8).trim();
            return Source.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    static String describe() {
        File file = file();
        return file + " exists=" + file.exists() + " bytes=" + file.length() + " source=" + current();
    }

    /**
     * The font as a custom fallback family. Minikin always picks a custom fallback family that has
     * the glyph; a plain typeface loses emoji to the system emoji font, which is tagged as an emoji
     * font (und-Zsye) and wins font selection for emoji even inside a span.
     */
    static Typeface loadTypeface() {
        if (!hasFont()) {
            return null;
        }
        try {
            android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(file()).build();
            android.graphics.fonts.FontFamily family = new android.graphics.fonts.FontFamily.Builder(font).build();
            return new Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build();
        } catch (Exception e) {
            Logger.printException(() -> "Failed to load the emoji font as a fallback family", e);
            return parse(file());
        }
    }

    /** Removes the stored font; the system emoji are back after a restart. */
    public static boolean delete() {
        Source previous = current();
        boolean deleted = !file().exists() || file().delete();
        sourceFile().delete();
        if (deleted) {
            SettingsRestart.markChanged(RESTART_KEY, RestartPolicy.REQUIRED, name(previous), null);
        }
        return deleted;
    }

    /** Downloads a font, blocking; call off the main thread. */
    public static Result download(Source source, Progress progress) {
        File temp = new File(dir(), FILE_NAME + ".part");
        HttpURLConnection connection = null;
        try {
            connection = open(source.url);
            long total = connection.getContentLengthLong();
            if (total > MAX_SIZE_BYTES) {
                return Result.FAILED;
            }
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (progress.isCancelled()) {
                        return Result.CANCELLED;
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    if (done > MAX_SIZE_BYTES) {
                        return Result.FAILED;
                    }
                    if (done - lastReport > 256 * 1024) {
                        lastReport = done;
                        progress.onProgress(done, total);
                    }
                }
                progress.onProgress(done, total);
                if (total > 0 && done != total) {
                    throw new IOException("Download ended early: " + done + " of " + total);
                }
            }

            if (!isFontHeader(temp) || parse(temp) == null) {
                return Result.NOT_A_FONT;
            }

            Source previous = current();
            if (!temp.renameTo(file())) {
                throw new IOException("Could not move the emoji font into place");
            }
            try (OutputStream out = new FileOutputStream(sourceFile())) {
                out.write(source.name().getBytes(StandardCharsets.UTF_8));
            }
            SettingsRestart.markChanged(RESTART_KEY, RestartPolicy.REQUIRED, name(previous), source.name());
            return Result.DONE;
        } catch (Exception e) {
            Logger.printException(() -> "Failed to download the emoji font " + source.name(), e);
            return Result.FAILED;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /** Opens the URL, following redirects by hand: GitHub release downloads redirect to another host. */
    private static HttpURLConnection open(String url) throws IOException {
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "piko-plus");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without a location");
                }
                url = new URL(new URL(url), location).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("HTTP " + code + " for " + url);
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    private static String name(Source source) {
        return source == null ? null : source.name();
    }

    private static boolean isFontHeader(File file) {
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            if (in.read(header) != 4) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        String magic = new String(header, 0, 4, StandardCharsets.ISO_8859_1);
        return magic.equals("OTTO") || magic.equals("true") || magic.equals("ttcf")
                || (header[0] == 0x00 && header[1] == 0x01 && header[2] == 0x00 && header[3] == 0x00);
    }

    private static Typeface parse(File file) {
        try {
            return new Typeface.Builder(file).build();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
