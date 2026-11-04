package com.open4id.example.android;

import android.app.Activity;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.open4id.sdk.PaymentTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Sample-only Play Billing wrapper (billing 9.x).
 *
 * <p>It exists purely so the manual test host can perform a real sandbox
 * purchase and hand the resulting purchase token to the Open4ID payments
 * surface. The SDK itself never depends on a store; this class is not part
 * of it.</p>
 *
 * <p>Purchases use a license-tester account for free test instruments. A
 * license tester can also buy from a sideloaded release or debug build: Play
 * bypasses its install-source check for testers as long as the package is
 * configured in Play and the products are active. A non-tester sideload answers
 * {@code ITEM_UNAVAILABLE}/{@code SERVICE_DISCONNECTED}.</p>
 */
final class PlayBillingHelper {

    /** Billing connection lifecycle. */
    interface StateListener {
        void onReady();

        void onDisconnected(String message);
    }

    /** Outcome of one purchase attempt. */
    interface PurchaseListener {
        void onPurchased(String productId, String purchaseToken);

        void onCancelled();

        void onFailed(String message);
    }

    private final Activity activity;
    private final Consumer<String> log;
    private final BillingClient billingClient;
    private PurchaseListener pendingPurchase;
    /**
     * Purchases returned by the last {@link #collectProofs()} run, so the
     * sample can acknowledge them once Open4ID accepted the proofs.
     */
    private final List<Purchase> lastCollected = Collections.synchronizedList(new ArrayList<>());

    PlayBillingHelper(Activity activity, Consumer<String> log) {
        this.activity = activity;
        this.log = log;
        this.billingClient = BillingClient.newBuilder(activity)
                .setListener(this::onPurchasesUpdated)
                // One-time products must be enabled explicitly since billing 8.
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
    }

    boolean isReady() {
        return billingClient.isReady();
    }

