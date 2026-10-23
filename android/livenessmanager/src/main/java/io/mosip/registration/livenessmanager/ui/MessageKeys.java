package io.mosip.registration.livenessmanager.ui;

import android.content.Context;

import java.text.MessageFormat;

/**
 * Resolves the liveness message keys (API.md §7) to this module's string resources: the key with its dots
 * replaced by underscores, so {@code liveness.prompt.turn_left} is {@code R.string.liveness_prompt_turn_left}.
 */
public final class MessageKeys {

    private MessageKeys() {
    }

    /**
     * @param key a message key, for example {@code liveness.prompt.turn_left}
     * @return the string resource name for it
     */
    static String resourceName(String key) {
        return key.replace('.', '_');
    }

    /**
     * @param context resolves the resource
     * @param key     the message key
     * @param args    values for the {@code {0}}, {@code {1}} placeholders
     * @return the text, or the key itself when the module has no string for it, so the screen never fails on
     *         a key the engine adds later
     */
    public static String text(Context context, String key, Object... args) {
        int id = context.getResources().getIdentifier(resourceName(key), "string", context.getPackageName());
        if (id == 0) {
            return key;
        }
        String text = context.getString(id);
        return args.length == 0 ? text : MessageFormat.format(text, args);
    }
}
