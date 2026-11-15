package app.morphe.extension.all.telegram;

import android.app.Activity;
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
 * From here you hide or unhide Telegram accounts (they disappear from the side menu, see HiddenAccounts)
 * and set up the Telegram lock. The screen locks as soon as you leave it.
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
        if (!VaultStore.isPinSet(this)) {
            showSetPin();
        } else if (!unlocked) {
            showEnterPin();
        } else {
            showMain();
        }
    }

    private void showSetPin() {
        body("Choose a PIN (4 to 12 digits). It protects your hidden accounts and the Telegram lock. If you forget it, clear the app's data (this also logs out every account).");
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

    private void showMain() {
        section("Accounts");
        java.util.List<Integer> known = VaultStore.known(this);
        Set<Integer> hidden = VaultStore.hidden(this);
        if (known.isEmpty()) {
            body("No accounts seen yet. Open Telegram once and wait a moment, then come back here.");
        } else {
            body("Hide an account, then open Telegram's side menu to see which one disappeared. Don't hide the account you are using right now. Notifications of a hidden account still appear.");
            for (int id : known) {
                addAccountRow(id, hidden.contains(id));
            }
            if (!hidden.isEmpty()) {
                if (HiddenAccounts.isRevealActive()) {
                    root.addView(button("Hide them again now", v -> {
                        HiddenAccounts.hideAgain();
                        render();
                    }));
                } else {
                    root.addView(button("Show hidden accounts for 5 minutes", v -> {
                        HiddenAccounts.revealFor(5);
                        toast("Open Telegram: the accounts are back for 5 minutes");
                        render();
                    }));
                }
            }
        }

        section("Lock Telegram");
        final boolean lockOn = VaultStore.isLockEnabled(this);
        body("Asks for this PIN when Telegram opens, and after it has been in the background for the time below.");
        root.addView(button(lockOn ? "Lock is ON (tap to turn off)" : "Lock is OFF (tap to turn on)", v -> {
            VaultStore.setLockEnabled(this, !lockOn);
            render();
        }));
        root.addView(button("Lock after: " + describe(VaultStore.lockAfterSeconds(this)) + " (tap to change)", v -> {
            VaultStore.setLockAfterSeconds(this, nextDelay(VaultStore.lockAfterSeconds(this)));
            render();
        }));

        root.addView(button("Lock the Vault now", v -> {
            unlocked = false;
            render();
        }));
    }

    private static final int[] DELAYS = {0, 30, 60, 300, 900};

    private static int nextDelay(int current) {
        for (int i = 0; i < DELAYS.length; i++) {
            if (DELAYS[i] == current) return DELAYS[(i + 1) % DELAYS.length];
        }
        return DELAYS[0];
    }

    private static String describe(int seconds) {
        if (seconds <= 0) return "every time";
        if (seconds < 60) return seconds + " seconds";
        return (seconds / 60) + (seconds == 60 ? " minute" : " minutes");
    }

    private void addAccountRow(final int account, final boolean isHidden) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));

        TextView name = new TextView(this);
        name.setText("Account " + (account + 1));
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        card.addView(name);

        TextView state = new TextView(this);
        state.setText(isHidden ? "Hidden" : "Visible in the side menu");
        state.setTextColor(MUTED);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(state);

        card.addView(button(isHidden ? "Unhide" : "Hide", v -> toggleHidden(account, isHidden)));
        root.addView(card, lp);
    }

    // ---------------------------------------------------------------- actions

    private void toggleHidden(int account, boolean currentlyHidden) {
        if (!currentlyHidden) {
            int visible = 0;
            Set<Integer> hidden = VaultStore.hidden(this);
            for (int a : VaultStore.known(this)) {
                if (!hidden.contains(a)) visible++;
            }
            if (visible <= 1) {
                toast("Keep at least one visible account");
                return;
            }
        }
        VaultStore.setHidden(this, account, !currentlyHidden);
        render();
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

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setPadding(0, dp(20), 0, 0);
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
