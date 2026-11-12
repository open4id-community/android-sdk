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
import android.widget.TextView;

import com.open4id.sdk.AuthTypes;
import com.open4id.sdk.Open4IDPlayBillingStore;
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
    // Payments screen state (see openPayments).
    private boolean showingPayments = false;
    /** Detaches the payment screen's entitlement listener on exit. */
    private Runnable paymentsUnsubscribe;
    // The SDK's built-in Play Billing store (created on first open); the
    // SDK owns the sheet, verification, and the acknowledge lifecycle.
    private Open4IDPlayBillingStore store;
    /** Log surface of the open payments screen (null when closed). */
    private TextView paymentsLog;
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

        Button paymentsBtn = new Button(this);
        paymentsBtn.setText("Payments (Play Billing)");
        paymentsBtn.setOnClickListener(v -> openPayments());
        root.addView(paymentsBtn,
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
                if (showingPayments) {
                    leavePayments();
                } else if (showingLoginUi) {
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
        if (paymentsUnsubscribe != null) {
            paymentsUnsubscribe.run();
            paymentsUnsubscribe = null;
        }
        if (store != null) {
            store.end();
            store = null;
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

    /**
     * Payments screen: the SDK's built-in Play Billing store owns the whole
     * purchase lifecycle (sheet → verification → acknowledge/consume after
     * acceptance), so the example only drives it and logs every step.
     * Since the SDK rehydrates paid state once per process after the first
     * bootstrap (a stored session refreshes the ledger; a lost session
     * restores through the store's proof source), the "Restore purchases"
     * button below is mostly a demonstration — opening the screen still
     * calls {@code store.restore()} explicitly so the flow is observable in
     * the log.
     */
    private void openPayments() {
        try {
            client.configure(text(appId), text(environment), text(publicKey),
                    text(endpoint), text(redirectUri), null,
                    getPackageName());
        } catch (RuntimeException e) {
            appendLog("configure: " + e.getMessage());
            return;
        }
        if (store == null) {
            // One call per client: creates the store and registers its
            // proof source, so restore() is self-sufficient.
            store = client.playBillingPayments(this);
        }
        appendLog("payments bootstrap…");
        client.bootstrap().whenComplete((bootstrap, error) -> {
            if (error != null) {
                appendLog("payments bootstrap failed: " + rootMessage(error));
                return;
            }
            runOnUiThread(this::showPayments);
        });
    }

    private void showPayments() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(pad, pad, pad, pad);

        com.open4id.sdk.PaymentTypes.PaymentCatalog catalog = client.payments().products();
        TextView catalogView = new TextView(this);
        catalogView.setTextSize(13);
        catalogView.setText(describeCatalog(catalog));
        screen.addView(catalogView);

        EditText productId = field(screen, firstPlayProduct(catalog));

        // Resolve the shop with Play prices, then run the startup restore —
        // the first load also connects the built-in billing client.
        store.loadProducts().whenComplete((rows, error) -> {
            if (error != null) {
                appendLog("shop failed: " + rootMessage(error));
                restorePurchases("startup restore (ledger only)");
                return;
            }
            for (Open4IDPlayBillingStore.PlayStoreProduct row : rows) {
                appendLog("shop: " + row.product.productId + " [" + row.product.type
                        + "] " + (row.isResolvable()
                                ? row.formattedPrice : "(not in Play — Buy disabled)"));
            }
            restorePurchases("startup restore");
        });

        Button buyBtn = new Button(this);
        buyBtn.setText("Buy with Play Billing");
        buyBtn.setOnClickListener(v -> store.purchase(text(productId))
                .whenComplete((outcome, error) -> {
                    if (error != null) {
                        appendLog("purchase failed: " + rootMessage(error));
                        return;
                    }
                    if (outcome.isCancelled()) {
                        appendLog("play purchase cancelled");
                        return;
                    }
                    if (outcome.isPending()) {
                        appendLog("play purchase pending (ask-to-buy): "
                                + "a later restore picks it up");
                        return;
                    }
                    appendLog("play purchase ok: " + outcome.productId);
                    if (outcome.receipt != null) {
                        appendLog("consumable " + outcome.receipt.state + ": "
                                + outcome.receipt.transactionRef + " x"
                                + outcome.receipt.quantity
                                + " (refunded " + outcome.receipt.refundedQuantity + ")");
                        return;
                    }
                    appendLog("verify ok: " + outcome.entitlements.size()
                            + " entitlement(s)");
                }));
        screen.addView(buyBtn);

        Button restoreBtn = new Button(this);
        restoreBtn.setText("Restore purchases");
        restoreBtn.setOnClickListener(v -> restorePurchases("restore"));
        screen.addView(restoreBtn);

        // Webhook delivery is not implemented, so refunds are learned by
        // reading the player's ledger receipts. A game grants items keyed
        // by transactionRef and reverses refundedQuantity; the example only
        // logs them because it owns no item balance.
        Button purchasesBtn = new Button(this);
        purchasesBtn.setText("List payment purchases");
        purchasesBtn.setOnClickListener(v -> client.payments().purchases()
                .whenComplete((rows, error) -> {
                    if (error != null) {
                        appendLog("purchases failed: " + rootMessage(error));
                        return;
                    }
                    appendLog("purchases ok: " + rows.size() + " receipt(s)");
                    for (com.open4id.sdk.PaymentTypes.PaymentPurchase purchase : rows) {
                        appendLog("  " + purchase.transactionRef + " "
                                + purchase.productId + " [" + purchase.type + "] "
                                + purchase.status + " x" + purchase.quantity
                                + (purchase.refundedQuantity > 0
                                        ? " (refunded " + purchase.refundedQuantity + ")"
                                        : ""));
                    }
                }));
        screen.addView(purchasesBtn);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("Refresh entitlements");
        refreshBtn.setOnClickListener(v -> client.entitlements().refresh()
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        appendLog("entitlements failed: " + rootMessage(error));
                        return;
                    }
                    appendLog(describeEntitlements(snapshot));
                }));
        screen.addView(refreshBtn);

        TextView logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextIsSelectable(true);
        paymentsLog = logView;
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        screen.addView(logScroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        0, 1f));

        // Entitlement changes from any source re-render the snapshot.
        paymentsUnsubscribe = client.entitlements().onChanged(
                snapshot -> runOnUiThread(() ->
                        logView.append(describeEntitlements(snapshot) + "\n")));

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> leavePayments());
        screen.addView(back,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        showingPayments = true;
        // Same edge-to-edge guard as the login screen above.
        screen.setFitsSystemWindows(true);
        setContentView(screen);
    }

    /**
     * Restores this player's purchases: the ledger recompute plus the store
     * proofs the built-in store collects from Play. A stored session keeps
     * its player and re-submits its purchases; a reinstall starts as a
     * guest and the proof moves an anonymous binding to it. The store
     * finishes restored purchases (consume consumables, acknowledge the
     * rest) only when the server accepted every proof.
     */
    private void restorePurchases(String label) {
        appendLog(label + ": restore…");
        store.restore()
                .whenComplete((result, error) -> {
                    if (error != null) {
                        appendLog(label + " failed: " + rootMessage(error));
                        return;
                    }
                    appendLog(label + " ok: " + result.entitlements.size()
                            + " entitlement(s), verified=" + result.verified
                            + " rejected=" + result.rejected
                            + " conflict=" + result.conflict
                            + (result.proofsUnavailable
                                    ? ", store proofs unavailable" : ""));
                    if (result.conflict > 0) {
                        appendLog(label + ": purchase owned by another account — "
                                + "sign in as that account to recover it");
                    }
                });
    }

    private void leavePayments() {
        showingPayments = false;
        if (paymentsUnsubscribe != null) {
            paymentsUnsubscribe.run();
            paymentsUnsubscribe = null;
        }
        paymentsLog = null;
        setContentView(mainView);
    }

    private static String firstPlayProduct(
            com.open4id.sdk.PaymentTypes.PaymentCatalog catalog) {
        for (com.open4id.sdk.PaymentTypes.PaymentProduct product
                : catalog.forPlatform("google_play")) {
            return product.productId;
        }
        return "sku_premium";
    }

    private static String describeCatalog(
            com.open4id.sdk.PaymentTypes.PaymentCatalog catalog) {
        if (!catalog.enabled) {
            return "payments: disabled (no store enabled or no catalog)";
        }
        StringBuilder out = new StringBuilder("payments: enabled, ")
                .append(catalog.products.size()).append(" product(s)\n");
        for (com.open4id.sdk.PaymentTypes.PaymentProduct product
                : catalog.forPlatform("google_play")) {
            out.append("  ").append(product.productId)
                    .append(" [").append(product.type).append("] -> ")
                    .append(product.isConsumable()
                            ? "(consumable, no entitlement)" : product.entitlements)
                    .append('\n');
        }
        return out.toString();
    }

    private static String describeEntitlements(
            com.open4id.sdk.PaymentTypes.EntitlementSnapshot snapshot) {
        if (snapshot == null) {
            return "entitlements: none fetched yet (sign in, then buy or restore)";
        }
        StringBuilder out = new StringBuilder("entitlements fetchedAt=")
                .append(snapshot.fetchedAtMillis).append('\n');
        for (com.open4id.sdk.PaymentTypes.Entitlement entitlement
                : snapshot.entitlements) {
            out.append("  ").append(entitlement.key)
                    .append(" = ").append(entitlement.state)
                    .append(" via ").append(entitlement.source);
            if (entitlement.expiresAt != null) {
                out.append(" until ").append(entitlement.expiresAt);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Log lines from the payments screen go into its own log view; the
     * Toast surface stays for the login screens.
     */
    private void appendLog(String line) {
        TextView target = paymentsLog;
        if (target != null) {
            runOnUiThread(() -> target.append(line + "\n"));
            return;
        }
        runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                line, android.widget.Toast.LENGTH_LONG).show());
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return String.valueOf(cause.getMessage());
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
        if (showingPayments) {
            leavePayments();
            return;
        }
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
        if (intent != null && client.completeDeepLink(intent.getData())) {
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
