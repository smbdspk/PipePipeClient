package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.regex.Pattern;

public final class FilenameUtils {
    private static final String CHARSET_MOST_SPECIAL = "[\\n\\r|?*<\":\\\\>/]+";
    private static final String CHARSET_ONLY_LETTERS_AND_DIGITS = "[^\\w\\d]+";
    private static final String CHARSET_ILLEGAL_FS = "[/\\\\:*?\"<>|]";

    private static final Pattern PATTERN_MOST_SPECIAL = Pattern.compile(CHARSET_MOST_SPECIAL);
    private static final Pattern PATTERN_LETTERS_DIGITS = Pattern.compile(CHARSET_ONLY_LETTERS_AND_DIGITS);
    private static final Pattern PATTERN_ILLEGAL_FS = Pattern.compile(CHARSET_ILLEGAL_FS);

    private FilenameUtils() {
    }

    public static String createFilename(final Context context, final String title) {
        Pattern pattern = getCharsetPattern(context);
        return title.replaceAll(pattern.pattern(), "_");
    }

    public static String sanitizeWithPreference(final Context context, final String s) {
        if (s == null) return "";
        return createFilename(context, s.trim());
    }

    public static String buildFilename(Context context, String template,
                                       org.schabi.newpipe.extractor.stream.StreamInfo info) {
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

        Pattern p = getCharsetPattern(context);
        String name = template
                .replace("{title}", sanitizeWithPattern(info.getName(), p))
                .replace("{channel}", sanitizeWithPattern(nvl(info.getUploaderName()), p))
                .replace("{date}", date)
                .replace("{date_short}", dateShort)
                .replace("{id}", sanitizeWithPattern(normalizeId(info.getId()), p));

        name = name.trim();
        if (name.isEmpty())
            name = sanitizeWithPattern(info.getName(), p);
        return name;
    }

    public static String buildFilename(String template,
                                       org.schabi.newpipe.extractor.stream.StreamInfo info) {
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
            name = sanitize(info.getName());
        return name;
    }

    private static String normalizeId(final String id) {
        if (id == null)
            return "";
        String result = id;
        final int hashIdx = result.indexOf('#');
        if (hashIdx >= 0) {
            result = result.substring(0, hashIdx);
        }
        if (result.endsWith("?p=1")) {
            result = result.substring(0, result.length() - "?p=1".length());
        }
        return result;
    }

    private static String sanitize(String s) {
        if (s == null)
            return "";
        return PATTERN_ILLEGAL_FS.matcher(s).replaceAll("_").trim();
    }

    private static String sanitizeWithPattern(String s, Pattern p) {
        if (s == null)
            return "";
        return p.matcher(s).replaceAll("_").trim();
    }

    private static Pattern getCharsetPattern(Context context) {
        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);

        final String charsetLd = context.getString(R.string.charset_letters_and_digits_value);
        final String charsetMs = context.getString(R.string.charset_most_special_value);
        final String defaultCharset = context.getString(R.string.default_file_charset_value);

        String selectedCharset = sharedPreferences.getString(
                context.getString(R.string.settings_file_charset_key), null);

        if (selectedCharset == null || selectedCharset.isEmpty()) {
            selectedCharset = defaultCharset;
        }

        if (selectedCharset.equals(charsetLd)) {
            return PATTERN_LETTERS_DIGITS;
        } else if (selectedCharset.equals(charsetMs)) {
            return PATTERN_MOST_SPECIAL;
        } else {
            return Pattern.compile(selectedCharset);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}