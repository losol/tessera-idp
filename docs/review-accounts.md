# App Review accounts

Mitt Losvik uses Los ID's passwordless email login. Apple's reviewer needs a
prepared civitas account to see citizens, talerett, family and chats. A normal
email code expires after five minutes and requires mailbox access; signing in
with the reviewer's Apple ID would instead open an empty account.

The `tessera-otp` execution accepts a fixed code for explicitly configured
review addresses and sends **no email** for them. This is off by default. It
uses the existing email/code forms, without showing the code or identifying
review addresses in the page. Review notes must tell the reviewer to choose
email login and enter the prepared email and code, even though the page uses
the usual email-delivery wording.

## Configuration

Set these keys on the **authenticator config of the `tessera-otp` execution**
in the realm's browser flow, alongside its existing ALTCHA/OTP settings:

| Key | Value |
| --- | --- |
| `review-accounts` | Secret string of `email=code` entries separated by commas or actual newlines. Example syntax only: `appreview@losvikkommune.no=12345678`. Generate a different, random code of at least 8 digits for actual use. |
| `review-client-ids` | `civitas-mobile` for Mitt Losvik. Optional: comma- or whitespace-separated exact client IDs. Absent/blank allows all clients. |

Addresses match exactly after trimming and case normalization with
`Locale.ROOT`; plus aliases, prefixes and subdomains are separate addresses.
No pattern matching takes place. `*` and `?` are rejected. Codes are strings
of at least eight ASCII digits, preserving leading zeroes. Empty list items
are ignored. Any malformed entry, short/nonnumeric code, or duplicate address
(ignoring case) disables the **entire list**, with a generic warning that
never includes the supplied value. Disabled or invalid configuration falls
back to ordinary email-code login. Client IDs match exactly and case
sensitively; a listed review address on a disallowed client is rejected
without sending email. The allowlist restricts this login method, not access
through an existing SSO session or other identity providers.

Provision the Keycloak user first, with the exact email and `enabled: true`,
and prepare the associated civitas data separately. Review mode never creates
a new user or bypasses disabled status. An account found only by username with
a different email cannot use the review code. Successful review verification
marks the prepared user's email verified, as ordinary OTP login does; this is
an explicit administrative trust decision, not evidence of mailbox ownership.

## Secret handling and eventuras-infra handoff

The actual list is a credential. Store it in **Infisical** and deliver it via
`eventuras-infra`'s secret handling into realm setup. Never commit it in either
repository, a realm JSON file, fixtures, command examples or a PR. Do not print
it, put it in shell arguments/history, or echo it in setup errors. The
`review-accounts` property uses Keycloak's password field type; this masks its
console input, but does **not** encrypt the authenticator config or promise
redaction from admin API responses, exports, backups or request logging.
Restrict realm administration and protect those outputs as secrets. Literal
`${vault.…}` references are not resolved by this setting: infra must supply
the resolved list through the authenticated admin API.

In `eventuras-infra` (observed at `9c4c3e5`), wire the following:

1. Introduce secret input **`TESSERA_OTP_REVIEW_ACCOUNTS`** from Infisical,
   with value `<prepared-email>=<random-code-of-at-least-8-digits>` (comma
   separate additional accounts). Missing/blank means off; use separate values
   for `losol-test` and `losol`. The input name is an infra contract proposed
   here, not an environment variable automatically read by this provider.
2. In `apps/losol-keycloak/setup-realm.ts`, pass that resolved input and
   `review-client-ids: civitas-mobile` into
   `scripts/keycloak/client.ts`'s `setupTesseraOtpBrowserFlow`.
