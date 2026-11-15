package app.morphe.extension.all.telegram;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen PIN prompt shown on top of Telegram when the app lock is on.
 * Back goes to the home screen, never into Telegram.
 */
public class UnlockActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        root.setGravity(Gravity.CENTER);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Telegram is locked");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        final EditText pin = new EditText(this);
        pin.setHint("PIN");
        pin.setHintTextColor(Color.rgb(170, 170, 170));
        pin.setTextColor(Color.WHITE);
        pin.setGravity(Gravity.CENTER);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(pin);

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setOnClickListener(v -> {
            long blocked = VaultStore.blockedForMillis(this);
            if (blocked > 0) {
                Toast.makeText(this, "Too many tries. Wait " + (blocked / 1000 + 1) + " seconds", Toast.LENGTH_SHORT).show();
            } else if (VaultStore.checkPin(this, pin.getText().toString())) {
                HiddenAccounts.markUnlocked();
                finish();
            } else {
                pin.setText("");
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(unlock);
        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // home screen, not Telegram
    }
}
