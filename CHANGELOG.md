# Changelog

All notable changes to the Open4ID Android SDK are documented here.
Coordinates: `com.open4id:open4id-sdk` on Maven Central.

## 0.3.1

- Forgot-password email format check: `requestPasswordReset()` rejects
  malformed addresses locally with `SdkValidationError`
  (`fieldErrors.email = invalid`, same shape rule as Core API) without
  any network call; unknown addresses still get the generic success so
  responses never reveal registration state. Failures render a dedicated
  `invalid_email` message (English/Vietnamese) in `Open4IDAuthView`.
- First Maven Central release: `com.open4id:open4id-sdk:0.3.1`
  (signed AAR plus sources and javadoc jars).
