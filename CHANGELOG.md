# Changelog

All notable changes to the Open4ID Android SDK are documented here.
Coordinates: `com.open4id:open4id-sdk` on Maven Central.

## 0.8.0 (2026-11-12)

- **One event surface (`onEvent` parity).** `client.setEventListener`
  delivers one `Open4IDEvent` for every state change a host used to
  re-publish itself: `AUTH` (sign-in/sign-out/refresh/deletion, with the
  sign-in method in `message`), `PRODUCTS` (bootstrap catalog
  `catalog:N` and `loadProducts` `store:N`), `ENTITLEMENTS` (publish and
  the cleared signed-out snapshot), `PURCHASE` (verify outcomes,
  including a non-grantable consumable state), and `RESTORE`
  (verified/rejected/conflict summary — including the silent launch
  restore). The `TRANSACTION` kind exists so one branch handles both
  platforms: Play reports store-initiated completions through queries,
  which surface as `RESTORE`. Events fire on the main thread by default
  (`setCallbackExecutor` chooses another; `null` = publishing thread);
  the typed listeners keep working unchanged.
- **`subscriptionState(productId)` (iOS parity).** `Open4IDEntitlements`
  and a client sugar return `ACTIVE`, `GRACE`, `EXPIRED`, `NONE`, or
  `UNKNOWN` for one catalog product, reading the live bootstrap catalog
  and the catalog captured by the built-in store's last `loadProducts()`.
  `UNKNOWN` means "checking" — no snapshot yet, a product unknown to both
  catalogs, or a consumable without entitlement keys.
- **`start()` (iOS parity).** One-call launch: bootstrap, wait for the
  once-per-process launch restore (paid state settled), then return the
  stored session or sign in as a guest. Single-flight (concurrent
  callers share one sequence), retryable after a failure, and never
  replaces a stored session with a guest. Guest login disabled with
  nothing stored fails with `client.auth.not_authenticated`.
- **Guest login split from the guest button.** `guest_enabled` stays the
  server capability (guest identities, guest-bound payments); the new
  `guest_button_visible` bootstrap flag only decides whether the built-in
  dialogs render "Continue as guest". It defaults to `guest_enabled` when
  absent, so older bootstraps behave exactly as before. Anonymous checkout is
  `guest_enabled = true` with `guest_button_visible = false`. Exposed as
  `AuthTypes.LoginReadiness.guestButtonVisible` and
  `AuthLayout.Model.guestButtonVisible`.
- **Purchase preflight.** `Open4IDPlayBillingStore.purchase` now checks for a
  usable identity before opening the Play sheet: with no session and guest
  login disabled it throws `client.auth.not_authenticated` instead of
  charging a player whose verify would be rejected. Hosts get
  `Open4IDAuthClient.canPurchase()` and `requiresSignIn()` for shop gating
  (fail-closed before the first bootstrap). The guest identity is still
  minted only after the player confirms the sheet.

## 0.7.0 (2026-11-12)

- **Time-aware entitlement usability (iOS parity).**
  `Entitlement.isUsable` now treats a cached `active` row past its
  `expiresAt` as unusable (the server recomputes on read, but between
  refreshes the client owns the check), while a `grace` row keeps its
  original (past) expiry by design — the grace-window end is server-side
  and arrives as the next state on a later read. Parsed dates
  (`expiresAtDate()`/`grantedAtDate()`/`updatedAtDate()`,
  `remainingSeconds()`), `has(key, atMillis)`, snapshot and client
  `hasAny(List)`, `isPurchased(productId)` (catalog row → entitlement
  keys), and `entitlement(key)` round out the surface.
- **Launch restore reconciles with the store.** When a proof source is
  registered, the stored-session hydration now also submits the store's
  current receipts instead of only refreshing the ledger: renewals
  recorded only by the store, and purchases made before persistence
  existed, repair themselves at launch without a Restore tap.
- **Session survives reinstall via Auto Backup.** The default
  `SharedPreferences` store (`open4id_session`) is private app data and
  rides along with Android Auto Backup / device transfer when the host
  keeps `android:allowBackup="true"` (the modern default), so the same
  guest account returns after an uninstall/reinstall on most devices.
  When a backup does not restore, the launch restore (below) recovers
  purchases from the store account. The README persistence section was
  corrected to match the shipped default (persisted by default,
  memory-only only outside Android).
- **Paid state survives a restart (launch restore).** After the first
  `bootstrap()` that advertises a payments catalog, the SDK rehydrates paid
  state once per process: a stored session refreshes its ledger rows, and a
  missing session signs in as a guest (when guest login is enabled) and
  restores through the registered proof source — so a package bought in a
  previous run is active again after relaunch without host code. The result
  arrives through `entitlements().onChanged` like every other publish; a
  failure stays silent and leaves paid state "unknown" (the host's explicit
  `restore()` / `refresh()` still work unchanged). Opt out with
  `payments().setAutoRestoreOnLaunch(false)` set before the first
  `bootstrap()`. Creating `playBillingPayments(...)` after the bootstrap
  runs one idempotent retry with the built-in proof source, so a store set
  up late still repairs a session that did not survive the restart.

## 0.6.0

- **Built-in Play Billing store**: `client.playBillingPayments(activity)`
  returns a store that owns the whole purchase lifecycle, the Android
  analogue of the iOS SDK's `storeKitPayments()`:
  `loadProducts()` resolves the `google_play` catalog rows with Play prices
  (rows Play cannot resolve come back flagged, a store *error* fails the
  load), `purchase(product|productId)` opens the Play sheet and verifies
  through Core API — `verifyTransaction` for one_time/subscription,
  `verifyConsumable` for a consumable — then acknowledges (or consumes)
  only after the server accepted the proof. The outcome is one value to
  branch on: `verified` (grant from `entitlements`/`receipt`), `pending`
  (ask-to-buy; a later restore picks it up), `cancelled`. The store
  registers itself as the payments proof source, so
  `payments().restore()` / `store.restore()` is self-sufficient after a
  restart and after a reinstall (fresh guest; the store proof moves the
  anonymous binding), and `store.restore()` finishes restored purchases
  (consume consumables, acknowledge the rest) only after the server
  accepted every proof. Connection handling (billing 9.x, one-time pending
  purchases, auto service reconnection, main-thread sheet) lives inside
  the SDK. Hosts must declare `com.android.vending.BILLING`; the store is
  inert without Play services (typed `SdkStoreUnavailableError`).
  The store dependency is now part of the artifact: the headless surface
  (Unity IAP hosts forwarding purchase tokens) is unchanged, so the
  contract becomes "no *mandatory* store dependency".
- Play Billing 9.1.0 ships as an `api` dependency of the SDK artifact.

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
