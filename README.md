# Open4ID Android SDK

Headless Java authorization client plus a ready-made login view for
Open4ID player authentication: OAuth Authorization Code + PKCE, social
sign-in.

## Requirements

- minSdk 26 (Android 8.0)
- compileSdk 36
- Java 11+

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `androidx.browser:browser:1.9.0` | Custom Tabs (auth flows) |
| `com.google.android.gms:play-services-auth:21.2.0` | Google server-auth-code flow (compileOnly, optional at runtime) |
| `com.android.billingclient:billing:9.1.0` | Built-in Play Billing store (inert without Play services; declare `com.android.vending.BILLING`) |

No third-party JSON, HTTP, or crypto libraries. All wire parsing uses pure Java (`java.util`, `java.security`, `java.net`).

## Installation

Published to Maven Central as `com.open4id:open4id-sdk` ([changelog](CHANGELOG.md)):

```gradle
dependencies {
    implementation 'com.open4id:open4id-sdk:0.5.0'
}
```

Multi-module local builds can depend on the source module instead:

```gradle
dependencies {
    implementation project(':open4id-sdk')
}
```

## Quick start: the login view in 5 steps

> Recommended path: a drop-in `Open4IDAuthView` that handles
> email/password, sign-up, guest, and social sign-in with no protocol
> code on your side. The sections below document the pieces: the view in
> detail, the headless client for custom UI, and session handling.
>
> Every network method returns a `CompletableFuture` on an internal
> worker pool — never call it on the main thread and never block on it
> there. The view already follows this rule; the headless snippets
> further below use `.get()` for brevity (call them from a worker
> thread or chain them).

### Step 0 — Publisher setup (dashboard, once)

In your Open4ID application, enable the **android** platform with your
exact `package_name` (e.g. `com.example.game`), register the exact
redirect URI (a custom scheme such as
`com.example.game://open4id/callback` works on device; `http://`
loopback URIs only in development/sandbox), and make sure the public
SDK key allows the `android` platform. The Authentication page shows
the exact callback values to paste at each provider console (Google /
GitHub / Apple) — copy them character for character:

- **Google**: provider enabled, the application's public (Web) client
  ID plus its Vault-backed client secret. In the Google Cloud Console,
  link an Android OAuth client with your package name plus signing-cert
  SHA-1 (debug key:
  `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey`)
  to that Web client ID in the same project.
- **GitHub**: provider enabled; register the shared Portal callback URL
  (`https://app.open4id.com/github-callback` — the JS SDK's derived
  default, shown in the Portal preview) in the GitHub OAuth App settings.
  Native apps call back through
  the Portal too: register one per-app bridge URL,
  `https://app.open4id.com/github-callback/<package_name>`, and pass it
  as the callback URI.
