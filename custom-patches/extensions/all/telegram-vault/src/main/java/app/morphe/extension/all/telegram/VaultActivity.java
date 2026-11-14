package app.morphe.extension.all.telegram;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/**
 * "Vault": a second launcher icon that is protected by its own PIN.
 * From here you hide or unhide Telegram accounts and open a hidden one.
 * Hidden accounts disappear from Telegram's side menu (see HiddenAccounts).
 * The screen locks as soon as you leave it.
 */
public class VaultActivity extends Activity {
    private static final int BG = Color.rgb(18, 18, 18);
    private static final int CARD = Color.rgb(34, 34, 34);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(170, 170, 170);

    private LinearLayout root;
    private boolean unlocked = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No screenshots, and no preview of the screen in the recent apps list.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unlocked = false;   // lock instantly when the user leaves
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // ---------------------------------------------------------------- screens

    private void render() {
        if (root == null) return;
        root.removeAllViews();
        title("Vault");
        if (!TelegramBridge.isTelegram()) {
            body("This screen only works inside the patched Telegram app.");
        } else if (!VaultStore.isPinSet(this)) {
            showSetPin();
        } else if (!unlocked) {
            showEnterPin();
        } else {
            showAccounts();
        }
    }

    private void showSetPin() {
        body("Choose a PIN (4 to 12 digits). It protects your hidden accounts. If you forget it, clear the app's data (this also logs out every account).");
        final EditText pin = pinField("New PIN");
        final EditText again = pinField("Repeat PIN");
        root.addView(pin);
        root.addView(again);
        root.addView(button("Save PIN", v -> {
            String a = pin.getText().toString();
            String b = again.getText().toString();
            if (!validPin(a)) {
                toast("The PIN must be 4 to 12 digits");
            } else if (!a.equals(b)) {
                toast("The two PINs are different");
            } else {
                try {
                    VaultStore.setPin(this, a);
                    unlocked = true;
                    render();
                } catch (Exception e) {
                    toast("Could not save the PIN");
                }
            }
        }));
    }

    private void showEnterPin() {
        body("Enter your PIN");
        final EditText pin = pinField("PIN");
        root.addView(pin);
        root.addView(button("Unlock", v -> {
            long blocked = VaultStore.blockedForMillis(this);
            if (blocked > 0) {
                toast("Too many tries. Wait " + (blocked / 1000 + 1) + " seconds");
            } else if (VaultStore.checkPin(this, pin.getText().toString())) {
                unlocked = true;
                render();
            } else {
                pin.setText("");
                toast("Wrong PIN");
            }
        }));
    }

    private void showAccounts() {
        body("Hidden accounts are removed from Telegram's side menu. Open one from here. "
                + "Notifications from a hidden account still appear, so mute it in Telegram if you need silence.");
        Set<Integer> hidden = VaultStore.hidden(this);
        int shown = 0;
        for (int a = 0; a < TelegramBridge.maxAccounts(); a++) {
            if (!TelegramBridge.isActivated(a)) continue;
            shown++;
            addAccountRow(a, hidden.contains(a));
        }
        if (shown == 0) {
            body("No logged-in accounts found.");
        }
        root.addView(button("Lock now", v -> {
            unlocked = false;
            render();
        }));
    }

    private void addAccountRow(final int account, final boolean isHidden) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));

        TextView name = new TextView(this);
        name.setText(TelegramBridge.label(account));
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        card.addView(name);

        TextView state = new TextView(this);
        state.setText(isHidden ? "Hidden" : "Visible in the side menu");
        state.setTextColor(MUTED);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(state);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button(isHidden ? "Unhide" : "Hide", v -> toggleHidden(account, isHidden)));
        buttons.addView(button("Open", v -> openAccount(account)));
        card.addView(buttons);
        root.addView(card, lp);
    }

    // ---------------------------------------------------------------- actions

    private void toggleHidden(int account, boolean currentlyHidden) {
        if (!currentlyHidden) {
            if (account == TelegramBridge.selectedAccount()) {
                toast("Switch Telegram to another account first, then hide this one");
                return;
            }
            int visible = 0;
            Set<Integer> hidden = VaultStore.hidden(this);
            for (int a = 0; a < TelegramBridge.maxAccounts(); a++) {
                if (TelegramBridge.isActivated(a) && !hidden.contains(a)) visible++;
            }
            if (visible <= 1) {
                toast("Keep at least one visible account");
                return;
            }
        }
        VaultStore.setHidden(this, account, !currentlyHidden);
        render();
    }

    private void openAccount(final int account) {
        try {
            Object running = TelegramBridge.launchActivityInstance();
            if (running != null && account != TelegramBridge.selectedAccount()) {
                if (!TelegramBridge.switchRunningTelegram(running, account)) {
                    toast("Close Telegram completely, then tap Open again");
                    return;
                }
            } else if (running == null) {
                TelegramBridge.rememberSelected(account);
            }
            Intent launch = new Intent();
            launch.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launch);
            unlocked = false;
        } catch (Throwable t) {
            toast("Could not open that account");
        }
    }

    // ---------------------------------------------------------------- small UI helpers

    private static boolean validPin(String s) {
        return s.length() >= 4 && s.length() <= 12 && s.matches("[0-9]+");
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setGravity(Gravity.START);
        root.addView(t);
    }

    private void body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(12), 0, dp(12));
        root.addView(t);
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return e;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }
}
