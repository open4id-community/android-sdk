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
    // Example-owned real Play Billing client for sandbox purchases; the
    // SDK has no store dependency and never opens a purchase sheet.
    private PlayBillingHelper billing;

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
     * Payments screen: the headless payment surface behind a real
     * example-owned Play Billing client (billing 9.x). The SDK has no store
     * dependency: Play produces the purchase token, the SDK verifies it
     * server-side through Core API. Opening the screen and the "Restore
     * purchases" button both call {@code payments().restore()}, which uses
     * the proof source registered below — so the same call recovers a
     * purchase after a restart and after a reinstall.
     */
    private void openPayments() {
        try {
            configureClient();
        } catch (RuntimeException e) {
            appendLog("configure: " + e.getMessage());
            return;
        }
        if (billing == null) {
            billing = new PlayBillingHelper(this, this::appendLog);
        }
        // One registration makes restore() self-sufficient: it asks Play
        // which purchases this store account owns and submits them with the
        // ledger recompute, so a reinstall recovers the purchase as a fresh
        // guest with no host wiring at the call site.
        client.payments().setProofSource(billing::collectProofs);
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

        // Connect the Play Billing client so a sandbox purchase works, then
        // run the startup restore once Play is reachable — the proof source
        // needs a live connection.
        billing.connect(new PlayBillingHelper.StateListener() {
            @Override
            public void onReady() {
                appendLog("play billing ready");
                restorePurchases("startup restore");
            }

            @Override
            public void onDisconnected(String message) {
                appendLog("play billing unavailable: " + message);
                restorePurchases("startup restore (ledger only)");
            }
        });

        Button buyBtn = new Button(this);
        buyBtn.setText("Buy with Play Billing");
        buyBtn.setOnClickListener(v -> billing.buy(text(productId),
                isSubscriptionProduct(text(productId)),
                new PlayBillingHelper.PurchaseListener() {
                    @Override
                    public void onPurchased(String boughtProductId, String token) {
                        appendLog("play purchase ok: " + boughtProductId);
                        verifyPlayProof(boughtProductId, token);
                    }

                    @Override
                    public void onCancelled() {
                        appendLog("play purchase cancelled");
                    }

                    @Override
                    public void onFailed(String message) {
                        appendLog("play purchase failed: " + message);
                    }
                }));
        screen.addView(buyBtn);

        Button restoreBtn = new Button(this);
        restoreBtn.setText("Restore purchases");
        restoreBtn.setOnClickListener(v -> {
            // A fresh Play query each time: a purchase made on another
            // device is only visible to Play after a restore/query.
            billing.connect(new PlayBillingHelper.StateListener() {
                @Override
                public void onReady() {
                    restorePurchases("restore");
                }

                @Override
                public void onDisconnected(String message) {
                    appendLog("play billing unavailable: " + message);
                    restorePurchases("restore (ledger only)");
                }
            });
        });
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
     * Submits a Play proof to the Open4ID payments surface. A consumable
     * goes through the consumable delivery call: Open4ID consumes it
     * server-side, so the example grants nothing and only consumes the
     * token again as a harmless safety net. Every other product keeps the
     * entitlement path and is acknowledged so it is not auto-refunded
     * after three days.
     */
    private void verifyPlayProof(String productId, String token) {
        if (productId == null || productId.isEmpty()
                || token == null || token.isEmpty()) {
            appendLog("verify: product id and purchase token are required");
            return;
        }
        PaymentTypes.TransactionProof proof =
                PaymentTypes.TransactionProof.googlePlay(productId, token);
        if (isConsumableProduct(productId)) {
            client.payments().verifyConsumable(proof)
                    .whenComplete((receipt, error) -> {
                        if (error != null) {
                            appendLog("consumable verify failed: " + rootMessage(error));
                            return;
                        }
                        appendLog("consumable " + receipt.state + ": "
                                + receipt.transactionRef + " x" + receipt.quantity
                                + " (refunded " + receipt.refundedQuantity + ")");
                        if ((receipt.isDelivered() || receipt.isAlreadyDelivered())
                                && billing != null) {
                            // The server already consumed it; Play reports
                            // "already consumed" and this is a no-op.
                            billing.consume(token);
                        }
                    });
            return;
        }
        client.payments().verifyTransaction(proof)
                .whenComplete((rows, error) -> {
                    if (error != null) {
                        appendLog("verify failed: " + rootMessage(error));
                        return;
                    }
                    appendLog("verify ok: " + rows.size() + " entitlement(s)");
                    if (billing != null) {
                        billing.acknowledge(token);
                    }
                });
    }

    /** Reads the bootstrap catalog to decide how a purchase is finished. */
    private boolean isConsumableProduct(String productId) {
        PaymentTypes.PaymentProduct product = client.payments().products().product(productId);
        return product != null && product.isConsumable();
    }

    /** Catalog type decides whether Play is queried as INAPP or SUBS. */
    private boolean isSubscriptionProduct(String productId) {
        PaymentTypes.PaymentProduct product = client.payments().products().product(productId);
        return product != null && "subscription".equals(product.type);
    }

    private java.util.Set<String> consumableProductIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PaymentTypes.PaymentProduct product
                : client.payments().products().forPlatform("google_play")) {
            if (product.isConsumable()) {
                ids.add(product.productId);
            }
        }
        return ids;
    }

    /**
     * Restores this player's purchases: the ledger recompute plus the store
     * proofs the registered proof source collects from Play. A stored
     * session keeps its player and re-submits its purchases; a reinstall
     * starts as a guest and the proof moves an anonymous binding to it.
     */
    private void restorePurchases(String label) {
        appendLog(label + ": restore…");
        client.payments().restore()
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
                    // Acknowledge only what the server accepted, so an
                    // unverified purchase can still be auto-refunded.
                    if (billing != null && result.verified > 0 && result.rejected == 0
                            && result.conflict == 0) {
                        billing.finishCollected(consumableProductIds());
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
        if (billing != null) {
            billing.end();
            billing = null;
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
