package com.open4id.example.android;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.open4id.sdk.Open4IDAuthClient;

/**
 * Smallest possible published-SDK integration: the headless
 * {@link Open4IDAuthClient} driven directly (bootstrap, register,
 * sign-in, guest, refresh, sign out). No framework, no view code.
 * The package name plus redirect URI below must be registered for the
 * dev application first (Portal → Applications → Authentication:
 * enable the android platform with this exact package, and register
 * the exact redirect URI) — otherwise bootstrap answers
 * {@code client.bootstrap.invalid}.
 */
public class MainActivity extends Activity {
    private final Open4IDAuthClient client = new Open4IDAuthClient();
    private EditText appId;
    private EditText environment;
    private EditText publicKey;
    private EditText endpoint;
    private EditText redirectUri;
    private EditText email;
    private EditText password;
    private TextView log;
    // Listens to auth state changes from any source (sign-in, sign-out,
    // refresh, deletion).
    private Runnable authStateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        appId = field(root, "app_4a17c6406abaefdf0d9a705e7175b37c");
        environment = field(root, "development");
        publicKey = field(root, "sdk_pk_24cf6cc954d16591812fd1b9953ef053f5133b13f7cec9ae");
        endpoint = field(root, "https://id.open4id.com/api");
        redirectUri = field(root, "com.open4id.example.android://open4id/callback");
        email = field(root, "player@example.com");
        password = field(root, "");
        password.setHint("password (8+ characters)");

        button(root, "1. Bootstrap", this::onBootstrap);
        button(root, "2a. Register", this::onRegister);
        button(root, "2b. Sign in", this::onSignIn);
        button(root, "2c. Continue as guest", this::onGuest);
        button(root, "3. Refresh session", this::onRefresh);
        button(root, "4. Sign out", this::onSignOut);

        log = new TextView(this);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        // Target SDK 36 enforces edge-to-edge: without this the top
        // fields render under the status bar. Confine content to the
        // system windows instead.
        outer.setFitsSystemWindows(true);
        setContentView(outer);
        applyLightStatusBar();
        // Listen to auth state changes from any source so the main app
        // can react (e.g. show game lobby when signed in).
        authStateListener = client.onAuthStateChanged((session, user) -> {
            if (session != null) {
                appendLog("auth state: signed in sub=" + session.userId);
            } else {
                appendLog("auth state: signed out");
            }
        });
    }

    /**
     * Our backgrounds are light, so the status bar icons must be dark —
     * otherwise the clock/icons vanish into the background.
     * Must run after setContentView (no decor view exists before it).
     */
    private void applyLightStatusBar() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void configureClient() {
        client.configure(text(appId), text(environment), text(publicKey),
                text(endpoint), text(redirectUri), null,
                getPackageName());
    }

    private void onBootstrap() {
        try {
            configureClient();
        } catch (RuntimeException e) {
            appendLog("configure: " + e.getMessage());
            return;
        }
        appendLog("bootstrap…");
        client.bootstrap().whenComplete((bootstrap, error) -> {
            if (error != null) {
                appendLog("bootstrap failed: " + rootMessage(error));
                return;
            }
            String name = bootstrap.displayName != null ? bootstrap.displayName : "(no display_name)";
            appendLog("bootstrap ok: app=" + name);
        });
    }

    private void onRegister() {
        try {
            configureClient();
        } catch (RuntimeException e) {
            appendLog("configure: " + e.getMessage());
            return;
        }
        appendLog("register…");
        client.register(text(email), text(password)).whenComplete((ignored, error) -> {
            if (error != null) {
                appendLog("register failed: " + rootMessage(error));
                return;
            }
            appendLog("registered. Check the inbox for the 24h verification email, then sign in.");
        });
    }

    private void onSignIn() {
        try {
            configureClient();
        } catch (RuntimeException e) {
            appendLog("configure: " + e.getMessage());
            return;
        }
        appendLog("sign in…");
        client.signInWithPassword(text(email), text(password)).whenComplete((session, error) -> {
            if (error != null) {
                appendLog("sign in failed: " + rootMessage(error));
                return;
            }
            appendLog("signed in: sub=" + session.userId);
        });
    }

    private void onGuest() {
        try {
            configureClient();
        } catch (RuntimeException e) {
            appendLog("configure: " + e.getMessage());
            return;
        }
        appendLog("guest sign-in…");
        client.continueAsGuest().whenComplete((session, error) -> {
            if (error != null) {
                appendLog("guest failed: " + rootMessage(error));
                return;
            }
            appendLog("guest ok: sub=" + session.userId);
        });
    }

    private void onRefresh() {
        appendLog("refresh…");
        client.refresh().whenComplete((session, error) -> {
            if (error != null) {
                appendLog("refresh failed: " + rootMessage(error));
                return;
            }
            appendLog("refresh ok: sub=" + session.userId);
        });
    }

    private void onSignOut() {
        appendLog("sign out…");
        client.signOut().whenComplete((ignored, error) -> {
            if (error != null) {
                appendLog("sign out failed: " + rootMessage(error));
                return;
            }
            appendLog("signed out.");
        });
    }

    private EditText field(LinearLayout root, String initial) {
        EditText edit = new EditText(this);
        edit.setText(initial);
        edit.setSingleLine(true);
        edit.setTextSize(13);
        root.addView(edit,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private void button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        root.addView(button,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void appendLog(String line) {
        runOnUiThread(() -> log.append(line + "\n"));
    }

    private static String text(EditText edit) {
        return edit.getText().toString().trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return String.valueOf(cause.getMessage());
    }

    @Override
    protected void onDestroy() {
        if (authStateListener != null) {
            authStateListener.run();
        }
        super.onDestroy();
    }
}
