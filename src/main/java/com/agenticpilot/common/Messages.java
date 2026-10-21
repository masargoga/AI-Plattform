package com.agenticpilot.common;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;

/** English texts from the message bundle for places without a UI locale, such as API error messages. */
public final class Messages {

    private static final Properties BUNDLE = load();

    private Messages() {
    }

    private static Properties load() {
        Properties p = new Properties();
        try (var in = Messages.class.getResourceAsStream("/vaadin-i18n/translations.properties")) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Message bundle could not be read", e);
        }
        return p;
    }

    public static String english(String key, Object... args) {
        String text = BUNDLE.getProperty(key);
        if (text == null) {
            return key;
        }
        return args == null || args.length == 0 ? text : MessageFormat.format(text, args);
    }
}