- **Apple**: provider enabled; register the Return URLs derived from every
  exact OAuth redirect URI (their origins — Apple's web popup answers to
  the page's own origin, HTTPS only) in the
  Services ID settings. Native apps call back through the Portal first:
  register one per-app bridge URL,
  `https://app.open4id.com/apple-callback/<package_name>`, and pass it
  as the Return URL.
- **Facebook**: provider enabled; register the shared Portal callback URL
  (`https://app.open4id.com/facebook-callback` — the JS SDK's derived
  default, shown in the Portal preview) in the Facebook app's Valid OAuth
  Redirect URIs. Native apps call back through the Portal too: register
  one per-app bridge URL,
  `https://app.open4id.com/facebook-callback/<package_name>`, and pass it
  as the callback URI.

### Step 1 — Depend on the SDK

```gradle
dependencies {
    implementation project(':open4id-sdk')
}
```

### Step 2 — Create the client and bootstrap once

```java
Open4IDAuthClient client = new Open4IDAuthClient();
client.configure(
        "app_0123456789abcdef",               // appId
        "development",                         // development | sandbox | production
        "sdk_pk_0123456789abcdefghij...",     // publicSdkKey — public key only, never the secret key
        "https://id.open4id.com/api",         // endpoint (optional, defaults to production)
        "com.example.game://open4id/callback", // redirectUri — exact registered URI
        "openid profile email",               // scope (optional, must include openid)
        "com.example.game",                   // packageName — exact registered package
        null                                  // SessionStore (optional, defaults to the persisted snapshot store)
);

// Off the main thread:
AuthTypes.Bootstrap bootstrap = client.bootstrap().get();
// bootstrap.auth.* — OIDC endpoints, scopes
// bootstrap.login.* — provider readiness (google.clientId, github.clientId, facebook.clientId, apple.clientId)
// bootstrap.serverCallback — publisher login receipt callback (opt-in)
```

Prefer one call for the whole launch? `client.start()` bootstraps, waits for
the once-per-process launch restore (paid state settled), and signs in as a
guest when nothing was stored and the application allows guest login —
returning the session to branch on (see [Sessions](#sessions)). Create
`playBillingPayments(activity)` **before** `start()` so the launch restore can
submit store proofs.

### Step 3 — Drop in the login view

```java
import com.open4id.sdk.ui.Open4IDAuthView;

Open4IDAuthView login = new Open4IDAuthView(this);
login.setBootstrap(client.getBootstrap());
login.setAuthClient(client);
login.addAuthListener(new Open4IDAuthView.AuthListener() {
    @Override public void onSignedIn(String provider) {
        // provider: email_password | guest | google | github | apple
        showGameLobby(client.getSession());
    }
    @Override public void onSignedOut() { showLandingPage(); }
    @Override public void onAccountDeleted() { showLandingPage(); }
});
setContentView(login);
```

Email/password, sign-up, guest, Google, GitHub, Facebook, and Apple all
render inside — no provider code on your side. Google needs the view hosted
in an `Activity` (account chooser / consent UI).

### Step 4 — Use the session

```java
client.getSession();        // AuthSession or null
client.getCachedUser();     // { sub, email? } or null
client.isAuthenticated();   // false within 30s of expiry
client.ensureSession(30_000).get(); // no-op when still valid, rotates when near expiry
```

### Step 5 — Refresh, sign out, delete

```java
client.ensureSession(30_000).get(); // call before important calls
client.signOut().get();      // revoke server-side, clear local state
client.deleteAccount().get(); // permanent; needs a signed-in session
```

### Notice:

- **Remember login across restarts and reinstalls:** the SDK persists the
  session by default (private `open4id_session` SharedPreferences, wired
  through the init provider); pass a `SessionStore` to `configure` for a
  custom backend (see Sessions).
- **Threading:** the view never blocks the main thread; if you call
  the headless client directly, chain the futures instead of `.get()`.

## Concepts: login view vs headless client

The SDK has two surfaces over the same protocol (OAuth Authorization Code
+ PKCE, same token endpoint, same session shape):

- **Login view** (`Open4IDAuthView`, `com.open4id.sdk.ui`): the
  recommended path used in the quick start. It renders every sign-in
  method and calls the headless client for you — no provider or token
  code on your side.
- **Headless client** (`Open4IDAuthClient`): use it directly for a
  custom UI, a redirect-only flow, or a single provider call without
  the view.

`bootstrap()` always makes a request; `getBootstrap()` only returns the latest
in-memory successful snapshot. `configure()` resets all session state
(and optionally restores a persisted session from the given
`SessionStore`); `reset()` clears everything back to unconfigured.

## Login view (`com.open4id.sdk.ui`)

`Open4IDAuthView` renders sign-in, sign-up, forgot-password, link-email, and
delete-account modes from safe bootstrap readiness. Provider buttons
carry the inline brand marks (Google, GitHub, Facebook, Apple vector
drawables — no host assets needed). The view shows only the login
methods the Publisher enabled for the environment (normal email/password,
guest, Google, GitHub, Facebook, Apple) — toggled in the Publisher Portal
under App Settings → SDK & API keys → Login methods — and hides the rest
automatically after the next bootstrap. Every protocol operation runs in the bound
headless client: credentials and tokens never leave the view except
through that client.

```java
import com.open4id.sdk.ui.AuthLayout;
import com.open4id.sdk.ui.Open4IDAuthView;

Open4IDAuthView login = new Open4IDAuthView(this);
login.setBootstrap(auth.getBootstrap());
login.setAuthClient(auth);
login.addAuthListener(new Open4IDAuthView.AuthListener() {
    @Override public void onSignedIn(String provider) {
        // (provider: email_password | guest | google | github | apple)
        // read auth.getSession() / auth.getCachedUser() and show the game
    }
    @Override public void onSignedOut() { /* show landing page */ }
    @Override public void onAccountDeleted() { /* show landing page */ }
});
```

The view is session-aware: while the bound client holds a session, the
entry modes (`sign-in`/`sign-up`) render the signed-in account view
(player identity, sign out, delete-account entry) instead of the login
form, and an expired session keeps the same view with a refresh prompt
(`ensureSession` in place) instead of dropping back to login. The delete
entry follows the publisher's delete-button toggle (SDK keys page):
a disabled toggle hides it and the endpoint answers `403`. Explicit
task modes (`forgot-password`, `link-email`, `delete-account`) always
render their own view. Signing out inside the view emits
`onSignedOut` next to the other two callbacks. The view subscribes to
the client's auth-state listener and unsubscribes on detach; remove
your listener with `removeAuthListener` when done.

```java
login.setMode(AuthLayout.Mode.SIGN_UP); // sign-in (default) | sign-up |
    // forgot-password | link-email | delete-account
login.setLocale("en");                  // en default, vi built in
login.setAppName("My Game");            // default: bootstrap displayName
```

Social bridge URLs need no per-app hardcoding: with a custom-scheme
redirect the SDK defaults to the per-app Portal bridges
(`<portalOrigin>/github-callback/<package>`,
`<portalOrigin>/facebook-callback/<package>`, and
`<portalOrigin>/apple-callback/<package>`), which must be registered at
each provider console. The Portal origin defaults to production
(`https://app.open4id.com`); development builds point it at the local
Portal instead:

```java
// Explicit per-call/per-view overrides still win when you host your own
// callback pages (e.g. an http(s) redirect keeps its origin-derived default):
login.setGitHubCallbackUri("https://game.example.com/github-callback");
login.setFacebookCallbackUri("https://game.example.com/facebook-callback");
login.setAppleReturnUri("https://game.example.com/apple-return");
```

Sign-in submits email/password through `signInWithPassword` and reports
wrong credentials or a pending email verification inline (with a
working resend-verification action). Sign-up submits through `register`
and points at the verification email. Forgot-password submits through
`requestPasswordReset` and points at the reset email (single-use
30-minute link consumed on the game's reset page). The guest button
continues as a guest through `continueAsGuest`. The link-email mode
links the guest session to a real address through `linkEmail`: taken
addresses and non-guest sessions get inline guidance instead of raw
errors. The Google button signs in through `signInWithGoogle` (Play
Services server auth code, no navigation — needs the view hosted in an
`Activity`); the GitHub button signs in through `signInWithGitHub`
(Custom Tab plus the game/SaaS callback page); the Facebook button signs
in through `signInWithFacebook` (Custom Tab at the Facebook OAuth dialog,
same Portal-bridge shape); the Apple button signs in through
`signInWithApple` (Custom Tab, no navigation). Unavailable
providers and user cancellations render as info lines; anything else
renders the mapped error. The component never parses, validates,
stores, or exposes player tokens.

The delete-account mode is an explicit confirmation step for the
signed-in player: it calls headless `deleteAccount()` and emits
`onAccountDeleted` on success so the host can clear displayed state;
the headless client already cleared its local session by then.

The view brand names the application instead of a generic product
mark: it reads the bootstrap `displayName` (the application's
configured name) and renders it with its initial as the mark.
Override it explicitly when the game knows its own name without
waiting for bootstrap:

```java
login.setAppName("My Game"); // setAppName(null) clears the override
```

Without an override or a configured name, the view keeps the generic
Open4ID brand.

Headless error keys (`client.auth.*`) are a stable contract and stay
untranslated; only the rendered view text is localized.

## Headless flows

Call the client directly when you own the UI instead of the login view.
Every flow below ends in the same `AuthTypes.AuthSession` (read it through
`getSession()` / `getCachedUser()`, keep it alive with `ensureSession()`
— see Sessions). Guest sign-in (`continueAsGuest`) and guest-to-email
linking (`linkEmail`) follow the same single-use code exchange; the
view wires them to its guest button and link-email mode.

### Email/password, registration, and verification

Prerequisites on the Application/environment: android platform enabled
with the exact `package_name`, the exact redirect URI registered, the
SDK key allowed for `android`, and email/password login enabled.
Native passwords require at least 8 characters; registration answers
with a generic accepted response even for invalid input
(anti-enumeration), so a short password only surfaces later as a
sign-in failure.

Registration sends a 24h verification email. An unverified address may
sign in while its grace period is outstanding; afterwards sign-in fails
until re-verified:

```java
client.register("player@example.com", "sufficient-long-password").get();
client.resendVerification("player@example.com").get(); // generic response
client.verifyEmail("token-from-email").get();          // single-use token from email
```

`signInWithPassword` powers first-party game dialogs such as the login
view: the credentials travel once to
`POST /client/player_authorize`, which answers the same single-use,
PKCE-bound authorization code the hosted form would issue, and the SDK
exchanges it at `/token` exactly as in the other flows.

```java
AuthTypes.AuthSession session = client.signInWithPassword(
        "player@example.com", "sufficient-long-password").get();
```

Wrong credentials throw with `client.player.invalid_credentials`; an
address past its verification grace throws
`client.player.email_not_verified` instead of a session.

### Password reset

```java
client.requestPasswordReset("player@example.com").get(); // generic 202 for any well-formed address; malformed throws SdkValidationError email=invalid
client.resetPassword("token-from-email", "new-sufficient-password").get(); // single-use 30-minute token
```

The request answers success-shaped for any well-formed address so responses never reveal
registration state. Resetting consumes the token, sets the password
(minimum 8 characters), and revokes all sessions for the player; an
unknown or expired token throws `client.auth.reset_invalid`.

### Guest and guest-to-email linking

```java
// Continue as guest
AuthTypes.AuthSession guest = client.continueAsGuest().get();

// Link the guest to a real address (revokes guest tokens, signs in natively)
AuthTypes.AuthSession nativeSession = client.linkEmail(
        "player@example.com", "sufficient-long-password").get();
```

An already-registered address is never merged: linking fails with
`client.auth.email_taken`.

### Redirect authorize (Custom Tab + deep link)

```java
Open4IDAuthClient.AuthorizationRequest attempt =
        client.buildAuthorizationUrl(null).get();
// Host opens attempt.url in a Custom Tab; the redirect lands on the
// registered URI, and the host forwards the full callback URL:
AuthTypes.AuthSession session = client.handleCallback(callbackUrl).get();
```

State/nonce/PKCE are bound to the remembered transaction; a mismatch
or a provider error throws `client.auth.invalid_state` /
`client.auth.access_denied`.

### Social providers

Google needs a host-side seam (see below); GitHub, Facebook, and Apple
are implemented by the base SDK — no host subclass needed.

```java
// Google (Play Services server auth code — not a Credential Manager ID token)
// Needs play-services-auth in the host app + a host SocialAuthClient that
// overrides getGoogleAuthorizationCode with GoogleSignInClient.
AuthTypes.AuthSession session = client.signInWithGoogle(activity).get();

// GitHub (Custom Tab opened by the SDK; set the host Activity first)
client.setHostActivity(activity); // or new Open4IDAuthClient(activity)
AuthTypes.AuthSession github = client.signInWithGitHub(null).get();

// Facebook (Custom Tab at the Facebook OAuth dialog; same Portal-first shape)
AuthTypes.AuthSession facebook = client.signInWithFacebook(null).get();

// Apple (Custom Tab opened by the SDK; same Portal-first shape)
AuthTypes.AuthSession apple = client.signInWithApple(null).get();
```

`signInWithGoogle(activity)` needs a foreground `Activity` (account
chooser / consent UI) and waits up to `GOOGLE_TIMEOUT_MS` (120s,
mirroring the JS popup budget). It requests a **server auth code** via
Play Services `AuthorizationClient` — not a Credential Manager ID
token — because Core API exchanges an authorization code with the
Vault-backed secret, exactly like the web GIS popup code; the
`serverClientId` is the application's public (Web) client ID from
bootstrap. Native-issued codes are exchanged with the validated web
origin as `redirect_uri` first, then an empty-`redirect_uri` fallback,
before failing closed (verified live on device 2026-10-12). A closed
or denied flow throws `client.auth.popup_closed` or
`client.auth.access_denied`; a Console misconfiguration (wrong package
name / SHA-1, Android client in a different project than the Web ID)
throws `client.auth.provider_unavailable`. A host without
`play-services-auth` gets `client.auth.provider_unavailable`, never a
linkage error.

GitHub, Facebook, and Apple open a Custom Tab in the host `Activity` and
await the `?code&state` deep link fired back by the Portal bridge. Set
the host `Activity` before signing in — `new Open4IDAuthClient(activity)`
or `setHostActivity(activity)` — then forward the deep-link intent URL to
`Open4IDAuthClient.completeDeepLink(uri)` from `Activity.onNewIntent`
(return `true` when it was a social callback). Without a host Activity,
all three flows throw `client.auth.popup_blocked`. The provider readiness
gate (`client.auth.provider_unavailable`), the closed/denied mapping
(`popup_closed` / `access_denied`), and the 120s user-interaction budget
mirror the Google flow. Facebook signs in at the Facebook OAuth dialog
(pinned Graph API version, `scope=email`, the same permission set as the
JS popup flow) and posts the short-lived code plus the exact callback URI
to `POST /client/player_facebook_authorize`, which the server exchanges
with the Vault-backed App Secret.

The interactive Google picker (`GoogleSignInClient.getSignInIntent()`) is
left to the host because it requires `com.google.android.gms` — see
`sdks/android/sample/.../SocialAuthClient.java` for the reference
override, or the `android-sdk-minimal-ui` sample's `SocialAuthClient`.

Derive the exact URIs to register at each provider console:

```java
// GitHub OAuth App callback URI (default: redirectUri origin + /github-callback)
String githubCallback = Open4IDAuthClient.deriveGitHubCallbackUri(
        "com.example.game://open4id/callback", null);

// Facebook Valid OAuth Redirect URI (default: redirectUri origin + /facebook-callback)
String facebookCallback = Open4IDAuthClient.deriveFacebookCallbackUri(
        "com.example.game://open4id/callback", null);

// Apple Services ID Return URL (default: redirectUri origin)
String appleReturn = Open4IDAuthClient.deriveAppleReturnUri(
        "https://game.example.com/open4id/callback", null);

// Google authorized JavaScript origin from a registered redirect URI
String webOrigin = Open4IDAuthClient.deriveWebOrigin("https://game.example.com/open4id/callback");
```

## Sessions

### One-call launch (`start`)

`start()` (0.8.0, iOS parity) is the whole launch in one future: bootstrap →
wait for the once-per-process launch restore (paid state settled) → return
the stored session, or sign in as a guest when nothing was stored and the
application allows guest login. Concurrent callers share one sequence, and a
failed call (offline first launch) can be retried.

```java
AuthTypes.AuthSession session = client.start().get();
if (client.isGuestSession()) { /* anonymous player; offer linkEmail later */ }
else { /* signed-in player */ }
```

A stored session is never replaced by a guest. Create
`playBillingPayments(activity)` before `start()` when you want the launch
restore to submit store proofs (the Play store needs an Activity, so unlike
iOS the client cannot create the built-in store itself). Guest login disabled
with nothing stored fails with `client.auth.not_authenticated` — the
publisher's sign-in requirement.

### Refresh

Access tokens live 15 minutes; the SDK never refreshes by itself.
Prefer `ensureSession(minValidityMs)` (Keycloak-style `updateToken`):
it returns the session untouched when it outlives the window and
rotates once via `refresh()` otherwise (default window 30s, matching
`isAuthenticated()`). Call it before important calls or periodically
while a long game session is open. Refresh-token families live 30 days
idle and 90 days absolute; every refresh rotates to a new refresh
token and the previous one dies immediately.

```java
AuthTypes.AuthSession valid = client.ensureSession(30_000).get(); // no-op when still valid
```

Concurrent `refresh()` calls are single-flight: they share one rotation
instead of racing (a race would reuse a consumed token, which the
server treats as theft, revokes the whole family, and clears the local
session). Token-establishing flows complete serialized in start order
instead of clobbering each other's session, and a late result after a
sign-out or deletion is discarded instead of resurrecting the cleared
session. Only idempotent reads retry after a network flap; single-use
exchanges (code, refresh, authorize, delete) never retry. A rejected
refresh (`invalid_grant`) always clears local state, so treat it as
signed-out and send the player back to sign-in instead of retrying.

### Persistence (on by default)

The client persists the session by default: the auto-initialized context
wires a private `SharedPreferences` store (`open4id_session`), every
established/rotated session is saved, and configuring the client picks it
back up (malformed or foreign-app snapshots are dropped). A restored
session with an expired access token is still usable: `ensureSession()`
rotates it when the refresh token is alive. The file is private app data,
so it also rides along with Android Auto Backup / device transfer — keep
`android:allowBackup="true"` (the modern default) and the same session,
guest account included, comes back after an uninstall/reinstall on most
devices. Pass `SessionStore.MemorySessionStore` to `configure` for
memory-only (tests, kiosks), or your own `SessionStore` for a different
backend.

| Store | Survives | Default? | Note |
|---|---|---|---|
| `SharedPreferencesSessionStore` (auto-wired) | Restart + reinstall (via Auto Backup) | Yes | Private `open4id_session` prefs; namespace per app when embedding several SDK apps |
| `SessionStore.MemorySessionStore` | Nothing (lost on process death) | No | Safest; re-login every launch |
| Custom `SessionStore` (`load`/`save`/`clear`) | Whatever you implement | No | Recommended: EncryptedSharedPreferences/Keystore adapter |

```java
client.configure(appId, environment, publicSdkKey, endpoint,
        redirectUri, scope, packageName,
        new EncryptedSessionStore(context, "open4id.session." + appId));
```

Adapters must never throw — storage errors are treated as cache
misses. Tokens at rest are readable by app code, so prefer the
encrypted adapter on shared devices, and always namespace the storage
name with the Application ID so apps never read each other's sessions.
Sign-out, deletion, reset, and reuse-detection wipe the stored copy
like the memory one.

### Publisher server callback (optional login receipt)

Publishers with their own backend can tick the `server_callback`
checkbox in the Authentication configuration and save a URL. After
every fresh sign-in the SDK POSTs one login receipt there:

```json
{ "token_type": "Bearer", "access_token": "oa_...", "expires_in": 900,
  "scope": "openid profile email", "user": { "sub": "...", "email": "..." } }
```

Rules: URL presence alone never triggers it (`enabled` must be
`true`); refresh rotation never re-notifies; a delivery failure never
invalidates the session (subscribe with `onServerCallbackError`, or
replay manually with `notifyServer()`, which throws
`server_callback_disabled` when the Publisher left the checkbox off).
The receipt never carries a refresh token, verifier, or code. The
Publisher backend verifies the access token with
`POST /api/server/player_verify_token` before trusting it, and owns
CORS on its own URL. Publishers without a backend ignore this
entirely: the default flow is unchanged.

```java
// Manual receipt POST (throws if disabled)
client.notifyServer().get();

// Async error listener (delivery failed, session kept)
client.onServerCallbackError(error -> {
});
```

## SDK events (one listener)

Instead of re-publishing SDK state into your own bus listener by listener,
register one event listener (0.8.0, the Android analogue of the iOS
`onEvent` block). Every state change arrives as an `Open4IDEvent` with
`kind`, `success`, `productId`, `message` (a stable error key or a short
summary — never a token or a store proof), and `entitlements`:

```java
client.setEventListener(new Open4IDAuthClient.EventListener() {
    @Override public void onEvent(Open4IDEvent event) {
        switch (event.kind) {
            case AUTH:         // sign-in/out/refresh/deletion; message names the method
            case PRODUCTS:     // bootstrap catalog ("catalog:N") and loadProducts ("store:N")
            case ENTITLEMENTS: // snapshot published (rows) or cleared (success=false)
            case PURCHASE:     // verify outcome; productId + state/key
            case RESTORE:      // "verified=N rejected=M conflict=C" (also the launch restore)
            case TRANSACTION:  break; // reserved; Play reports those via RESTORE
        }
    }
});
```

Events fire on the **main thread** by default (touch views directly); pass an
executor to `client.setCallbackExecutor(...)` to choose another thread, or
`null` to deliver on the publishing thread (headless services, tests). The
typed listeners (`onAuthStateChanged`, `entitlements().onChanged`) keep
working unchanged — the event stream is additive sugar.

## Payments and entitlements

The SDK does not sell anything: Core API re-verifies every proof with the
store before granting an entitlement, so the client claim is only a hint.
Two ways to buy, both feeding the same verification surface:

- **Built-in Play Billing store** (`client.playBillingPayments(activity)`,
  0.6.0+): the SDK owns the Play sheet, verification, and the
  acknowledge/consume lifecycle, and registers itself as the restore proof
  source — the Android analogue of the iOS SDK's `storeKitPayments()`.
- **Headless proofs** (any store client: Unity IAP, RevenueCat, your own
  billing wrapper): the host buys anywhere and forwards the Play purchase
  token with `verifyTransaction` / `verifyConsumable`. Only `google_play`
  proofs are submitted from Android; the iOS SDK owns `app_store`.

### Built-in Play Billing store

The built-in store carries the full purchase lifecycle. Hosts must declare
the `com.android.vending.BILLING` permission; the store is inert without
Play services (typed store-unavailable errors, never a crash).

```java
// One call per client: creates the store and registers its proof source,
// so payments().restore() becomes self-sufficient (restart + reinstall).
Open4IDPlayBillingStore store = client.playBillingPayments(activity);

// Shop: catalog rows for google_play resolved with Play prices. Rows Play
// cannot resolve come back with isResolvable() == false (disable the Buy
// button); a store error (network, Play unreachable) fails the load.
store.loadProducts().whenComplete((rows, error) -> {
    for (Open4IDPlayBillingStore.PlayStoreProduct row : rows) {
        // row.product (catalog entry), row.formattedPrice, row.isResolvable()
    }
});

// Buy: Play sheet -> server verification -> acknowledge (or consume for a
// consumable) after the server accepted the proof. Branch on the outcome.
// The store preflights before opening the sheet: with no session and guest
// login disabled it throws client.auth.not_authenticated instead of
// charging a player whose verify would be rejected. Gate your Buy buttons
// on client.canPurchase() / client.requiresSignIn().
store.purchase("sku_premium").whenComplete((outcome, error) -> {
    if (error != null) { /* 160003 store: retry; 140006: sticky owner */ return; }
    if (outcome.isVerified()) {
        // one_time/subscription: grant from outcome.entitlements
        // consumable: grant from outcome.receipt (transactionRef, quantity)
    } else if (outcome.isPending()) {
        // ask-to-buy: nothing verified yet; a later restore picks it up
    } // else cancelled
});

// Restore: ledger recompute + the purchases Play reports for this store
// account. After the server accepted every proof, purchases restored from
// another device are acknowledged/consumed here too.
store.restore().whenComplete((result, error) -> {
    // result.entitlements, result.verified, result.rejected,
    // result.conflict, result.hasConflict(), result.proofsUnavailable
});
```

**How verification works, and what restore cannot recover.** Core API
never trusts the client's claim: for every proof it calls the Play
Developer API (`purchases.products.get` for one-time/consumables,
`purchases.subscriptionsv2.get` for subscriptions) with your
application's service account and re-reads the authoritative state from
Google before recording anything. Ownership is single-owner and only
moves away from an anonymous guest: a proof for a purchase bound to
another guest transfers it to the current player (the previous owner's
rows are dropped), while a purchase owned by a player with an
email/social identity is sticky and counts as `conflict`. Restore needs
connectivity (the store collection via `queryPurchasesAsync` is local,
the verification is server-side), and it recovers what Play still
reports — a reinstall with a lost session therefore recovers every
non-consumable purchase and subscription, but not consumables already
consumed on the ledger (their delivery record stays bound to the previous
guest; re-deliver those from your persisted `transactionRef` records
against `payments().purchases()`), and not purchases owned by a
credentialed account.

### Subscription state per shop row

`subscriptionState` (0.8.0, iOS parity) turns the snapshot plus catalog
into the five-state answer a boolean cannot give — billing retry is not
"expired", and "no data yet" is not "not purchased":

```java
Open4IDSubscriptionState state = client.subscriptionState("sku_premium");
// ACTIVE  — a usable active row exists
// GRACE   — billing retry (the server owns the window end)
// EXPIRED — a row exists but is past its expiry (or the expired state)
// NONE    — snapshot known, nothing usable for this product
// UNKNOWN — no snapshot yet, product unknown to both catalogs, or a
//           consumable (no entitlement keys). Show as "checking", never locked.
```

It reads the live bootstrap catalog and, when the product is missing
there, the catalog captured by the built-in store's last `loadProducts()`.
No network call. For a whole shop, iterate `payments().products().products`
and call it per row.

### Headless proofs (bring your own store client)

The game buys through Unity IAP or the native
Play Billing client, then forwards the Play purchase token here.

```java
// Catalog comes from the bootstrap snapshot (no network call).
PaymentTypes.PaymentCatalog catalog = client.payments().products();
if (catalog.enabled) {
    for (PaymentTypes.PaymentProduct product
            : catalog.forPlatform("google_play")) {
        // product.productId / product.type / product.entitlements
    }
}

// After a successful Play Billing purchase, forward its purchase token.
PaymentTypes.TransactionProof proof = PaymentTypes.TransactionProof
        .googlePlay("sku_premium", purchaseTokenFromBillingClient);
client.payments().verifyTransaction(proof).whenComplete((rows, error) -> {
    if (error != null) { /* typed error, see below */ return; }
    // rows: the player's recomputed entitlements
});

// Restore/recompute from the server ledger. Proofs are optional and
// additive; an invalid proof counts as `rejected`, a proof owned by an
// anonymous player transfers the purchase to this one, and a proof owned
// by a credentialed account counts as `conflict`. The SDK already runs one
// restore on its own — once per process, after the first bootstrap that
// advertises a payments catalog (see payments().setAutoRestoreOnLaunch) —
// so a purchase made in a previous run is active again after relaunch
// without host code. An explicit restore stays useful behind a "Restore
// purchases" button.
client.payments().restore().whenComplete((result, error) -> {
    // result.entitlements, result.verified, result.rejected, result.conflict
    // result.hasConflict(), result.proofsUnavailable
});

// Optional but recommended for a custom store client: one registration
// makes restore() self-sufficient at app start and behind "Restore
// purchases" (the built-in store registers its source automatically).
// The source is the billing client the host already owns.
client.payments().setProofSource(() -> {
    List<PaymentTypes.TransactionProof> proofs = new ArrayList<>();
    // PlayBillingClient.queryPurchasesAsync(INAPP + SUBS): one proof per
    // owned purchase, e.g. TransactionProof.googlePlay(productId, token)
    return CompletableFuture.completedFuture(proofs);
});

// Cheap checks against the cached snapshot.
client.entitlements().refresh().whenComplete((snapshot, error) -> { });
boolean premium = client.entitlements().has("premium"); // active or grace
List<PaymentTypes.Entitlement> all = client.entitlements().list();
client.entitlements().onChanged(snapshot -> { /* verify, restore, refresh,
                                                 sign-in, sign-out */ });
```

### Google Play consumables

A `consumable` catalog entry is Play-only and grants no entitlement: Open4ID
does not own item balances. The game grants items itself from the receipt
returned by `verifyConsumable`, keyed by a stable `transaction_ref`.

```java
client.payments().verifyConsumable(
        PaymentTypes.TransactionProof.googlePlay("coins_100", purchaseToken))
    .whenComplete((receipt, error) -> {
        if (error != null) { /* 160003 is transient: retry later */ return; }
        switch (receipt.state) {
            case PaymentTypes.ConsumableReceipt.DELIVERED:
                // Grant receipt.quantity items for receipt.transactionRef
                // (idempotent by that reference).
                break;
            case PaymentTypes.ConsumableReceipt.ALREADY_DELIVERED:
                break; // replay: the items were already granted
            case PaymentTypes.ConsumableReceipt.PENDING:
                break; // consume handshake unfinished: retry verify, grant nothing
            case PaymentTypes.ConsumableReceipt.REFUNDED:
                // The whole purchase was reversed: claw back this reference.
                break;
        }
    });

// Reconciliation read: webhook delivery is not implemented, so this is how
// the game learns about refunds, including partial ones.
client.payments().purchases().whenComplete((rows, error) -> {
    for (PaymentTypes.PaymentPurchase purchase : rows) {
        // Grant purchase.quantity for purchase.transactionRef, then reverse
        // purchase.refundedQuantity. Both steps are idempotent per reference.
    }
});
```

`verifyConsumable` re-verifies the purchase with Google, writes the ledger
row, then consumes the Play purchase server-side. Consuming both acknowledges
the purchase (so it is not auto-refunded after three days) and makes the
product buyable again. The host may call
`BillingClient.consumeAsync(...)` again as a harmless no-op; Play answers
"already consumed". Never acknowledge a consumable instead of consuming it.

Rules and shapes:

- **Proofs**: `TransactionProof.googlePlay(productId, purchaseToken)`.
  Purchase tokens are sent once, never logged, and never persisted;
  `toString()` redacts them.
- **States**: `active` and `grace` are usable; `expired` and `revoked`
  stay in `list()` so the host can show history. `expires_at` is absent
  for non-expiring one-time grants.
- **Snapshot lifecycle**: the cached snapshot is cleared when the
  signed-in player changes or signs out, so grants never leak across
  sessions. Same-session transitions keep it. `current()` is `null` on a
  cold start until the first read, so treat "no snapshot" as unknown, not
  as "not purchased".
- **Restore bounds**: at most 20 proofs per call; the request body is
  capped at 64 KB server-side.
- **Catalog fail-closed**: before bootstrap, when the application has no
  store enabled, or when the block is missing/malformed,
  `products().enabled` is `false` with an empty list. A consumable is
  Play-only and must have an empty `entitlements` list; `isConsumable()`
  identifies it.
- **Consumable receipts**: `transactionRef` (`ptx_...`) is stable for the
  purchase and is never the store token, so it is safe to persist as the
  game's grant/idempotency key. `quantity` and `refundedQuantity` follow the
  ledger, `consumedAt` is set once Play accepts the consume, and
  `consumeState` is `pending`, `consumed`, or `failed` in `purchases()`.
  Consumables never appear in `entitlements()`, and neither
  `verifyConsumable` nor a `verifyTransaction` response carrying a `delivery`
  block publishes an entitlement snapshot.
- **Restore records, it does not deliver**: `restore()` verifies and records a
  consumable proof (so it counts as `verified`) but returns no receipt and does
  not consume it server-side. Finish collected consumables with the Play client
  after a successful restore (the sample consumes every collected consumable),
  or call `verifyConsumable` for the receipt, and grant only from
  `purchases()` keyed by `transactionRef`.
- **Refunds**: `purchases()` is newest first and leaves out the store token
  and player id. A `partially_refunded` row keeps `status`
  `partially_refunded` until the whole quantity is reversed, then becomes
  `refunded`; reverse only the delta between `refundedQuantity` and what the
  game already clawed back.
- **Retries**: `160003` (`client.payment.store_unavailable`) is
  transient — retry later. `140006`
  (`client.payment.owned_by_another_account`) means the purchase is bound to
  another account: ask the player to sign in as that account, do not retry.
- **Guest purchases and store-account restore**: `verifyTransaction` and
  `restore` sign in as a guest first when there is no session and the
  bootstrap snapshot allows guest login; with guest login disabled they fail
  with `client.auth.not_authenticated`. A store-verified proof moves the
  purchase only while the current owner is still an anonymous guest, so a
  reinstall or a fresh guest recovers what it paid for. Once an owner has an
  email or social identity the purchase is sticky: verify fails with
  `SdkConflictError` and restore reports `result.conflict`, so a shared Play
  account can never take a grant away from the paying account.
- **App lifecycle and restore**: the device stores the session, never the
  grants. At app start, `client.payments().restore()` recomputes the ledger
  for the restored session and re-submits its store proofs (idempotent); after
  a reinstall or sign-out it signs in as a guest and the proof moves an
  anonymous binding to the new player. Register
  `client.payments().setProofSource(...)` once with the billing client you
  already own (Play `queryPurchasesAsync` over `INAPP` and `SUBS`) and the one
  call covers both cases; without a source, restore stays a ledger-only
  recompute. `result.proofsUnavailable` is `true` when the store could not be
  read (error, throw, or the 10 s bound), so the UI can offer a retry instead
  of hiding a paid feature. Use `entitlements().refresh()` when only the
  ledger matters, and explicit `restore(proofs)` when the host collects
  proofs itself — neither consults the source.
- **Token freshness**: `verifyTransaction`, `restore`, and
  `entitlements().refresh()` rotate a stale access token through
  `ensureSession` before the call, so a session older than the 900 s token
  lifetime (a store sheet left open, an idle game) still works instead of
  answering `auth.token.invalid`.

## Error Keys

All errors use stable `client.auth.*` / `system.*` keys (shared with the JS SDK);
player-action failures keep their server `client.player.*` keys. Each
exception is a `RuntimeException` subclass with `errorCode` and
`fieldErrors` (for validation), so `CompletableFuture` chains never
need checked-exception plumbing:

| Key | HTTP | Cause |
|-----|------|-------|
| `client.auth.invalid_request` | 400 | Bad input (missing fields, invalid format) |
| `client.auth.provider_unavailable` | 400 | Provider disabled or misconfigured |
| `client.auth.invalid_state` | 400 | PKCE/state mismatch |
| `client.auth.access_denied` | 401 | User denied consent |
| `client.auth.popup_blocked` | 400 | Custom Tab blocked |
| `client.auth.popup_closed` | 400 | User closed chooser / Custom Tab |
| `client.auth.popup_timeout` | 408 | Chooser / Custom Tab timed out |
| `client.auth.not_bootstrapped` | 400 | `bootstrap()` not called |
| `client.auth.not_authenticated` | 401 | No valid session |
| `client.auth.invalid_token` | 401 | Access token rejected |
| `client.auth.invalid_grant` | 400 | Refresh token spent/revoked |
| `client.player.invalid_credentials` | 401 | Wrong email/password |
| `client.player.email_not_verified` | 403 | Grace period expired |
| `client.player.verification_invalid` | 400 | Bad verification token |
| `client.player.reset_invalid` | 400 | Bad reset token |
| `client.player.email_taken` | 409 | Address already registered |
| `client.player.link_unavailable` | 409 | Guest linking not allowed |
| `client.auth.server_callback_disabled` | 400 | Publisher checkbox off |
| `client.auth.server_callback_failed` | 5xx | Receipt delivery failed |
| `client.payment.invalid_request` | 400 | Malformed or missing store proof |
| `client.payment.transaction_not_found` | 404 | Unknown transaction or entitlement (`130004`) |
| `client.payment.owned_by_another_account` | 409 | Purchase bound to another credentialed account (`140006`); restore reports it as `conflict` instead |
| `client.payment.store_unavailable` | 503 | Store verification temporarily unavailable (`160003`) |
| `system.service.down` | 503 | Dependency unreachable |
| `system.internal_error` | 500 | Unexpected failure |

## Testing

Run from `sdks/android/` (no Gradle wrapper is checked in; use the
system `gradle`):

```bash
gradle :open4id-sdk:testDebugUnitTest
```

- 123 JVM unit tests covering validation, error mapping, PKCE, flows, callback URI derivation, payments (catalog including consumables, proof wire shape, consumable receipts and ledger purchases, restore bounds/conflict counts, the registered proof source for stored-session and reinstall restores, guest auto sign-in, snapshot lifecycle, error mapping), and the login-view strings/layout/brand/error mapping (2 live-gated tests skipped without device credentials)
- Mocked `HttpTransport` for deterministic wire testing (the view itself needs a device; its logic is covered by the pure-`ui` tests)
- Instrumented tests (`ManualLoginLiveTest`) skipped by default; run with device/emulator and live Core API:

```bash
OPEN4ID_LIVE_APP_ID=app_... OPEN4ID_LIVE_ENVIRONMENT=development \
OPEN4ID_LIVE_PUBLIC_KEY=sdk_pk_... OPEN4ID_LIVE_ENDPOINT=http://127.0.0.1:10001/api \
OPEN4ID_LIVE_REDIRECT_URI=https://app.open4id.com/open4id-callback \
OPEN4ID_LIVE_PACKAGE=com.example.game OPEN4ID_LIVE_EMAIL=player@example.com \
OPEN4ID_LIVE_PASSWORD=... \
gradle :open4id-sdk:testDebugUnitTest --tests "com.open4id.sdk.ManualLoginLiveTest"
```

## Sample App (Manual Live Test)

`sample/` is a minimal host for exercising the SDK on a real
device/emulator — required for the Google flow (account chooser UI +
Play Services cannot run on the JVM):

```bash
# From sdks/android/. Forward the dev Core API to the device loopback
# (sample default endpoint):
adb reverse tcp:10001 tcp:10001
# Device/emulator with Play Services + a signed-in Google account, then:
gradle :sample:installDebug
```

The app preloads the development App from the web example. Tap **Load
bootstrap** (expect the App display name, e.g. `Login Example`), then
**Sign in with Google** (verified live on device 2026-10-12), or tap
**Open login UI** for the SDK login view bound to the same client. The
sample speaks the native mobile contract: `platform: android` plus the
package name on every request, the custom-scheme redirect
(`com.open4id.example.android://open4id/callback`), and no `Origin`
header anywhere — the same shape production apps use.

The activity registers `client.onAuthStateChanged(...)` so the main app
can react to any auth state change (sign-in, sign-out, refresh,
deletion) from any source — headless calls or the login UI/dialog.

Google Console prerequisites for the App's client ID: provider enabled
in Authentication config, and the sample package
(`com.open4id.example.android`) plus your debug SHA-1 linked to the Web
client ID in the same project (debug key):
`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey`.
The sample overrides the SDK code fetch with the interactive
`GoogleSignInClient` intent via `SocialAuthClient`
(`sdks/android/sample/src/main/java/com/open4id/example/android/SocialAuthClient.java`),
so the account picker always appears and every sign-in returns a fresh
server auth code. GitHub, Facebook, and Apple use the base SDK's Custom
Tab seams (`Open4IDAuthClient.openGitHubCustomTab` /
`openFacebookCustomTab` / `openAppleCustomTab`) with the per-app Portal
bridges
`https://app.open4id.com/github-callback/com.open4id.example.android`,
`https://app.open4id.com/facebook-callback/com.open4id.example.android`,
and
`https://app.open4id.com/apple-callback/com.open4id.example.android`
(registered at the GitHub OAuth App, Facebook Valid OAuth Redirect URIs,
and Apple Services ID consoles, respectively). Apple's bridge accepts
`form_post` server-side and hands `?code&state` to the app deep link,
exactly like the query flow; Apple requests `scope=email` with
`response_mode=form_post` so first-time sign-in carries a verified
address.

The **Payments** screen (third panel) drives the SDK's built-in Play Billing
store (`client.playBillingPayments(activity)`): the shop rows load with Play
prices, **Buy with Play Billing** opens the real Play sheet, verifies through
Core API, and acknowledges/consumes only after the server accepted the proof.
The store picks the Play product type from the catalog: `subscription` entries
are queried as `SUBS` and bought with the first active base plan's offer
token, everything else as `INAPP`; a miss under the expected type retries the
other type, which distinguishes a wrong catalog `type` from a product that is
genuinely inactive in Play. A verified consumable logs its
`transactionRef` receipt; a verified one-time/subscription logs the
recomputed entitlements. Verification needs a signed-in player, and
purchases use a license-tester account for free test instruments. A license
tester can also test a **sideloaded** release or debug APK: Play bypasses its
"signed and uploaded to Play" check for testers, provided the package name
matches the app configured in Play and the products are active. Without that
account, a sideloaded build answers
`ITEM_UNAVAILABLE`/`SERVICE_DISCONNECTED`, so use the Play internal-testing
install for non-tester runs.

Status 2026-10-26: a license tester bought a one-time product
(`com.open4id.example.pkg_02`) and a subscription
(`com.open4id.example.pkg_03`, `1 week, prepaid`) from a sideloaded vc 8
release APK; the consumable purchase/refund pass and the Play-installed pass
are still outstanding.

The built-in store registers its proof source on creation, so **Restore
purchases** and the restore that runs when the screen opens both recompute the
ledger *and* re-submit every purchase Play reports for the account — the
reinstall case — and finish restored purchases only after Core API accepted
the proofs (consuming consumables, acknowledging everything else). The screen
logs `verified`/`rejected`/`conflict` plus `store proofs unavailable` when Play
could not be queried. **List payment purchases** reads the player's ledger
receipts (newest first) so the refund path is exercised: the sample logs
`transactionRef`, status, `quantity`, `refundedQuantity`, and `consumeState`
for each row. Every screen open logs a `payments summary` once the startup
restore finishes: the purchased product id, status, and purchase time grouped
by `one-time`, `subscription`, and `consumable`, followed by the active/grace
subscriptions with their product id and expiration time in the device
timezone. **Refresh entitlements** stays the ledger-only read.

## Signed release bundle (Google Play upload)

The `sample` module signs its release bundle from an untracked
`keystore.properties` at `sdks/android/`. When the file is missing, release
builds stay unsigned (so `assembleDebug` and CI keep working unchanged); when
present, `sample/build.gradle` loads it and applies a `release`
`signingConfig`.

`keystore.properties` keys:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Generate the upload key once (JKS, RSA 4096, long validity). This is the
**upload key** — Google Play App Signing holds the actual app signing key:

```bash
keytool -genkeypair \
  -keystore ~/.android/open4id-sample-upload.jks -storetype JKS \
  -alias open4id-sample-upload \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Open4ID Sample Upload, OU=Mobile, O=Open4ID, L=Ho Chi Minh City, ST=HCMC, C=VN" \
  -storepass '<strong-password>' -keypass '<strong-password>'

keytool -list -v -keystore ~/.android/open4id-sample-upload.jks \
  -alias open4id-sample-upload
```

Build the signed bundle, from `sdks/android/`:

```bash
gradle :sample:bundleRelease
# output: sample/build/outputs/bundle/release/sample-release.aab
```

Confirm the signer and read its fingerprints:

```bash
keytool -printcert -jarfile sample/build/outputs/bundle/release/sample-release.aab
```

### Sideloadable APK from the bundle

A license tester can buy test products from a sideloaded build, so the signed
bundle can be turned into a universal APK without a Play install. Use the
`bundletool` **all** jar (the Gradle-cached `bundletool` artifact is a library
jar with no main class):

```bash
curl -LO https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar

java -jar bundletool-all-1.18.3.jar build-apks \
  --bundle=sample/build/outputs/bundle/release/sample-release.aab \
  --output=sample/build/outputs/bundle/release/sample-release.apks \
  --mode=universal --overwrite \
  --ks=<keystore.properties storeFile> --ks-key-alias=<keyAlias> \
  --ks-pass=pass:<storePassword> --key-pass=pass:<keyPassword>

unzip -o sample/build/outputs/bundle/release/sample-release.apks universal.apk
adb install -r -d universal.apk
```

`gradle :sample:assembleRelease` produces an equivalent APK straight from
source; the bundle-extracted one is what Play would deliver to a matching
device. Both are signed with the upload key, so uninstall a Play-installed
build first (its signature is Play App Signing's). Bundle and APK outputs stay
git-ignored.

Notes:

- Bump `versionCode` (and `versionName`) in `sample/build.gradle` before every
  Play upload; a repeated `versionCode` is rejected.
- Back up the keystore **and** `keystore.properties`. Losing them means you
  cannot ship updates under the same upload key.
- Google sign-in from a Play-installed build needs the **Play App Signing**
  certificate SHA-1 (Play Console → App integrity) — not the upload key's
  SHA-1 — linked to the Android OAuth client in the Google Cloud Console.
- In-app products and subscriptions are created in Play Console and mapped in
  the Open4ID Publisher Portal `payments.products` catalog; add your account
  under Play Console → License testing so test purchases are free.
- Key material (`*.jks`, `*.keystore`, `keystore.properties`,
  `local.properties`) is git-ignored and must never be committed.

## ProGuard

No reflection used; minification safe without extra rules.

## Release (Maven Central)

Coordinates are `com.open4id:open4id-sdk:<version>`, configured in
`sdks/android/gradle.properties` (`GROUP`, `POM_ARTIFACT_ID`,
`VERSION_NAME` plus the public POM metadata). Publishing uses the
Vanniktech plugin (signed AAR plus sources and javadoc jars).

Prerequisites (one time, never committed):

- The `com.open4id` namespace registered on the Central Portal.
- A Central Portal user token (publishing token, not the login
  password): `mavenCentralUsername` / `mavenCentralPassword` in
  `~/.gradle/gradle.properties`, or `ORG_GRADLE_PROJECT_mavenCentralUsername`
  / `ORG_GRADLE_PROJECT_mavenCentralPassword` environment variables.
- A GPG signing key: export the ASCII-armored private key
  (`gpg --export-secret-keys --armor <key-id>`) and provide it as
  `ORG_GRADLE_PROJECT_signingInMemoryKey` (plus
  `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` when the key has a
  password). Key material (`.jks`, `.keystore`, `local.properties`) is
  git-ignored and must never be committed.

Release steps, from `sdks/android/`:

```bash
# 1. Bump VERSION_NAME in gradle.properties (and versionName in
#    open4id-sdk/build.gradle), add a CHANGELOG.md entry.
# 2. Full verification:
gradle :open4id-sdk:testDebugUnitTest
# 3. Dry-run the publication wiring locally (no credentials needed):
gradle :open4id-sdk:publishToMavenLocal -PsignAllPublications=false
# 4. Upload (manual close on the Central Portal afterwards):
gradle :open4id-sdk:publishToMavenCentral
# ...or upload and release in one step:
gradle :open4id-sdk:publishAndReleaseToMavenCentral
```

Published artifacts appear on Central 10–30 minutes after release.
Pre-release hygiene: the `sample/` app currently commits a Google
client-secret JSON and sets cleartext traffic — rotate those credentials
and flags before any public release (tracked in the project handoff).

## Security

- `sdk_sk_...` never in client; only `sdk_pk_...` in JSON bodies
- PKCE verifier, authorization codes, refresh tokens never logged
- Session snapshot persisted by default in private `SharedPreferences`
  (`open4id_session`, auto-wired through the init provider; memory-only
  when no app context is available); encrypted store recommended on
  shared devices
- Custom Tabs used for all external auth (no WebView)
- Play Services auth degrades to typed error when absent (never `NoClassDefFoundError`)

## License

MIT
