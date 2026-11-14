package app.morphe.extension.all.telegram;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/**
 * Injection points called from Telegram's code (see the Telegram vault patch).
 */
@SuppressWarnings("unused")
public final class HiddenAccounts {
    private static final String TAG = "MorpheTelegramVault";
    private static Field accountNumbersField;

    private HiddenAccounts() {
    }

    /**
     * Injection point: called at the end of DrawerLayoutAdapter.resetItems().
     * Removes hidden accounts from the account list of the side menu. Never throws.
     */
    public static void filterDrawer(Object drawerLayoutAdapter) {
        try {
            Context context = TelegramBridge.appContext();
            if (context == null) return;
            Set<Integer> hidden = VaultStore.hidden(context);
            if (hidden.isEmpty()) return;

            Field field = accountNumbersField;
            if (field == null) {
                field = findAccountNumbersField(drawerLayoutAdapter.getClass());
                accountNumbersField = field;
            }
            if (field == null) {
                Log.w(TAG, "accountNumbers field not found; hidden accounts stay visible");
                return;
            }
            @SuppressWarnings("unchecked")
            List<Integer> list = (List<Integer>) field.get(drawerLayoutAdapter);
            if (list != null) {
                list.removeAll(hidden);
            }
        } catch (Throwable t) {
            Log.w(TAG, "filterDrawer failed", t);
        }
    }

    private static Field findAccountNumbersField(Class<?> adapterClass) {
        Field guess = null;
        int integerLists = 0;
        for (Field f : adapterClass.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            if (f.getName().equals("accountNumbers")) return f;
            Type generic = f.getGenericType();
            if (generic instanceof ParameterizedType) {
                Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
                if (args.length == 1 && args[0] == Integer.class) {
                    integerLists++;
                    guess = f;
                }
            }
        }
        // Only trust a guess when it is the one and only List<Integer> field.
        return integerLists == 1 ? guess : null;
    }
}
