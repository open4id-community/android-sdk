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
import com.open4id.sdk.Open4IDPlayBillingStore;
import com.open4id.sdk.PaymentTypes;

/**
 * Smallest possible published-SDK integration: the headless
 * {@link Open4IDAuthClient} driven directly (bootstrap, register,
 * sign-in, guest, refresh, sign out, payments). No framework, no view code.
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
    private android.view.View mainView;
    // Listens to auth state changes from any source (sign-in, sign-out,
    // refresh, deletion).
    private Runnable authStateListener;
    // Payments screen state (see openPayments).
    private boolean showingPayments = false;
    private android.window.OnBackInvokedCallback backInvokedCallback;
    /** Detaches the payment screen's entitlement listener on exit. */
    private Runnable paymentsUnsubscribe;
    // The SDK's built-in Play Billing store (created on first open); the
    // SDK owns the sheet, verification, and the acknowledge lifecycle.
    private Open4IDPlayBillingStore store;

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
        button(root, "5. Payments (Play Billing)", this::openPayments);

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
        mainView = outer;
        // Target SDK 36 enforces edge-to-edge: without this the top
        // fields render under the status bar. Confine content to the
        // system windows instead.
        outer.setFitsSystemWindows(true);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // System Back returns from the payments screen instead of
            // leaving the app (API 33+ routes Back through the dispatcher;
            // the onBackPressed override below covers older releases).
            backInvokedCallback = () -> {
                if (showingPayments) {
                    leavePayments();
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

    /**
     * Payments screen: the SDK's built-in Play Billing store owns the whole
     * purchase lifecycle (sheet → verification → acknowledge/consume after
     * acceptance), so the example only drives it and logs every step.
     * Opening the screen and the "Restore purchases" button both call
     * {@code store.restore()}, which uses the proof source the store
     * registered — so the same call recovers a purchase after a restart and
     * after a reinstall.
     */
    private void openPayments() {
        try {
            configureClient();
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

        PaymentTypes.PaymentCatalog catalog = client.payments().products();
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
                    for (PaymentTypes.PaymentPurchase purchase : rows) {
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
                    appendLog("entitlements ok: " + snapshot.entitlements.size());
                }));
        screen.addView(refreshBtn);

        TextView state = new TextView(this);
        state.setTextSize(13);
        state.setTextIsSelectable(true);
        ScrollView stateScroll = new ScrollView(this);
        stateScroll.addView(state);
        screen.addView(stateScroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        0, 1f));

        Runnable render = () -> state.setText(describeEntitlements());
        render.run();
        paymentsUnsubscribe = client.entitlements().onChanged(
                snapshot -> runOnUiThread(render));

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> leavePayments());
        screen.addView(back,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        showingPayments = true;
        // Same edge-to-edge guard as the main screen above.
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
        setContentView(mainView);
    }

    private static String firstPlayProduct(PaymentTypes.PaymentCatalog catalog) {
        for (PaymentTypes.PaymentProduct product : catalog.forPlatform("google_play")) {
            return product.productId;
        }
        return "sku_premium";
    }

    private static String describeCatalog(PaymentTypes.PaymentCatalog catalog) {
        if (!catalog.enabled) {
            return "payments: disabled (no store enabled or no catalog)";
        }
        StringBuilder out = new StringBuilder("payments: enabled, ")
                .append(catalog.products.size()).append(" product(s)\n");
        for (PaymentTypes.PaymentProduct product : catalog.forPlatform("google_play")) {
            out.append("  ").append(product.productId)
                    .append(" [").append(product.type).append("] -> ")
                    .append(product.isConsumable()
                            ? "(consumable, no entitlement)" : product.entitlements)
                    .append('\n');
        }
        return out.toString();
    }

    private String describeEntitlements() {
        PaymentTypes.EntitlementSnapshot snapshot = client.entitlements().current();
        if (snapshot == null) {
            return "entitlements: none fetched yet (sign in, then buy or restore)";
        }
        StringBuilder out = new StringBuilder("entitlements fetchedAt=")
                .append(snapshot.fetchedAtMillis).append('\n');
        for (PaymentTypes.Entitlement entitlement : snapshot.entitlements) {
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
        if (backInvokedCallback != null
                && android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
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
        super.onDestroy();
    }

    /**
     * System Back returns from the payments screen to the main panel
     * instead of leaving the app (pre-API-33 path; the registered
     * back-invoked callback covers newer releases).
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (showingPayments) {
            leavePayments();
            return;
        }
        super.onBackPressed();
    }
}
