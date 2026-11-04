# Open4ID minimal login-UI Android sample

The same backend as `../android-sdk-minimal`, but the login UI is the
SDK's `Open4IDAuthView` instead of headless calls: one activity, no
protocol code. Sign-in, sign-up, forgot-password, guest, link-email,
and delete-account come with the view; this activity only creates the
headless client, bootstraps it, binds both to the view, and listens
for sign-in/sign-out/account-deleted.

It needs the published SDK (`com.open4id:open4id-sdk:0.6.0` from Maven
Central, pinned in `app/build.gradle`) — Play Billing 9.x ships inside the
SDK since 0.6.0: the built-in store owns the sheet, verification, and the
acknowledge lifecycle, so the example adds no store dependency of its own.

## Prerequisites

1. An Open4ID application with a development environment, a public SDK
   key (`sdk_pk_...`), and email/password + guest login enabled
   (Publisher Portal → Applications → Authentication). The form comes
   prefilled with the dev `Login Show Case` values.
2. No dashboard changes needed: this example shares the package
   (`com.open4id.example.android`) and redirect URI
   (`com.open4id.example.android://open4id/callback`) already
   registered for the SDK `sample` app — the android platform entry
   and the exact OAuth redirect URI cover all three apps.

> All Android examples use the same `applicationId`, so installing
> one replaces the other on the device — install and test one at a
> time. Replace the values with your own application to use it
> elsewhere (then the package + redirect URI registration in the
> Portal applies to your new package).

## Run

```sh
cd examples/android-sdk-minimal-ui
gradle :app:installDebug   # no wrapper is checked in; use system gradle
```

Tap **Open login UI** (full-screen swap with a Back button) or
**Open login dialog** (the same view in an `Open4IDLoginDialog`
modal): sign up (sends the 24h verification
email), sign in, continue as guest, or request a password reset. The
view remembers the session while the client lives; the auth listener
logs sign-in/sign-out/deletion to logcat (`Open4ID` tag).

## Payments (Play Billing)

**Payments (Play Billing)** opens a self-contained payments screen with
its own log panel — the same surface as `../android-sdk-minimal`
(section "Payments (Play Billing)" there), pointed at the same client:

- the catalog comes from the bootstrap snapshot (`payments().products()`),
  filtered to `google_play` rows; the screen loads the shop with Play
  prices (`store().loadProducts()`);
- **Buy with Play Billing** runs `store().purchase(productId)`: the real
  Play sheet, verification through Core API (`verifyTransaction` /
  `verifyConsumable` — consumable rows get a `transactionRef` receipt, not
  entitlements), and acknowledge/consume only after Core API accepts the
  proof; a cancelled sheet and ask-to-buy (`pending`) are logged, not
  errors;
- **Restore purchases** and the screen-open restore call
  `store().restore()` — the ledger recompute plus the purchases Play
  reports (the proof source the store registered on creation, Play
  `queryPurchasesAsync` over `INAPP` + `SUBS`), so the same call
  recovers purchases after a restart and after a reinstall (fresh
  guest; the store proof moves the anonymous binding), and finishes
  restored purchases only after the server accepted every proof;
- **List payment purchases** reads the player's ledger receipts
  (`payments().purchases()`), and **Refresh entitlements** re-reads the
  snapshot — entitlement changes re-render in the log panel.

**Social sign-in:** GitHub and Apple work out of the SDK: pass the host
`Activity` to the client (e.g. `new Open4IDAuthClient(this)` or
`setHostActivity`), register the `/github-callback` / `/apple-callback`
custom-scheme intent filter (see `AndroidManifest.xml`), and forward the
deep link to `client.completeDeepLink(...)`. Google still needs the
sample-only `SocialAuthClient` subclass (this module's
`SocialAuthClient.java`) for the interactive `GoogleSignInIntent` account
picker, which requires `com.google.android.gms.auth.api.signin`
({@code play-services-auth}).

Google/GitHub/Apple bridges need their own registration (provider
consoles plus the per-app Portal bridge URLs) — email and guest work
with the dev registration above.

## Auth state events

The activity registers `client.onAuthStateChanged(...)` so the main app
can react to any auth state change (sign-in, sign-out, refresh,
deletion) from any source — headless calls or the login UI/dialog.

## Notes

- The login UI screen is a view swap, not a new activity: the on-screen
  **Back** button and the system back gesture both return to the config
  screen (a `fitsSystemWindows` guard keeps the Back button below the
  status bar on edge-to-edge displays); system Back on the config
  screen exits the app as usual.
- Sessions live in SDK instance memory only and vanish on process
  death; pass a `SessionStore` to `setSessionStore()` to persist.
