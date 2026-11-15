package app.morphe.extension.all.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores the vault PIN (salted PBKDF2 hash, never the PIN itself) and the list of hidden accounts.
 * Note: this is privacy against casual snooping, not protection against someone who can read the phone's storage.
 */
final class VaultStore {
    private static final String PREFS = "morphe_tg_vault";
    private static final String KEY_SALT = "salt";
    private static final String KEY_HASH = "hash";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_FAILS = "fails";
    private static final String KEY_LOCKED_UNTIL = "locked_until";
    private static final String KEY_KNOWN = "known";
    private static final String KEY_LOCK_ON = "lock_on";
    private static final String KEY_LOCK_AFTER = "lock_after_s";
    private static final int ITERATIONS = 60_000;
    private static final int MAX_FREE_ATTEMPTS = 5;
    private static final long LOCKOUT_MILLIS = 30_000L;

    private VaultStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isPinSet(Context context) {
        return prefs(context).getString(KEY_HASH, null) != null;
    }

    static void setPin(Context context, String pin) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hash(pin, salt);
        prefs(context).edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_LOCKED_UNTIL, 0L)
                .commit();
    }

    /** Milliseconds the PIN entry stays blocked after too many wrong attempts (0 = not blocked). */
    static long blockedForMillis(Context context) {
        long left = prefs(context).getLong(KEY_LOCKED_UNTIL, 0L) - System.currentTimeMillis();
        return Math.max(0L, left);
    }

    static boolean checkPin(Context context, String pin) {
        SharedPreferences p = prefs(context);
        if (blockedForMillis(context) > 0) {
            return false;
        }
        try {
            byte[] salt = Base64.decode(p.getString(KEY_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(p.getString(KEY_HASH, ""), Base64.NO_WRAP);
            if (MessageDigest.isEqual(expected, hash(pin, salt))) {
                p.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).commit();
                return true;
            }
        } catch (Exception ignored) {
            // treat any problem as a wrong PIN
        }
        int fails = p.getInt(KEY_FAILS, 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt(KEY_FAILS, fails);
        if (fails >= MAX_FREE_ATTEMPTS) {
            e.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MILLIS).putInt(KEY_FAILS, 0);
        }
        e.commit();
        return false;
    }

    private static byte[] hash(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    static Set<Integer> hidden(Context context) {
        Set<Integer> out = new HashSet<>();
        String raw = prefs(context).getString(KEY_HIDDEN, "");
        for (String part : raw.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) {
                try {
                    out.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                    // skip garbage
                }
            }
        }
        return out;
    }

    static void setHidden(Context context, int account, boolean hide) {
        Set<Integer> set = hidden(context);
        if (hide) {
            set.add(account);
        } else {
            set.remove(account);
        }
        StringBuilder sb = new StringBuilder();
        for (int a : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a);
        }
        prefs(context).edit().putString(KEY_HIDDEN, sb.toString()).commit();
    }

    // ------------------------------------------------------------------ accounts the Vault has seen

    /** Account numbers that the side menu listed the last time it was looked at (in menu order). */
    static java.util.List<Integer> known(Context context) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String part : prefs(context).getString(KEY_KNOWN, "").split(",")) {
            part = part.trim();
            if (!part.isEmpty()) {
                try {
                    out.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                    // skip garbage
                }
            }
        }
        return out;
    }

    static void setKnown(Context context, java.util.List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int a : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a);
        }
        String value = sb.toString();
        SharedPreferences p = prefs(context);
        if (!value.equals(p.getString(KEY_KNOWN, ""))) {
            p.edit().putString(KEY_KNOWN, value).commit();
        }
    }

    // ------------------------------------------------------------------ app lock settings

    static boolean isLockEnabled(Context context) {
        return prefs(context).getInt(KEY_LOCK_ON, 0) == 1 && isPinSet(context);
    }

    static void setLockEnabled(Context context, boolean on) {
        prefs(context).edit().putInt(KEY_LOCK_ON, on ? 1 : 0).commit();
    }

    /** Seconds Telegram may stay in the background before it asks for the PIN again (0 = ask every time). */
    static int lockAfterSeconds(Context context) {
        return prefs(context).getInt(KEY_LOCK_AFTER, 60);
    }

    static void setLockAfterSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_LOCK_AFTER, seconds).commit();
    }
}
