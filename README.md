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
| `androidx.browser:browser:1.9.0` | Custom Tabs (sole runtime exception to zero-dependency rule) |
| `com.google.android.gms:play-services-auth:21.2.0` | Google server-auth-code flow (compileOnly, optional at runtime) |

No third-party JSON, HTTP, or crypto libraries. All wire parsing uses pure Java (`java.util`, `java.security`, `java.net`).

## Installation

Published to Maven Central as `com.open4id:open4id-sdk` ([changelog](CHANGELOG.md)):

```gradle
dependencies {
    implementation 'com.open4id:open4id-sdk:0.3.2'
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
- **GitHub**: provider enabled; register the derived callback URI
  (`deriveGitHubCallbackUri`, default `<redirectUri origin>/github-callback`)
  in the GitHub OAuth App settings. Native apps call back through the
  Portal first: register one per-app bridge URL,
  `https://app.open4id.com/github-callback/<package_name>`, and pass it
  as the callback URI.
- **Apple**: provider enabled; register the derived Return URL
  (`deriveAppleReturnUri`, default the redirect-URI origin) in the
  Services ID settings. Native apps call back through the Portal first:
  register one per-app bridge URL,
  `https://app.open4id.com/apple-callback/<package_name>`, and pass it
  as the Return URL.

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
// bootstrap.login.* — provider readiness (google.clientId, github.clientId, apple.clientId)
// bootstrap.serverCallback — publisher login receipt callback (opt-in)
```

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

Email/password, sign-up, guest, Google, GitHub, and Apple all render
inside — no provider code on your side. Google needs the view hosted
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

- **Remember login across restarts:** pass a `SessionStore` to
  `configure` (see Sessions).
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
carry the inline brand marks (Google, GitHub, Apple vector drawables —
no host assets needed). The view shows only the login methods the
Publisher enabled for the environment (normal email/password, guest,
Google, GitHub, Apple) — toggled in the Publisher Portal under App
Settings → SDK & API keys → Login methods — and hides the rest
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
(`<portalOrigin>/github-callback/<package>` and
`<portalOrigin>/apple-callback/<package>`), which must be registered at
each provider console. The Portal origin defaults to production
(`https://app.open4id.com`); development builds point it at the local
Portal instead:

```java
// Explicit per-call/per-view overrides still win when you host your own
// callback pages (e.g. an http(s) redirect keeps its origin-derived default):
login.setGitHubCallbackUri("https://game.example.com/github-callback");
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
(Custom Tab plus the game/SaaS callback page); the Apple button signs
in through `signInWithApple` (Custom Tab, no navigation). Unavailable
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

Google works out of the box; GitHub and Apple need the host UI seam
(see below).

```java
// Google (Play Services server auth code — not a Credential Manager ID token)
AuthTypes.AuthSession session = client.signInWithGoogle(activity).get();

// GitHub (Custom Tab opened by the host seam; null resolves to the
// per-app Portal bridge derived from the portal origin + package name)
AuthTypes.AuthSession github = client.signInWithGitHub(null).get();

// Apple (Custom Tab opened by the host seam; same Portal-first shape)
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

GitHub and Apple have no platform API (and WebView is forbidden for
auth), so the base SDK leaves the browser seam to the host: subclass
and override `openGitHubCustomTab` / `openAppleCustomTab` (open the
provider URL in a Custom Tab, await your deep link, return the code —
the `sample/` app's `SocialAuthClient` is the reference implementation).
The provider readiness gate (`client.auth.provider_unavailable`), the
closed/denied mapping (`popup_closed` / `access_denied`), and the
120s user-interaction budget mirror the Google flow.

Derive the exact URIs to register at each provider console:

```java
// GitHub OAuth App callback URI (default: redirectUri origin + /github-callback)
String githubCallback = Open4IDAuthClient.deriveGitHubCallbackUri(
        "com.example.game://open4id/callback", null);

// Apple Services ID Return URL (default: redirectUri origin)
String appleReturn = Open4IDAuthClient.deriveAppleReturnUri(
        "https://game.example.com/open4id/callback", null);

// Google authorized JavaScript origin from a registered redirect URI
String webOrigin = Open4IDAuthClient.deriveWebOrigin("https://game.example.com/open4id/callback");
```

## Sessions

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

### Persistence (opt-in session stores)

The client is memory-only by default and forgets the session on process
death — it persists nothing unless you pass a `SessionStore` to
`configure`. Every established/rotated session is saved, and
configuring the client picks it back up (malformed or foreign-app
snapshots are dropped). A restored session with an expired access token
is still usable: `ensureSession()` rotates it when the refresh token
is alive.

| Store | Survives | Default? | Note |
|---|---|---|---|
| `SessionStore.MemorySessionStore` | Nothing (lost on process death) | Yes | Safest; re-login every launch |
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
| `system.service.down` | 503 | Dependency unreachable |
| `system.internal_error` | 500 | Unexpected failure |

## Testing

Run from `sdks/android/` (no Gradle wrapper is checked in; use the
system `gradle`):

```bash
gradle :open4id-sdk:testDebugUnitTest
```

- 84 JVM unit tests covering validation, error mapping, PKCE, flows, callback URI derivation, and the login-view strings/layout/brand/error mapping (2 live-gated tests skipped without device credentials)
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
server auth code. GitHub uses the per-app
Portal bridge `https://app.open4id.com/github-callback/com.open4id.example.android`
(registered in the GitHub OAuth App settings). The sample implements the
Apple Custom Tab seam with the email scope
(`response_mode=form_post`, so the share/hide-email choice appears and
first-time sign-in carries a verified address). The per-app Portal bridge
accepts Apple's form POST server-side and hands `?code&state` to the app
deep link, exactly like the query flow. Apple uses the per-app Portal
bridge `https://app.open4id.com/apple-callback/com.open4id.example.android`
(registered in the Services ID Return URLs — no Apple-side change is
needed for the scope; the same Return URL receives both modes).

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
