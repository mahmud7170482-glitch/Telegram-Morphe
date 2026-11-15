package app.morphe.extension.all.telegram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Called from Telegram's LaunchActivity.onResume / onPause (see the Telegram vault patch).
 *
 * Telegram 13 scrambles almost every class and method name, so nothing here depends on one. The only names used are
 * the ones the Android system and Telegram's copy of RecyclerView keep: {@code androidx.recyclerview.widget.
 * RecyclerView}, {@code getAdapter()} and {@code setAdapter(..)}. Everything else is found by looking at the objects
 * themselves (for example: the side menu is the narrow RecyclerView whose adapter holds a list of account numbers).
 *
 * If anything is not found the feature switches itself off quietly; it never throws into Telegram.
 */
@SuppressWarnings("unused")
public final class HiddenAccounts {
    private static final String TAG = "MorpheTelegramVault";
    private static final String RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView";
    private static final long CHECK_INTERVAL_MS = 150;

    // ---- side-menu watcher state (main thread only)
    private static WeakReference<ViewTreeObserver> observerRef = new WeakReference<>(null);
    private static Found cached;
    private static long lastCheck;
    private static boolean fixPending;
    private static volatile long revealUntil;
    /** Account numbers this process removed from the menu, with the position they had. */
    private static final LinkedHashMap<Integer, Integer> removed = new LinkedHashMap<>();

    // ---- app lock state (main thread only)
    private static boolean lockedNow = true;       // locked at process start (when the lock is turned on)
    private static long pausedAt;
    private static long unlockedAt;

    private HiddenAccounts() {
    }

    // =====================================================================================================
    // Injection points
    // =====================================================================================================

    /** Injection point: end of LaunchActivity.onResume(). */
    public static void onResume(Activity activity) {
        try {
            applyScreenSecurity(activity);
            attachWatcher(activity);
            lockIfNeeded(activity);
        } catch (Throwable t) {
            Log.w(TAG, "onResume failed", t);
        }
    }

    /** Injection point: end of LaunchActivity.onPause(). */
    public static void onPause(Activity activity) {
        try {
            pausedAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            Log.w(TAG, "onPause failed", t);
        }
    }

    // =====================================================================================================
    // Hidden accounts: watch the side menu
    // =====================================================================================================

