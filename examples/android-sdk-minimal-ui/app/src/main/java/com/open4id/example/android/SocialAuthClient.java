package com.open4id.example.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.Tasks;

import com.open4id.sdk.AuthErrors;
import com.open4id.sdk.Open4IDAuthClient;
import com.open4id.sdk.Open4IDAuthClient.Config;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Open4IDAuthClient with interactive social sign-in flows (Google, GitHub, Apple).
 *
 * <p>Google: the base SDK uses Play Services {@code AuthorizationClient}
 * (offline access), which can resolve silently and return an empty server
 * auth code when the user already consented. This override launches the
 * interactive {@code GoogleSignInClient.getSignInIntent()} account picker
 * instead, so every sign-in shows UI and returns a fresh server auth code
 * for the Web client ID from bootstrap.
 *
 * <p>GitHub / Apple: the base SDK leaves {@code openGitHubCustomTab} and
 * {@code openAppleCustomTab} unimplemented (throws
 * {@code provider_unavailable}). These overrides open the provider
 * authorize URL in a Custom Tab and await the {@code ?code&state} deep
 * link fired back to the app by the Portal bridge.
 *
 * <p>Host {@link Activity} must forward {@code onActivityResult} to
 * {@link #completeGoogleSignIn(int, Intent)} and {@code onNewIntent} to
 * {@link #completeDeepLink(android.net.Uri)}.
 */
final class SocialAuthClient extends Open4IDAuthClient {
    /** Request code for the Google sign-in intent. */
    static final int GOOGLE_SIGN_IN_RC = 9001;
    private static final long USER_BUDGET_MS = 120_000L;

    private static final Object PENDING_LOCK = new Object();
    private static CompletableFuture<String> googlePending;
    private static CompletableFuture<String> githubPending;
    private static String githubState;
    private static CompletableFuture<String> applePending;
    private static String appleState;

    private final WeakReference<Activity> host;

    SocialAuthClient(Activity host) {
        super();
        this.host = new WeakReference<>(host);
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
            com.google.android.gms.auth.api.signin.GoogleSignInOptions gso =
                    new com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestServerAuthCode(clientId)
                            .requestEmail()
                            .build();
            com.google.android.gms.auth.api.signin.GoogleSignInClient googleClient =
                    com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(activity, gso);
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
            com.google.android.gms.auth.api.signin.GoogleSignInAccount account =
                    com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
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

    /* ---------------------------------------------------------------- GitHub */

    @Override
    protected String openGitHubCustomTab(Config current, String clientId, String callbackUri,
                                         String state, int timeoutMs) {
        String url = "https://github.com/login/oauth/authorize"
                + "?client_id=" + Uri.encode(clientId)
                + "&redirect_uri=" + Uri.encode(callbackUri)
                + "&scope=" + Uri.encode("read:user user:email")
                + "&state=" + Uri.encode(state);
        parkGithub(state);
        openTab(url);
        try {
            return awaitSlot(githubPending, "github");
        } finally {
            clearGithub();
        }
    }

    /* ---------------------------------------------------------------- Apple */

    @Override
    protected String openAppleCustomTab(Config current, String clientId, String returnUri,
                                        String nonce, int timeoutMs) {
        // Email scope with form_post: the share/hide-email choice appears and
        // first-time sign-in carries a verified address the server can create
        // a player from. The Portal bridge accepts Apple's form_post
        // server-side and hands ?code&state to the app deep link.
        String url = "https://appleid.apple.com/auth/authorize"
                + "?client_id=" + Uri.encode(clientId)
                + "&redirect_uri=" + Uri.encode(returnUri)
                + "&response_type=" + Uri.encode("code")
                + "&response_mode=" + Uri.encode("form_post")
                + "&scope=" + Uri.encode("email")
                + "&state=" + Uri.encode(nonce)
                + "&nonce=" + Uri.encode(nonce);
        parkApple(nonce);
        openTab(url);
        try {
            return awaitSlot(applePending, "apple");
        } finally {
            clearApple();
        }
    }

    /**
     * Completes the parked social authorization from the deep-link intent.
     * Returns true when the URI belonged to a social flow. Call from
     * {@code Activity.onNewIntent}.
     */
    static boolean completeDeepLink(Uri uri) {
        if (uri == null || !"com.open4id.example.android".equals(uri.getScheme())
                || !"open4id".equals(uri.getHost())) {
            return false;
        }
        String path = uri.getPath();
        if ("/github-callback".equals(path)) {
            return completeSlot(githubPending, githubState, uri);
        }
        if ("/apple-callback".equals(path)) {
            return completeSlot(applePending, appleState, uri);
        }
        return false;
    }

    private void openTab(String url) {
        Activity activity = host.get();
        if (activity == null) {
            throw new AuthErrors.SdkValidationError("client.auth.popup_blocked");
        }
        try {
            final CustomTabsIntent tabs = new CustomTabsIntent.Builder().build();
            tabs.intent.setData(Uri.parse(url));
            activity.runOnUiThread(() -> activity.startActivity(tabs.intent));
        } catch (RuntimeException e) {
            throw new AuthErrors.SdkValidationError("client.auth.popup_blocked");
        }
    }

    private static String awaitSlot(CompletableFuture<String> flight, String provider) {
        try {
            String code = flight.get(USER_BUDGET_MS, TimeUnit.MILLISECONDS);
            if (code == null || code.isEmpty()) {
                throw new AuthErrors.SdkAuthError("client.auth.access_denied");
            }
            return code;
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
    }

    private static boolean completeSlot(CompletableFuture<String> flight, String expected,
                                        Uri uri) {
        if (flight == null) {
            return true;
        }
        String error = uri.getQueryParameter("error");
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        if (error != null || code == null || code.isEmpty()
                || state == null || !state.equals(expected)) {
            flight.completeExceptionally(
                    new AuthErrors.SdkAuthError("client.auth.access_denied"));
        } else {
            flight.complete(code);
        }
        return true;
    }

    private static void parkGithub(String state) {
        synchronized (PENDING_LOCK) {
            githubPending = new CompletableFuture<>();
            githubState = state;
        }
    }

    private static void clearGithub() {
        synchronized (PENDING_LOCK) {
            githubPending = null;
            githubState = null;
        }
    }

    private static void parkApple(String nonceAsState) {
        synchronized (PENDING_LOCK) {
            applePending = new CompletableFuture<>();
            appleState = nonceAsState;
        }
    }

    private static void clearApple() {
        synchronized (PENDING_LOCK) {
            applePending = null;
            appleState = null;
        }
    }
}
