# Changelog

All notable changes to the Open4ID Android SDK are documented here.
Coordinates: `com.open4id:open4id-sdk` on Maven Central.

## 0.5.0

- **Google Play consumables**: catalog entries of type `consumable` are
  Play-only with an empty entitlement list, and `PaymentProduct.isConsumable()`
  identifies them. `verifyConsumable(proof)` re-verifies the purchase, has Core
  API consume it server-side (which acknowledges it and stops the three-day
  auto-refund), and returns a `ConsumableReceipt` with a stable
  `transactionRef` (`ptx_...`, never the purchase token), `quantity`,
  `refundedQuantity`, and a delivery state (`delivered`, `already_delivered`,
  `pending`, `refunded`). Open4ID does not own item balances: the game grants
  items keyed by `transactionRef` and reverses them on a refund.
  `purchases()` reads the player's ledger receipts for reconciliation, because
  webhook delivery is not implemented; it supports partial refunds via
  `refundedQuantity` and `PaymentPurchase.isPartiallyRefunded()`. A consumable
  never publishes an entitlement snapshot.
- **Headless payments and entitlements**: new `Open4IDPayments`
  (`payments()`), `Open4IDEntitlements` (`entitlements()`), and the
  `PaymentTypes` value types. The SDK never opens a purchase sheet and
  adds no store dependency: the game buys through Unity IAP
  or the native Play Billing client and forwards the Play purchase token
  with `verifyTransaction(TransactionProof.googlePlay(...))`. Core API
  re-verifies the proof with Google before granting anything; the client
  assertion is only a hint.
- **Entitlement snapshot**: `entitlements().refresh()` reads the
  server-derived rows, `has(key)` is true only for `active`/`grace`, and
  `list()` keeps expired/revoked history. The snapshot is cleared when
  the signed-in player changes or signs out.
- **Catalog from bootstrap**: `payments().products()` returns the public
  `payments` block (product id, platform, type, entitlement keys) without
  a network call. A missing or malformed block yields a disabled catalog,
  never `null`.
- **Restore**: `payments().restore()` recomputes from the ledger;
  `restore(proofs)` accepts up to 20 optional proofs and reports
  `verified`/`rejected` counts plus `conflict` (valid proof, purchase owned
  by another credentialed account) with `hasConflict()`.
- **Restore proof source**: `payments().setProofSource(source)` registers the
  Play Billing client (or any store client) the host already owns, so
  `restore()` also submits the purchases the store reports for the account.
  One call then recovers a purchase both after a restart (stored session) and
  after a reinstall (fresh guest, same Play account), and the SDK keeps its
  zero store dependency. A source error, synchronous throw, or the 10 s bound
  degrades to a ledger-only restore and sets
  `RestoreResult.proofsUnavailable`; explicit `restore(proofs)` never consults
  the source, and `entitlements().refresh()` stays the ledger-only read.
- **Guest purchases**: `verifyTransaction` and `restore` sign in as a guest
  automatically when there is no session and the application allows guest
  login, so an anonymous purchase works without host code. With guest login
  disabled the call fails with `client.auth.not_authenticated`.
- **Store-account restore**: a store-verified proof moves the purchase only
  while the current owner is still an anonymous guest, so a reinstall or a
  fresh guest on the same Play account restores what it paid for. Once the
  owner has an email or social identity the purchase is sticky: verify fails
  with `SdkConflictError` and restore reports `conflict`, so a shared Play
  account cannot take a grant away from the paying account.
- **Proof hygiene**: `TransactionProof.toString()` redacts the purchase
  token, and the SDK never logs or persists proof material.
- **Token freshness**: every payment call (`verifyTransaction`,
  `verifyConsumable`, `purchases`, `restore`, `entitlements().refresh()`)
  rotates a stale access token through `ensureSession` first, so a session
  older than the 900 s token lifetime no longer fails with
  `auth.token.invalid` — the case for a store sheet left open past the
  token's life.
- **New typed errors**: `SdkStoreUnavailableError` (`160003`, retryable, still
  matches `SdkUnavailableError`), `SdkNotFoundError` (`130004`),
  `SdkConflictError` (`140006`, `client.payment.owned_by_another_account`),
  plus field-level validation errors for proof submissions.

## 0.4.1

- **GitHub provider mark**: `ic_open4id_github.xml` now draws the web app's
  canonical octocat (`apps/app.open4id.com/public/icons/github.svg`, viewBox
  98x96, the same artwork the iOS `ProviderMark` draws). The previous
  16-unit glyph closed its outline with a straight chord (a corrupted
  Bootstrap Icons variant), so the octocat rendered with a flat right edge.

## 0.4.0

- **Facebook Sign-In (Custom Tab)**: new `signInWithFacebook(callbackUri)`
  on the headless client and the `Open4IDAuthView` Facebook button
  (rendered between GitHub and Apple, the same order as the JS element).
  The SDK opens a Custom Tab at the Facebook OAuth dialog (pinned Graph
  API `v26.0`, `scope=email`) bound to the game callback URI — default
  the per-app Portal bridge
  `<portalOrigin>/facebook-callback/<package>` for custom-scheme
  redirects, overridable via `setFacebookCallbackUri` /
  `Open4IDLoginDialog.setFacebookCallbackUri` — then posts
  `facebook_code` + `facebook_redirect_uri` to
  `POST /client/player_facebook_authorize` exactly like the GitHub flow;
  the server exchanges the code with the Vault-backed App Secret and
  answers the same single-use PKCE-bound authorization code. Requires
  the Facebook provider enabled in bootstrap (`login.facebook`, parsed
  into `LoginReadiness.facebook`); the Portal bridge deep link completes
  through `completeDeepLink(Uri)` like GitHub/Apple. New exports:
  `deriveFacebookCallbackUri()` (Publisher preview derivation) and
  `defaultFacebookCallbackUri()`. Provider-unavailable and cancellation
  messages localized (English/Vietnamese).

## 0.3.3

- GitHub / Apple Custom Tab flows moved into `Open4IDAuthClient`
  (`openGitHubCustomTab` / `openAppleCustomTab` plus
  `completeDeepLink(Uri)`); hosts set the host `Activity` via
  `Open4IDAuthClient(Activity)` or `setHostActivity(Activity)` and forward
  deep links from `Activity.onNewIntent`. Sample/Flutter `SocialAuthClient`
  subclasses now only override the Google `getGoogleAuthorizationCode` seam
  (interactive `GoogleSignInIntent` picker; still requires
  `play-services-auth`).

## 0.3.2

- 7-argument `configure()` overload without the trailing `SessionStore`
  (defaults to the persisted snapshot store, memory-only without an app
  context). The 8-argument form is unchanged.
- `Open4IDLoginDialog.show(activity, client, bootstrap, locale,
  listener)` one-call bind-and-show returning the dialog, plus an
  explicit `close()` (same as `dismiss()`; sign-in still auto-closes
  unless disabled).

## 0.3.1

- Forgot-password email format check: `requestPasswordReset()` rejects
  malformed addresses locally with `SdkValidationError`
  (`fieldErrors.email = invalid`, same shape rule as Core API) without
  any network call; unknown addresses still get the generic success so
  responses never reveal registration state. Failures render a dedicated
  `invalid_email` message (English/Vietnamese) in `Open4IDAuthView`.
- First Maven Central release: `com.open4id:open4id-sdk:0.3.1`
  (signed AAR plus sources and javadoc jars).
