package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.regex.Pattern;

public final class FilenameUtils {
    private static final String CHARSET_MOST_SPECIAL = "[\\n\\r|?*<\":\\\\>/]+";
    private static final String CHARSET_ONLY_LETTERS_AND_DIGITS = "[^\\w\\d]+";

    private FilenameUtils() {
    }

    /**
     * #143 #44 #42 #22: make sure that the filename does not contain illegal chars.
     *
     * @param context the context to retrieve strings and preferences from
     * @param title   the title to create a filename from
     * @return the filename
     */
    public static String createFilename(final Context context, final String title) {
        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);

        final String charsetLd = context.getString(R.string.charset_letters_and_digits_value);
        final String charsetMs = context.getString(R.string.charset_most_special_value);
        final String defaultCharset = context.getString(R.string.default_file_charset_value);

        final String replacementChar = "_";
        String selectedCharset = sharedPreferences.getString(
                context.getString(R.string.settings_file_charset_key), null);

        final String charset;

        if (selectedCharset == null || selectedCharset.isEmpty()) {
            selectedCharset = defaultCharset;
        }

        if (selectedCharset.equals(charsetLd)) {
            charset = CHARSET_ONLY_LETTERS_AND_DIGITS;
        } else if (selectedCharset.equals(charsetMs)) {
            charset = CHARSET_MOST_SPECIAL;
        } else {
            charset = selectedCharset; // Is the user using a custom charset?
        }

        final Pattern pattern = Pattern.compile(charset);

        return createFilename(title, pattern, replacementChar);
    }

    /**
     * Create a valid filename.
     *
     * @param title             the title to create a filename from
     * @param invalidCharacters patter matching invalid characters
     * @param replacementChar   the replacement
     * @return the filename
     */
    private static String createFilename(final String title, final Pattern invalidCharacters,
            final String replacementChar) {
        return title.replaceAll(invalidCharacters.pattern(), replacementChar);
    }

    /**
     * Builds a download filename from a template and stream metadata.
     * Always sanitizes the result for filesystem safety.
     * Does not append the file extension.
     */
    public static String buildFilename(String template, org.schabi.newpipe.extractor.stream.StreamInfo info) {
        String date = "";
        if (info.getUploadDate() != null) {
            java.util.Calendar cal = info.getUploadDate().date();
            date = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                    cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH));
        }
        String dateShort = date.replace("-", "");
        if (dateShort.length() > 6) {
            dateShort = dateShort.substring(0, 6);
        }

        String name = template
                .replace("{title}", sanitize(info.getName()))
                .replace("{channel}", sanitize(nvl(info.getUploaderName())))
                .replace("{date}", date)
                .replace("{date_short}", dateShort)
                .replace("{id}", sanitize(normalizeId(info.getId())));

        name = name.trim();
        if (name.isEmpty())
            name = sanitize(info.getName()); // fallback
        return name;
    }

    /**
     * Normalises a stream ID for use in a filename.
     * <p>
     * BiliBili's link handler always encodes the page number into the ID as a query
     * parameter,
     * producing IDs like {@code BV1abc123?p=1} or {@code BV1abc123?p=3}.
     * After {@link #sanitize} replaces {@code ?} with {@code _} this becomes
     * {@code BV1abc123_p=1},
     * which looks abnormal for single-page videos where {@code p=1} is always the
     * implicit default.
     * </p>
     * <ul>
     * <li>{@code ?p=1} → stripped (single page, redundant)</li>
     * <li>{@code ?p=2+} → kept, becomes {@code _p=2} after sanitize (distinguishes
     * parts)</li>
     * <li>{@code #timestamp=N} → always stripped (not meaningful in a
     * filename)</li>
     * </ul>
     */
    private static String normalizeId(final String id) {
        if (id == null)
            return "";
        String result = id;
        // Strip timestamp fragment (always noise in a filename)
        final int hashIdx = result.indexOf('#');
        if (hashIdx >= 0) {
            result = result.substring(0, hashIdx);
        }
        // Strip ?p=1 only — keep ?p=N for N≥2
        if (result.endsWith("?p=1")) {
            result = result.substring(0, result.length() - "?p=1".length());
        }
        return result;
    }

    private static String sanitize(String s) {
        if (s == null)
            return "";
        return s.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
