# Open4ID minimal Android sample (headless)

The smallest possible published-SDK integration: one activity driving
the headless `Open4IDAuthClient` directly — no login view, no social
flows, no build tricks. It exercises bootstrap, email
register/sign-in, guest sign-in, session refresh, and sign-out against
the local dev Core API.

It needs the published SDK (`com.open4id:open4id-sdk:0.3.1` from Maven
Central, pinned in `app/build.gradle`).

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

## Notes

- Sessions live in SDK instance memory only and vanish on process
  death; pass a `SessionStore` to `setSessionStore()` to persist.
- Social sign-in (Google/GitHub/Apple) is intentionally out of scope
  here — see `sdks/android/sample` (project dependency) for the full
  Custom Tab flows, and `../android-sdk-minimal-ui` for the drop-in
  login view.
