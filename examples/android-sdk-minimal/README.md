# Open4ID minimal Android sample (headless)

The smallest possible published-SDK integration: one activity driving
the headless `Open4IDAuthClient` directly — no login view, no social
flows, no build tricks. It exercises bootstrap, email
register/sign-in, guest sign-in, session refresh, sign-out, and the
payments surface against the local dev Core API.

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
cd examples/android-sdk-minimal
gradle :app:installDebug   # no wrapper is checked in; use system gradle
```

Click through: **1. Bootstrap** (public discovery, no secrets) →
**2a. Register** (sends the 24h verification email) or **2b. Sign
in** / **2c. Continue as guest** → **3. Refresh** (rotates only near
expiry) → **4. Sign out** (revokes server-side, clears memory). SDK
failures render as stable error keys (`client.*`, `system.*`).

## Payments (Play Billing)

**5. Payments (Play Billing)** opens a self-contained payments screen that
drives the SDK's built-in Play Billing store
(`client.playBillingPayments(activity)`) — the same surface
`sdks/android/sample` shows:

- The catalog comes from the bootstrap snapshot (`payments().products()`),
  filtered to `google_play` rows — the product ids must exist in the
  Play Console for the package. The screen loads the shop with Play prices
  (`store().loadProducts()`); rows Play cannot resolve are logged as
  unresolvable (a Buy button would be disabled), a store *error* fails the
  load.
- **Buy with Play Billing** runs `store().purchase(productId)`: the real
  Play sheet (a license-tester account buys with a test instrument) →
  `verifyTransaction` (one_time/subscription) or `verifyConsumable`
  (consumable, which returns a `transactionRef` receipt instead of
  entitlements) → acknowledge/consume only after Core API accepts the
  proof (otherwise Play auto-refunds after three days). A cancelled sheet
  logs `cancelled`; ask-to-buy logs `pending` — a later restore picks it
  up.
- **Restore purchases** and the screen-open restore both call
  `store().restore()` — the ledger recompute plus the purchases Play
  reports for the store account (the proof source the store registered on
  creation, Play's `queryPurchasesAsync` over `INAPP` + `SUBS`). That
  makes the same call recover purchases after a restart (stored session)
  and after a reinstall (fresh guest; the store proof moves the anonymous
  binding). Restored purchases are acknowledged/consumed only after the
  server accepted every proof.
- **Paid state rehydrates on its own**: once per process, after the first
  bootstrap, the SDK refreshes a stored session's ledger rows or signs in
  as a guest and restores through the store's proof source. The session
  itself persists by default (private `open4id_session` SharedPreferences,
  which also rides Android Auto Backup), so the same player — guest
  included — and their purchases come back after a relaunch (and, via
  backup, usually after an uninstall/reinstall too). The main screen logs
  the change (`paid state: …`), and the payments screen shows the live
  rows.
- **List payment purchases** reads the player's ledger receipts
  (`payments().purchases()`) — how a game learns about consumable
  deliveries and refunds while webhook delivery is not implemented.
- **Refresh entitlements** re-reads the entitlement snapshot;
  entitlement changes from any source re-render the bottom panel.

The screen's log lines are the same ones the live smoke runbook
(`docs/tasks/PAYMENT_LIVE_SMOKE_RUNBOOK.md`) asserts, so this example
doubles as a manual payment harness.

## Notes

- Sessions live in SDK instance memory only and vanish on process
  death; pass a `SessionStore` to `setSessionStore()` to persist.
- The activity registers `client.onAuthStateChanged(...)` so the main app
  can react to any auth state change (sign-in, sign-out, refresh,
  deletion).
- Social sign-in (Google/GitHub/Apple) is intentionally out of scope
  here — see `sdks/android/sample` (project dependency) for the full
  Custom Tab flows, and `../android-sdk-minimal-ui` for the drop-in
  login view.
