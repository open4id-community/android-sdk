package com.open4id.example.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.open4id.sdk.AuthTypes;
import com.open4id.sdk.ui.Open4IDAuthView;
import com.open4id.sdk.ui.Open4IDLoginDialog;

/**
 * Same backend as {@code android-sdk-minimal}, but the login UI is the
 * SDK's {@link Open4IDAuthView} instead of headless calls: sign-in,
 * sign-up, forgot-password, guest, link-email, and delete-account come
 * with the view. This activity only creates the headless client,
 * bootstraps it, binds both to the view, and reacts to the auth
 * listener. Social buttons render per bootstrap readiness but need
 * their bridge URLs registered (see README) — email and guest work
 * with the dev registration below.
 * The package name plus redirect URI below must be registered for the
 * dev application first (Portal → Applications → Authentication:
 * enable the android platform with this exact package, and register
 * the exact redirect URI) — otherwise bootstrap answers
 * {@code client.bootstrap.invalid}.
 */
public class MainActivity extends Activity {
    private SocialAuthClient client;
    private EditText appId;
    private EditText environment;
    private EditText publicKey;
    private EditText endpoint;
    private EditText redirectUri;
    private android.view.View mainView;
    private boolean showingLoginUi = false;
    // API 33+ routes system Back through the invoked-dispatcher (the
    // onBackPressed override below only runs on older releases).
    private android.window.OnBackInvokedCallback backInvokedCallback;
    // Listens to auth state changes from any source (Google, GitHub, Apple,
    // email/password, guest, refresh, sign-out, deletion).
    private Runnable authStateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = new SocialAuthClient(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        appId = field(root, "app_4a17c6406abaefdf0d9a705e7175b37c");
        environment = field(root, "development");
        publicKey = field(root, "sdk_pk_24cf6cc954d16591812fd1b9953ef053f5133b13f7cec9ae");
        endpoint = field(root, "https://id.open4id.com/api");
        redirectUri = field(root, "com.open4id.example.android://open4id/callback");

        Button loginBtn = new Button(this);
        loginBtn.setText("Open login UI");
        loginBtn.setOnClickListener(v -> openLoginUi());
        root.addView(loginBtn,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        Button dialogBtn = new Button(this);
        dialogBtn.setText("Open login dialog");
        dialogBtn.setOnClickListener(v -> openLoginDialog());
        root.addView(dialogBtn,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        mainView = outer;
        // Target SDK 36 enforces edge-to-edge: without this the top
        // controls render under the status bar (untappable, partly
        // hidden). Confine content to the system windows instead.
        outer.setFitsSystemWindows(true);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = () -> {
                if (showingLoginUi) {
                    showingLoginUi = false;
                    setContentView(mainView);
                } else {
                    finish();
                }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backInvokedCallback);
        }
        setContentView(outer);
        applyLightStatusBar();
        // Listen to auth state changes from any source so the main app
        // can react (e.g. show game lobby when signed in).
        authStateListener = client.onAuthStateChanged((session, user) -> {
            if (session != null) {
                android.widget.Toast.makeText(MainActivity.this,
                        "auth state: signed in sub=" + session.userId,
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                android.widget.Toast.makeText(MainActivity.this,
                        "auth state: signed out",
                        android.widget.Toast.LENGTH_LONG).show();
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

    @Override
    protected void onDestroy() {
        if (authStateListener != null) {
            authStateListener.run();
        }
        if (backInvokedCallback != null
                && android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        super.onDestroy();
    }

    private void openLoginUi() {
        SocialAuthClient current;
        try {
            client.configure(text(appId), text(environment), text(publicKey),
                    text(endpoint), text(redirectUri), null,
                    getPackageName());
            current = client;
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, "configure: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        current.bootstrap().whenComplete((bootstrap, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                final String message = "bootstrap failed: " + cause.getMessage();
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        message, android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> showLoginUi(bootstrap));
        });
    }

    /**
     * Dialog variant of the login UI: the same SDK login view hosted in
     * an {@link Open4IDLoginDialog} instead of a full-screen swap.
     */
    private void openLoginDialog() {
        SocialAuthClient current;
        try {
            client.configure(text(appId), text(environment), text(publicKey),
                    text(endpoint), text(redirectUri), null,
                    getPackageName());
            current = client;
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, "configure: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        current.bootstrap().whenComplete((bootstrap, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                final String message = "bootstrap failed: " + cause.getMessage();
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        message, android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> showLoginDialog(bootstrap));
        });
    }

    private void showLoginDialog(AuthTypes.Bootstrap bootstrap) {
        // One-call bind-and-show; the returned dialog is closed
        // explicitly on sign-out/account-deleted (sign-in auto-closes).
        final Open4IDLoginDialog[] holder = new Open4IDLoginDialog[1];
        holder[0] = Open4IDLoginDialog.show(this, client, bootstrap, "en",
                new Open4IDAuthView.AuthListener() {
            @Override
            public void onSignedIn(String provider) {
                android.util.Log.i("Open4ID", "login dialog signed in via " + provider);
            }

            @Override
            public void onSignedOut() {
                android.util.Log.i("Open4ID", "login dialog signed out");
                holder[0].close();
            }

            @Override
            public void onAccountDeleted() {
                android.util.Log.i("Open4ID", "login dialog account deleted");
                holder[0].close();
            }
        });
    }

    private void showLoginUi(AuthTypes.Bootstrap bootstrap) {        Open4IDAuthView login = new Open4IDAuthView(this);
        login.setLocale("en");
        login.setBootstrap(bootstrap);
        login.setAuthClient(client);
        login.addAuthListener(new Open4IDAuthView.AuthListener() {
            @Override
            public void onSignedIn(String provider) {
                android.util.Log.i("Open4ID", "login UI signed in via " + provider);
            }

            @Override
            public void onSignedOut() {
                android.util.Log.i("Open4ID", "login UI signed out");
            }

            @Override
            public void onAccountDeleted() {
                android.util.Log.i("Open4ID", "login UI account deleted");
            }
        });
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> {
            showingLoginUi = false;
            setContentView(mainView);
        });
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(login);
        screen.addView(scroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        0, 1f));
        screen.addView(back,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        showingLoginUi = true;
        // Same edge-to-edge guard as the config screen above: keeps the
        // Back button below the status bar.
        screen.setFitsSystemWindows(true);
        setContentView(screen);
    }

    /**
     * System Back returns from the login UI screen to the config screen
     * instead of leaving the app.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (showingLoginUi) {
            showingLoginUi = false;
            setContentView(mainView);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (SocialAuthClient.completeGoogleSignIn(requestCode, data)) {
            return;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && SocialAuthClient.completeDeepLink(intent.getData())) {
            runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                    "provider callback received, exchanging…",
                    android.widget.Toast.LENGTH_SHORT).show());
        }
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

    private static String text(EditText edit) {
        return edit.getText().toString().trim();
    }
}
