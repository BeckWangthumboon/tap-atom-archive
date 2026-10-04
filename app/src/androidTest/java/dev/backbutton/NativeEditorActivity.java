package dev.backbutton;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

/** Separate test APK editor. No field is saved or submitted; no target APK dependencies. */
public class NativeEditorActivity extends Activity {
    private final Map<String, EditText> fields = new HashMap<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(60), dp(24), dp(24));
        TextView heading = new TextView(this);
        heading.setText("Native dictation test — nothing is submitted");
        heading.setTextSize(18);
        root.addView(heading);
        String[] names = {"message", "other", "password", "pin"};
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            TextView label = new TextView(this);
            label.setText(name);
            root.addView(label);
            EditText field = new EditText(this);
            field.setId(index + 1);
            field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
            field.setInputType(name.equals("password")
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : name.equals("pin")
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT);
            field.setText(name.equals("message") ? "Meet me at noon." : name.equals("other") ? "Other field" : "");
            fields.put(name, field);
            root.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        }
        setContentView(root);
        focus(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("reset", false)) {
            fields.get("message").setText("Meet me at noon.");
            fields.get("other").setText("Other field");
            fields.get("password").setText("");
            fields.get("pin").setText("");
        }
        focus(intent);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) focus(getIntent());
    }

    private void focus(Intent intent) {
        String name = intent.getStringExtra("field");
        EditText field = fields.get(name == null ? "message" : name);
        if (field == null) return;
        field.requestFocus();
        field.post(() -> getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
}
