package com.open4id.example.android;

import android.app.Activity;
import android.content.Intent;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.Tasks;

import com.open4id.sdk.AuthErrors;
import com.open4id.sdk.Open4IDAuthClient;
import com.open4id.sdk.Open4IDAuthClient.Config;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal UI host override: interactive Google Sign-In only.
 *
 * <p>GitHub and Apple Custom Tab flows are implemented by the base SDK
 * ({@link Open4IDAuthClient#openGitHubCustomTab} /
 * {@link Open4IDAuthClient#openAppleCustomTab} and
 * {@link Open4IDAuthClient#completeDeepLink}); this subclass only adds
 * the interactive {@code GoogleSignInClient.getSignInIntent()} account
 * picker, which requires {@code com.google.android.gms.auth.api.signin}
 * ({@code play-services-auth}).
 *
 * <p>Host {@link Activity} must forward {@code onActivityResult} to
 * {@link #completeGoogleSignIn(int, Intent)}.
 */
final class SocialAuthClient extends Open4IDAuthClient {
    /** Request code for the Google sign-in intent. */
    static final int GOOGLE_SIGN_IN_RC = 9001;
    private static final long USER_BUDGET_MS = 120_000L;
    private static final Object PENDING_LOCK = new Object();
    private static CompletableFuture<String> googlePending;

    SocialAuthClient(Activity host) {
        super(host);
    }

    /* ---------------------------------------------------------------- Google */

    @Override
    protected String getGoogleAuthorizationCode(Config current, String clientId,
                                                Activity activity, int timeoutMs) {
        CompletableFuture<String> flight = new CompletableFuture<>();
        synchronized (PENDING_LOCK) {
            googlePending = flight;
        }
        try {
            if (activity == null) {
                throw new AuthErrors.SdkValidationError("client.auth.invalid_request",
                        Collections.singletonMap("activity", "required"));
            }
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                    GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestServerAuthCode(clientId)
                    .requestEmail()
                    .build();
            GoogleSignInClient googleClient = GoogleSignIn.getClient(activity, gso);
            // Best-effort sign-out so the picker always appears and the
            // returned account carries a fresh server auth code.
            try {
                Tasks.await(googleClient.signOut(), 8, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            activity.runOnUiThread(() -> activity.startActivityForResult(
                    googleClient.getSignInIntent(), GOOGLE_SIGN_IN_RC));
            long budgetMs = timeoutMs > 0 ? timeoutMs : USER_BUDGET_MS;
            String code;
            try {
                code = flight.get(budgetMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthErrors.SdkAuthError("client.auth.access_denied");
            } catch (TimeoutException e) {
                throw new AuthErrors.SdkAuthError("client.auth.popup_timeout");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof AuthErrors.SdkError) {
                    throw (AuthErrors.SdkError) cause;
                }
                throw new AuthErrors.SdkAuthError("client.auth.access_denied");
            }
            if (code == null || code.isEmpty()) {
                throw new AuthErrors.SdkAuthError("client.auth.access_denied");
            }
            return code;
        } finally {
            synchronized (PENDING_LOCK) {
                if (googlePending == flight) {
                    googlePending = null;
                }
            }
        }
    }

    /**
     * Delivers the Google sign-in intent result parked by
     * {@link #getGoogleAuthorizationCode}. Returns true when the request
     * code belonged to the Google flow. Call from
     * {@code Activity.onActivityResult}.
     */
    static boolean completeGoogleSignIn(int requestCode, Intent data) {
        if (requestCode != GOOGLE_SIGN_IN_RC) {
            return false;
        }
        CompletableFuture<String> flight;
        synchronized (PENDING_LOCK) {
            flight = googlePending;
        }
        if (flight == null) {
            return true;
        }
        try {
            // Never Tasks.await here — onActivityResult runs on the main
            // thread and GMS forbids blocking awaits on it.
            GoogleSignInAccount account =
                    GoogleSignIn.getSignedInAccountFromIntent(data)
                            .getResult(ApiException.class);
            String code = account != null ? account.getServerAuthCode() : null;
            if (code == null || code.isEmpty()) {
                flight.completeExceptionally(
                        new AuthErrors.SdkAuthError("client.auth.access_denied"));
            } else {
                flight.complete(code);
            }
        } catch (ApiException e) {
            flight.completeExceptionally(mapGoogleSignInStatus(e.getStatusCode()));
        } catch (RuntimeException e) {
            flight.completeExceptionally(
                    new AuthErrors.SdkAuthError("client.auth.access_denied"));
        }
        return true;
    }

    private static AuthErrors.SdkError mapGoogleSignInStatus(int statusCode) {
        if (statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
            return new AuthErrors.SdkAuthError("client.auth.popup_closed");
        }
        if (statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
            // Console misconfiguration (wrong package name / SHA-1, or the
            // Android client lives in a different project than the Web ID).
            return new AuthErrors.SdkValidationError("client.auth.provider_unavailable",
                    Collections.singletonMap("provider", "google"));
        }
        return new AuthErrors.SdkAuthError("client.auth.access_denied");
    }
}
