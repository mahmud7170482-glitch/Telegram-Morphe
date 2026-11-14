package app.morphe.extension.all.telegram;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Talks to Telegram's own classes through reflection, so this extension needs no Telegram code at build time
 * and a renamed class or method simply makes a feature switch itself off instead of crashing the app.
 */
final class TelegramBridge {
    private static final String USER_CONFIG = "org.telegram.messenger.UserConfig";
    private static final String APPLICATION_LOADER = "org.telegram.messenger.ApplicationLoader";
    private static final String LAUNCH_ACTIVITY = "org.telegram.ui.LaunchActivity";

    private TelegramBridge() {
    }

    private static Class<?> userConfigClass() throws ClassNotFoundException {
        return Class.forName(USER_CONFIG);
    }

    /** True when the Telegram classes this extension relies on exist. */
    static boolean isTelegram() {
        try {
            userConfigClass().getMethod("getInstance", int.class);
            Class.forName(LAUNCH_ACTIVITY);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Context appContext() {
        try {
            Field f = Class.forName(APPLICATION_LOADER).getField("applicationContext");
            return (Context) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static int maxAccounts() {
        try {
            return userConfigClass().getField("MAX_ACCOUNT_COUNT").getInt(null);
        } catch (Throwable t) {
            return 5;
        }
    }

    private static Object config(int account) throws Exception {
        return userConfigClass().getMethod("getInstance", int.class).invoke(null, account);
    }

    static boolean isActivated(int account) {
        try {
            return (Boolean) userConfigClass().getMethod("isClientActivated").invoke(config(account));
        } catch (Throwable t) {
            return false;
        }
    }

    static String label(int account) {
        try {
            Object user = userConfigClass().getMethod("getCurrentUser").invoke(config(account));
            if (user == null) return "Account " + (account + 1);
            String first = text(user, "first_name");
            String last = text(user, "last_name");
            String phone = text(user, "phone");
            String name = (first + " " + last).trim();
            if (name.isEmpty()) name = "Account " + (account + 1);
            return phone.isEmpty() ? name : name + "  +" + phone;
        } catch (Throwable t) {
            return "Account " + (account + 1);
        }
    }

    private static String text(Object user, String field) {
        try {
            Object v = user.getClass().getField(field).get(user);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static int selectedAccount() {
        try {
            return userConfigClass().getField("selectedAccount").getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Remembers {@code account} as the one Telegram opens on its next start. */
    static void rememberSelected(int account) throws Exception {
        userConfigClass().getField("selectedAccount").setInt(null, account);
        Object cfg = config(0);
        cfg.getClass().getMethod("saveConfig", boolean.class).invoke(cfg, false);
    }

    /** The running LaunchActivity, or null when Telegram's UI is not alive. */
    static Object launchActivityInstance() {
        try {
            Class<?> c = Class.forName(LAUNCH_ACTIVITY);
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Switches the running Telegram UI to {@code account}.
     *
     * @return true if it was switched, false if the method could not be found.
     */
    static boolean switchRunningTelegram(Object launchActivity, int account) {
        try {
            Method m = launchActivity.getClass().getMethod("switchToAccount", int.class, boolean.class);
            m.invoke(launchActivity, account, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