3. Have the helper write one execution config containing
   **`config["review-accounts"] = resolved TESSERA_OTP_REVIEW_ACCOUNTS`** and
   **`config["review-client-ids"] = "civitas-mobile"`**, preserving
   `altcha-hmac-key`, `altcha-cost`, `code-length`, `code-lifetime` and
   `code-alphabet` where configured. Create the config even without an ALTCHA
   key when review accounts are enabled. The helper currently deletes and
   recreates the browser flow; every run must reapply the review settings.
   When the input is cleared, explicitly remove or blank `review-accounts`
   rather than retaining a previous value.
4. Keep realm **`bruteForceProtected: true`** and its existing lockout policy.
   Test in `losol-test` first. A deployment needs the provider JAR containing
   this change; this work does not change images, release or deploy anything.

## Limits, expiry and audit

ALTCHA still applies when enabled. A review challenge uses `code-lifetime`
(default 300 seconds) and the same shared per-realm/email attempt counter,
`MAX_ATTEMPTS = 5`, as ordinary OTP. Attempts do not extend the expiry. Going
back, resubmitting the address, opening another tab or waiting out the mail
throttle does not reset a live review challenge's attempts. An exhausted
review challenge remains blocked until its original expiry. After expiry,
start again at the email step; the configured fixed credential still works
for the new challenge. No code is written into its shared challenge record.

The existing shared-store counter is a best-effort read/modify/write, not an
atomic counter; concurrent attempts can race. Keycloak realm brute-force
protection remains the primary lockout control. Review users are attached
before invalid-code failure challenges so Keycloak can count failures against
them, and temporary/permanent lockouts and enabled status are checked at both
steps. No independent lockout bypass is added.

Every successful fixed-code verification logs an INFO `Review-account login`
with the normalized address, realm and client ID, never the code. Ordinary
logins do not emit this message. Treat the log as personal data. The entry
records successful OTP verification; a later required action or protocol step
may still prevent a completed application login.

## Rotation and turning it off

The config is read again on every verification. Rotating the code immediately
rejects the old code, including on outstanding review challenges. Removing or
blanking `review-accounts` in Infisical **and applying realm setup** disables
fixed-code acceptance, including outstanding challenges; those users then
follow normal email-code login. Removing just the client allowlist broadens
access and does not turn the feature off.

After review, remove the list and apply setup, then disable the prepared user
if it should no longer sign in. Revoke its existing Keycloak sessions/offline
sessions separately if immediate loss of app access is required: changing
authenticator config does not revoke already issued sessions or tokens.

## Verification

Run `mvn -B clean verify` in `plugins/tessera-otp`. The proxy-based JUnit tests
cover parsing and secret metadata, fixed-code login without email providers,
normal OTP delivery, exact matching, current-config rotation/removal, expiry,
attempt limits across sessions/back navigation, client restrictions, disabled
or missing users, realm lockouts, ALTCHA gating, and no secret in form
attributes, challenge storage or provider logs.

A disposable local Keycloak 26.8.2 runtime with the built provider JAR and
Ratio theme also verified OIDC authorization-code issuance for the fixed
credential without SMTP, real SMTP delivery and redemption for an ordinary
address, client rejection, the shared attempt cap across fresh browsers,
config removal during login, an increment in the actual Keycloak failure
counter and realm lockout, plus address-only INFO audit and no credential in
login HTML or runtime logs. This does not verify the prepared civitas data or
the released iOS app.

Before review, verify in a test realm with the configured image and Mailpit:

- The prepared address signs in with the review code from `civitas-mobile`,
  with no SMTP delivery and one INFO audit line containing no code.
- A wrong review code increments Keycloak's user failure counter, locks out
  under the realm policy, and cannot bypass the shared five-attempt limit by
  opening another browser. Disabled accounts also cannot sign in.
- A similar address and an ordinary address receive normal expiring codes;
  the fixed credential fails for them. A disallowed client cannot use the
  review address. Normal users on that client remain unaffected.
- Rotation rejects the old credential; removing the list rejects an
  outstanding review challenge. Confirm no secret appears in the login HTML
  or operational logs, and that the prepared civitas data is visible in the app.