    /** Connects once; a live connection answers immediately. */
    void connect(StateListener listener) {
        if (billingClient.isReady()) {
            listener.onReady();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    listener.onReady();
                } else {
                    listener.onDisconnected(describe(result));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                listener.onDisconnected("service disconnected");
            }
        });
    }

    void end() {
        billingClient.endConnection();
    }

    /**
     * Prices a one-time product or subscription, then opens the Play purchase
     * sheet. The catalog type decides the Play product type: subscriptions
     * are queried as {@code SUBS} and bought with an offer token, everything
     * else as {@code INAPP}. When the requested type is missing, it retries
     * the other one so a mistyped catalog entry still reveals the real store
     * state instead of a bare "not found".
     */
    void buy(String productId, boolean subscription, PurchaseListener listener) {
        if (productId == null || productId.isEmpty()) {
            listener.onFailed("product id required");
            return;
        }
        if (!billingClient.isReady()) {
            listener.onFailed("billing not connected yet");
            return;
        }
        pendingPurchase = listener;
        resolveProduct(productId, subscription
                ? BillingClient.ProductType.SUBS
                : BillingClient.ProductType.INAPP, true, listener);
    }

    private void resolveProduct(String productId, String productType,
            boolean mayRetryOtherType, PurchaseListener listener) {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();
        billingClient.queryProductDetailsAsync(params, (result, details) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                pendingPurchase = null;
                listener.onFailed(describe(result));
                return;
            }
            List<ProductDetails> found = details.getProductDetailsList();
            if (found.isEmpty()) {
                String otherType = BillingClient.ProductType.SUBS.equals(productType)
                        ? BillingClient.ProductType.INAPP
                        : BillingClient.ProductType.SUBS;
                if (mayRetryOtherType) {
                    log.accept("play: " + productId + " not found as " + productType
                            + ", retrying as " + otherType);
                    resolveProduct(productId, otherType, false, listener);
                } else {
                    pendingPurchase = null;
                    listener.onFailed("product " + productId + " not found in Play ("
                            + productType + ")");
                }
                return;
            }
            ProductDetails productDetails = found.get(0);
            BillingFlowParams.ProductDetailsParams.Builder chosen =
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails);
            if (BillingClient.ProductType.SUBS.equals(productType)) {
                List<ProductDetails.SubscriptionOfferDetails> offers =
                        productDetails.getSubscriptionOfferDetails();
                if (offers == null || offers.isEmpty()) {
                    pendingPurchase = null;
                    listener.onFailed("subscription " + productId
                            + " has no active base plan with an offer");
                    return;
                }
                ProductDetails.SubscriptionOfferDetails offer = offers.get(0);
                chosen.setOfferToken(offer.getOfferToken());
                log.accept("play price: " + priceOf(offer)
                        + " (base plan " + offer.getBasePlanId() + ")");
            } else {
                log.accept("play price: " + priceOf(productDetails));
            }
            BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(chosen.build()))
                    .build();
            BillingResult launched = billingClient.launchBillingFlow(activity, flow);
            if (launched.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                pendingPurchase = null;
                listener.onFailed(describe(launched));
            }
        });
    }

    /**
     * Acknowledges a verified purchase so Play does not auto-refund it after
     * three days. Called only after Open4ID accepts the proof. Only valid for
     * a one-time product or subscription; a consumable must be consumed
     * instead (see {@link #finishPurchase}).
     */
    void acknowledge(String purchaseToken) {
        if (purchaseToken == null || purchaseToken.isEmpty()) {
            return;
        }
        billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                result -> log.accept(result.getResponseCode() == BillingClient.BillingResponseCode.OK
                        ? "play purchase acknowledged"
                        : "acknowledge failed: " + describe(result)));
    }

    /**
     * Consumes a Play consumable, which both acknowledges it and makes the
     * product buyable again. Open4ID already consumes a verified consumable
     * server-side, so Play usually answers "already consumed"; that is a
     * success, not a failure. Never acknowledge a consumable instead: an
     * acknowledged but unconsumed consumable stays owned and auto-refunds.
     */
    void consume(String purchaseToken) {
        if (purchaseToken == null || purchaseToken.isEmpty()) {
            return;
        }
        billingClient.consumeAsync(
                ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                (result, token) -> log.accept(result.getResponseCode() == BillingClient.BillingResponseCode.OK
                        ? "play consumable consumed"
                        : "consume failed: " + describe(result)));
    }

    /**
     * Finishes a verified purchase the way its catalog type requires:
     * consume a consumable, acknowledge everything else.
     */
    void finishPurchase(String purchaseToken, boolean consumable) {
        if (consumable) {
            consume(purchaseToken);
        } else {
            acknowledge(purchaseToken);
        }
    }

    /**
     * Everything Play reports as owned by the signed-in store account, as
     * proofs for {@code client.payments().restore()}. In-app purchases and
     * subscriptions are both queried, so a restore after a reinstall gets
     * every purchase back in one call.
     *
     * <p>Registered with {@code setProofSource}, so the sample does not
     * forward proofs by hand. Never fails when only one product type answers:
     * it fails only when both queries failed and nothing was collected.</p>
     */
    CompletableFuture<List<PaymentTypes.TransactionProof>> collectProofs() {
        final CompletableFuture<List<PaymentTypes.TransactionProof>> result = new CompletableFuture<>();
        if (!billingClient.isReady()) {
            result.completeExceptionally(new IllegalStateException("billing not connected yet"));
            return result;
        }
        final Object lock = new Object();
        final List<PaymentTypes.TransactionProof> proofs = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        lastCollected.clear();
        final AtomicInteger remaining = new AtomicInteger(2);
        PurchasesResponseListener listener = (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                List<Purchase> found = purchases != null ? purchases : Collections.emptyList();
                synchronized (lock) {
                    for (Purchase purchase : found) {
                        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                            continue;
                        }
                        lastCollected.add(purchase);
                        List<String> products = purchase.getProducts();
                        String productId = products != null && !products.isEmpty()
                                ? products.get(0) : "";
                        if (!productId.isEmpty()) {
                            proofs.add(PaymentTypes.TransactionProof.googlePlay(
                                    productId, purchase.getPurchaseToken()));
                        }
                    }
                }
            } else {
                synchronized (lock) {
                    failures.add(describe(billingResult));
                }
            }
            if (remaining.decrementAndGet() == 0) {
                List<PaymentTypes.TransactionProof> collected;
                String failure;
                synchronized (lock) {
                    collected = new ArrayList<>(proofs);
                    failure = failures.isEmpty() ? null : failures.get(0);
                }
                if (collected.isEmpty() && failure != null) {
                    result.completeExceptionally(new IllegalStateException(failure));
                } else {
                    log.accept("play purchases: " + collected.size() + " owned");
                    if (failure != null) {
                        log.accept("play purchase query failed: " + failure);
                    }
                    result.complete(collected);
                }
            }
        };
        for (String productType : new String[] {
                BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS}) {
            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(productType).build(),
                    listener);
        }
        return result;
    }

    /**
     * Finishes every purchase from the last {@link #collectProofs()} run the
     * way its catalog type requires: consumables are consumed, everything else
     * that Play still reports unacknowledged is acknowledged. Call after a
     * restore the server accepted, never before, so an unverified purchase can
     * still be auto-refunded.
     */
    void finishCollected(java.util.Set<String> consumableProductIds) {
        List<Purchase> purchases;
        synchronized (lastCollected) {
            purchases = new ArrayList<>(lastCollected);
        }
        for (Purchase purchase : purchases) {
            List<String> products = purchase.getProducts();
            String productId = products != null && !products.isEmpty() ? products.get(0) : "";
            if (consumableProductIds != null && consumableProductIds.contains(productId)) {
                consume(purchase.getPurchaseToken());
            } else if (!purchase.isAcknowledged()) {
                acknowledge(purchase.getPurchaseToken());
            }
        }
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        PurchaseListener listener = pendingPurchase;
        if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            pendingPurchase = null;
            if (listener != null) {
                listener.onCancelled();
            }
            return;
        }
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            pendingPurchase = null;
            if (listener != null) {
                listener.onFailed(describe(result));
            }
            return;
        }
        if (purchases == null || purchases.isEmpty()) {
            pendingPurchase = null;
            if (listener != null) {
                listener.onFailed("no purchase returned");
            }
            return;
        }
        Purchase purchase = purchases.get(0);
        pendingPurchase = null;
        if (listener != null) {
            List<String> products = purchase.getProducts();
            String productId = products != null && !products.isEmpty() ? products.get(0) : "";
            listener.onPurchased(productId, purchase.getPurchaseToken());
        }
    }

    private static String priceOf(ProductDetails productDetails) {
        ProductDetails.OneTimePurchaseOfferDetails offer =
                productDetails.getOneTimePurchaseOfferDetails();
        if (offer != null && offer.getFormattedPrice() != null) {
            return offer.getFormattedPrice();
        }
        return "(no one-time price)";
    }

    private static String priceOf(ProductDetails.SubscriptionOfferDetails offer) {
        List<ProductDetails.PricingPhase> phases =
                offer.getPricingPhases().getPricingPhaseList();
        if (phases.isEmpty()) {
            return "(no subscription price)";
        }
        ProductDetails.PricingPhase phase = phases.get(phases.size() - 1);
        String price = phase.getFormattedPrice();
        String period = phase.getBillingPeriod();
        return period == null || period.isEmpty() ? price : price + " / " + period;
    }

    private static String describe(BillingResult result) {
        String debug = result.getDebugMessage();
        return "code " + result.getResponseCode() + (debug != null ? " (" + debug + ")" : "");
    }
}
