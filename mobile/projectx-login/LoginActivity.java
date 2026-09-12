package com.jagex.projectx;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.jagex.android.MainActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class LoginActivity extends Activity {
    private static final String PREFS = "projectx";
    private static final String K_USER = "u";
    private static final String K_PASS = "p";
    private static final String K_REMEMBER = "r";
    private static final String CREDS = "projectx-login.txt";
    private static final String STATUS = "projectx-login-status.txt";
    private static final String SCRIPT = "loginscript.js";
    private static final String HOST = "__PROJECTX_HOST__";

    private EditText userField;
    private EditText passField;
    private CheckBox rememberBox;
    private boolean passVisible;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String user = sp.getString(K_USER, "");
        String pass = sp.getString(K_PASS, "");
        boolean remember = sp.getBoolean(K_REMEMBER, true);
        String last = readSmall(STATUS);
        boolean lastFail = last != null && last.startsWith("fail");
        clearFile(STATUS);
        if (remember && user.length() > 0 && pass.length() > 0 && !lastFail) {
            setContentView(progressView());
            doLogin(user, pass, true);
        } else {
            String banner = lastFail ? "Last login failed — check your username and password." : null;
            setContentView(formView(user, pass, remember, banner));
        }
    }

    private int px(float dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }

    private GradientDrawable rounded(String color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    private TextView title() {
        TextView t = new TextView(this);
        t.setText("⚔ Project X");
        t.setTextColor(Color.parseColor("#E8B84B"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, px(20));
        return t;
    }

    private EditText field(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.parseColor("#777788"));
        e.setTextColor(Color.parseColor("#EAEAEA"));
        e.setSingleLine(true);
        e.setBackground(rounded("#26262E", 10));
        e.setPadding(px(14), px(12), px(14), px(12));
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private LinearLayout.LayoutParams stack(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topDp);
        return lp;
    }

    private View card(LinearLayout inner) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E0E12"));
        inner.setBackground(rounded("#1A1A22", 16));
        inner.setPadding(px(24), px(28), px(24), px(24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(inner, lp);
        return root;
    }

    private View formView(String user, String pass, boolean remember, String banner) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(title());

        if (banner != null) {
            TextView err = new TextView(this);
            err.setText(banner);
            err.setTextColor(Color.parseColor("#F2A0A0"));
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            err.setBackground(rounded("#3A1E1E", 8));
            err.setPadding(px(12), px(10), px(12), px(10));
            c.addView(err, stack(0));
        }

        userField = field("Username", false);
        userField.setText(user);
        userField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        c.addView(userField, stack(banner != null ? 14 : 4));

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passField = field("Password", true);
        passField.setText(pass);
        passField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passRow.addView(passField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Button toggle = new Button(this);
        toggle.setText("Show");
        toggle.setAllCaps(false);
        toggle.setTextColor(Color.parseColor("#B8B8C0"));
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggle.setBackgroundColor(Color.TRANSPARENT);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                passVisible = !passVisible;
                int sel = passField.getSelectionEnd();
                passField.setInputType(InputType.TYPE_CLASS_TEXT | (passVisible
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                passField.setSelection(Math.min(sel, passField.length()));
                toggle.setText(passVisible ? "Hide" : "Show");
            }
        });
        passRow.addView(toggle, new LinearLayout.LayoutParams(px(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(passRow, stack(10));

        passField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView tv, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            }
        });

        rememberBox = new CheckBox(this);
        rememberBox.setText("Remember me");
        rememberBox.setTextColor(Color.parseColor("#C8C8D0"));
        rememberBox.setChecked(remember);
        c.addView(rememberBox, stack(6));

        Button login = new Button(this);
        login.setText("Log in");
        login.setAllCaps(false);
        login.setTextColor(Color.parseColor("#101018"));
        login.setTypeface(Typeface.DEFAULT_BOLD);
        login.setBackground(rounded("#E8B84B", 10));
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        c.addView(login, stack(16));

        TextView switchAcc = new TextView(this);
        switchAcc.setText("Switch account");
        switchAcc.setTextColor(Color.parseColor("#7A7A88"));
        switchAcc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        switchAcc.setGravity(Gravity.CENTER);
        switchAcc.setPadding(0, px(14), 0, 0);
        switchAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit();
                userField.setText("");
                passField.setText("");
                userField.requestFocus();
            }
        });
        c.addView(switchAcc, stack(4));

        return card(c);
    }

    private View progressView() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackgroundColor(Color.parseColor("#0E0E12"));
        c.addView(title());
        c.addView(new ProgressBar(this), new LinearLayout.LayoutParams(px(48), px(48)));
        TextView t = new TextView(this);
        t.setText("Logging in…");
        t.setTextColor(Color.parseColor("#C8C8D0"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(16), 0, 0);
        c.addView(t);
        return c;
    }

    private void submit() {
        String user = userField.getText().toString().trim();
        String pass = passField.getText().toString();
        if (user.length() == 0 || pass.length() == 0) {
            return;
        }
        boolean remember = rememberBox.isChecked();
        setContentView(progressView());
        doLogin(user, pass, remember);
    }

    private void doLogin(String user, String pass, boolean remember) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (remember) {
            e.putString(K_USER, user).putString(K_PASS, pass).putBoolean(K_REMEMBER, true);
        } else {
            e.clear();
        }
        e.commit();
        clearFile(STATUS);
        writeFile(CREDS, user + "\n" + pass);
        copyAsset(SCRIPT);
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(Uri.parse("https://secure.runescape.com/playnow/rs?launchurl=" + HOST));
        startActivity(i);
        // keep the "Logging in..." screen visible over the boot gap, then step aside for the game.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 2500);
    }

    private void writeFile(String name, String content) {
        try {
            FileOutputStream o = new FileOutputStream(new File(getFilesDir(), name));
            o.write(content.getBytes("UTF-8"));
            o.close();
        } catch (Throwable t) {
        }
    }

    private void copyAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            FileOutputStream o = new FileOutputStream(new File(getFilesDir(), name));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
            o.close();
            in.close();
        } catch (Throwable t) {
        }
    }

    private String readSmall(String name) {
        try {
            File f = new File(getFilesDir(), name);
            if (!f.exists()) {
                return null;
            }
            byte[] data = new byte[(int) Math.min(f.length(), 256)];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(data);
            in.close();
            return n <= 0 ? null : new String(data, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private void clearFile(String name) {
        try {
            new File(getFilesDir(), name).delete();
        } catch (Throwable t) {
        }
    }
}
