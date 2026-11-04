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
    // Example-owned real Play Billing client for sandbox purchases; the
    // SDK has no store dependency and never opens a purchase sheet.
    private PlayBillingHelper billing;
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
        if (billing != null) {
            billing.end();
            billing = null;
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
            client.configure(text(appId), text(environment), text(publicKey),
                    text(endpoint), text(redirectUri), null,
                    getPackageName());
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

        com.open4id.sdk.PaymentTypes.PaymentCatalog catalog = client.payments().products();
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
        restoreBtn.setOnClickListener(v ->
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
                }));
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
        com.open4id.sdk.PaymentTypes.TransactionProof proof =
                com.open4id.sdk.PaymentTypes.TransactionProof.googlePlay(productId, token);
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
        com.open4id.sdk.PaymentTypes.PaymentProduct product =
                client.payments().products().product(productId);
        return product != null && product.isConsumable();
    }

    /** Catalog type decides whether Play is queried as INAPP or SUBS. */
    private boolean isSubscriptionProduct(String productId) {
        com.open4id.sdk.PaymentTypes.PaymentProduct product =
                client.payments().products().product(productId);
        return product != null && "subscription".equals(product.type);
    }

    private java.util.Set<String> consumableProductIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (com.open4id.sdk.PaymentTypes.PaymentProduct product
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