    private static void attachWatcher(final Activity activity) throws Exception {
        final View decor = activity.getWindow().getDecorView();
        ViewTreeObserver vto = decor.getViewTreeObserver();
        if (observerRef.get() != vto) {
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastCheck >= CHECK_INTERVAL_MS) {
                        lastCheck = now;
                        try {
                            checkDrawer(decor);
                        } catch (Throwable t) {
                            Log.w(TAG, "checkDrawer failed", t);
                        }
                    }
                    return true;
                }
            });
            observerRef = new WeakReference<>(vto);
        }
        checkDrawer(decor);
    }

    /** What was found: the side menu's RecyclerView, its adapter, and the adapter's list of account numbers. */
    private static final class Found {
        final View recyclerView;
        final Object adapter;
        final Field listField;
        final Method setAdapter;

        Found(View recyclerView, Object adapter, Field listField, Method setAdapter) {
            this.recyclerView = recyclerView;
            this.adapter = adapter;
            this.listField = listField;
            this.setAdapter = setAdapter;
        }

        @SuppressWarnings("unchecked")
        List<Integer> list() throws IllegalAccessException {
            return (List<Integer>) listField.get(adapter);
        }
    }

    private static void checkDrawer(View decor) throws Exception {
        final Context context = decor.getContext();
        Found found = locate(decor);
        if (found == null) return;
        final List<Integer> list = found.list();
        if (list == null) return;

        Set<Integer> hidden = VaultStore.hidden(context);

        // Remember which accounts exist, in a stable order. Hidden ones are no longer in the list but still exist.
        List<Integer> known = new ArrayList<>();
        for (int id : VaultStore.known(context)) {
            if (list.contains(id) || hidden.contains(id)) known.add(id);
        }
        for (int id : list) {
            if (!known.contains(id)) known.add(id);
        }
        VaultStore.setKnown(context, known);

        if (System.currentTimeMillis() < revealUntil) {
            restoreRemoved(found, list);
            return;
        }

        boolean needsFix = false;
        for (int id : list) {
            if (hidden.contains(id)) {
                needsFix = true;
                break;
            }
        }
        if (!needsFix || fixPending) return;

        fixPending = true;
        final Found f = found;
        final Set<Integer> toHide = hidden;
        f.recyclerView.post(new Runnable() {
            @Override
            public void run() {
                fixPending = false;
                try {
                    List<Integer> current = f.list();
                    if (current == null) return;
                    for (int i = current.size() - 1; i >= 0; i--) {
                        int id = current.get(i);
                        if (toHide.contains(id)) {
                            removed.put(id, i);
                            current.remove(i);
                        }
                    }
                    f.setAdapter.invoke(f.recyclerView, f.adapter);   // makes the RecyclerView re-read the list
                } catch (Throwable t) {
                    Log.w(TAG, "could not hide accounts", t);
                }
            }
        });
    }

    /** While the reveal window is open: put back the accounts this process removed, then refresh the menu. */
    private static void restoreRemoved(Found found, List<Integer> list) {
        if (removed.isEmpty()) return;
        try {
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(removed.entrySet());
            java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<Integer, Integer>>() {
                @Override
                public int compare(Map.Entry<Integer, Integer> a, Map.Entry<Integer, Integer> b) {
                    return a.getValue() - b.getValue();
                }
            });
            for (Map.Entry<Integer, Integer> e : entries) {
                if (!list.contains(e.getKey())) {
                    list.add(Math.min(e.getValue(), list.size()), e.getKey());
                }
            }
            removed.clear();
            found.setAdapter.invoke(found.recyclerView, found.adapter);
        } catch (Throwable t) {
            Log.w(TAG, "could not show hidden accounts", t);
        }
    }

    // ------------------------------------------------------------------ finding the side menu

    private static Found locate(View decor) {
        Found c = cached;
        if (c != null && c.recyclerView.isAttachedToWindow()) {
            try {
                Object adapter = getAdapter(c.recyclerView);
                if (adapter == c.adapter && c.list() != null) return c;
            } catch (Throwable ignored) {
                // fall through to a fresh search
            }
        }
        cached = null;
        if (decor instanceof ViewGroup) {
            cached = search((ViewGroup) decor, 0);
        }
        return cached;
    }

    private static Found search(ViewGroup group, int depth) {
        if (depth > 14) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isRecyclerView(child)) {
                Found f = examine(child);
                if (f != null) return f;
                continue;   // do not look inside lists: their children are rows
            }
            if (child instanceof ViewGroup) {
                Found f = search((ViewGroup) child, depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static boolean isRecyclerView(View v) {
        try {
            return Class.forName(RECYCLER_VIEW).isInstance(v);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getAdapter(View recyclerView) throws Exception {
        return Class.forName(RECYCLER_VIEW).getMethod("getAdapter").invoke(recyclerView);
    }

    private static Method findSetAdapter() throws Exception {
        for (Method m : Class.forName(RECYCLER_VIEW).getMethods()) {
            if (m.getName().equals("setAdapter") && m.getParameterTypes().length == 1) return m;
        }
        throw new NoSuchMethodException("setAdapter");
    }

    /** The side menu is a RecyclerView narrower than the screen whose adapter keeps a list of account numbers. */
    private static Found examine(View rv) {
        try {
            int screen = rv.getResources().getDisplayMetrics().widthPixels;
            int width = rv.getWidth();
            if (width <= 0 || width >= screen) return null;
            Object adapter = getAdapter(rv);
            if (adapter == null) return null;
            for (Class<?> c = adapter.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!ArrayList.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object value = f.get(adapter);
                    if (value instanceof ArrayList && looksLikeAccountList((ArrayList<?>) value)) {
                        return new Found(rv, adapter, f, findSetAdapter());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "examine failed", t);
        }
        return null;
    }

    /** 1 to 8 distinct small whole numbers: account numbers. */
    static boolean looksLikeAccountList(ArrayList<?> list) {
        if (list.isEmpty() || list.size() > 8) return false;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Integer)) return false;
            int v = (Integer) o;
            if (v < 0 || v > 9 || !seen.add(v)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ used by the Vault screen

    static void revealFor(int minutes) {
        revealUntil = System.currentTimeMillis() + minutes * 60_000L;
    }

    static void hideAgain() {
        revealUntil = 0L;
    }

    static boolean isRevealActive() {
        return System.currentTimeMillis() < revealUntil;
    }

    // =====================================================================================================
    // App lock
    // =====================================================================================================

    private static void applyScreenSecurity(Activity activity) {
        if (VaultStore.isLockEnabled(activity)) {
            // no screenshots and no preview of Telegram in the recent apps list
            activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private static void lockIfNeeded(Activity activity) {
        if (!VaultStore.isLockEnabled(activity)) return;
        long now = SystemClock.elapsedRealtime();
        boolean justUnlocked = unlockedAt != 0 && now - unlockedAt < 1500;
        if (!lockedNow && !justUnlocked && pausedAt != 0) {
            long awayMs = now - pausedAt;
            if (awayMs >= VaultStore.lockAfterSeconds(activity) * 1000L) {
                lockedNow = true;
            }
        }
        if (lockedNow && !justUnlocked) {
            Intent intent = new Intent(activity, UnlockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
        }
    }

    /** Called by UnlockActivity after the right PIN. */
    static void markUnlocked() {
        lockedNow = false;
        unlockedAt = SystemClock.elapsedRealtime();
    }
}
