package areaminer;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Server-side chat text in the player's game language. Players without the client mod receive these as plain
 * text, so they cannot rely on the client's own translations. Files: /areaminer/lang/&lt;code&gt;.properties.
 * Unsupported languages and missing keys fall back to English.
 */
public final class Messages {
    public static final Messages ENGLISH = new Messages(orEmpty(load("en_us")), null);
    /**
     * Bundled languages by language code (the part before "_"), loaded once. The language string comes from the
     * client, so nothing derived from it is cached or looked up beyond this fixed set.
     */
    private static final Map<String, Messages> BUNDLED = Map.of("en", ENGLISH, "ja", new Messages(orEmpty(load("ja_jp")), ENGLISH));

    private final Properties text;
    private final Messages fallback;

    private Messages(Properties text, Messages fallback) { this.text = text; this.fallback = fallback; }

    /** Locale codes such as "ja_jp", "ja_JP" or "ja-JP"; any language without a bundled file uses English. */
    public static Messages of(String locale) {
        if (locale == null) return ENGLISH;
        String code = locale.toLowerCase(Locale.ROOT).replace('-', '_');
        int split = code.indexOf('_');
        return BUNDLED.getOrDefault(split < 0 ? code : code.substring(0, split), ENGLISH);
    }
    private static Properties orEmpty(Properties properties) { return properties == null ? new Properties() : properties; }

    public String get(String key, Object... args) {
        String pattern = text.getProperty(key);
        if (pattern == null) return fallback != null ? fallback.get(key, args) : key;
        // Single quotes are literal in these files; MessageFormat would otherwise treat them as escapes.
        return args.length == 0 ? pattern : new MessageFormat(pattern.replace("'", "''"), Locale.ROOT).format(args);
    }


    private static Properties load(String code) {
        var stream = Messages.class.getResourceAsStream("/areaminer/lang/" + code + ".properties");
        if (stream == null) return null;
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        } catch (Exception e) {
            return null;
        }
    }
}
