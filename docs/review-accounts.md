# Review accounts: a fixed login code for one client, for a while

App store review (Apple's, say) needs an account the reviewer can open
without access to its mailbox. The passwordless login normally emails a code,
so for a prepared account the `tessera-otp` authenticator can instead accept
a **fixed code**, for **one client only**, **until a date you set**. The
login pages look exactly the same; the reviewer types the prepared address
and the fixed code where the emailed code would go.

The feature is off unless all three settings are present. It never creates a
user, never sends email to a review address, and never bypasses a disabled
or locked account.

## Set it up

1. **Create the user** in the realm with the exact email address, enabled,
   and give it whatever data the reviewer should see. The feature only ever
   matches an existing, enabled user.
2. **Set three keys on the `tessera-otp` execution's authenticator config**
   (Authentication → the browser flow → the Tessera OTP step → settings), next
   to the existing ALTCHA and code settings:

   | Key | Value |
   | --- | --- |
   | `review-accounts` | `email=code` entries, separated by commas or newlines. The code is at least 8 digits; make it random. Example syntax: `appreview@example.org=48215093` |
   | `review-client-ids` | The client IDs the code works for, e.g. `civitas-mobile`. Exact match. Nothing else accepts the code. |
   | `review-valid-until` | When it stops working: a date, `2026-12-01` (the whole day, UTC), or an instant, `2026-12-01T12:00:00Z` |

3. **Keep realm brute force protection on** (Security defenses → Brute force
   detection). Wrong guesses against the fixed code are counted against the
   review account like wrong passwords, and lock it under the realm policy.
   Without brute force protection the fixed code is refused.
4. Tell the reviewer: choose email login, enter the prepared address, enter
   the fixed code when asked for the emailed code.

The `review-accounts` value is a credential. Keycloak masks it in the admin
console (password field) but stores authenticator config in plain text, so
keep it out of realm exports, commits and chat; store the real value in the
secret store that feeds realm setup, and pick a code you can throw away
afterwards.

## What happens

- **Email step:** a listed address gets no email. If anything is not in
  order (date passed, other client, no such user, disabled, locked, realm
  without brute force protection, malformed settings), the address falls
  back to the **ordinary emailed code**, and a warning names the reason in
  the server log, without the code.
- **Code step:** the fixed code is compared as configured *right now*, so
  rotating or removing it, or reaching the date, applies to a login that is
  already half-way through. Expiry, the five-attempt cap and the send
  throttle are the same as for an emailed code.
- **Audit:** every accepted fixed code logs one INFO line with the address,
  realm and client. Ordinary logins log nothing new.
- **Not stored:** the code lives only in the authenticator config; the
  challenge record in Keycloak's single-use store carries a `review` flag
  and no code.

## Turn it off

It turns itself off at `review-valid-until`. To stop earlier, blank
`review-accounts` (or shorten the date) and apply the realm setup; an
outstanding challenge fails on the next attempt. Then disable the user if it
should not sign in at all; revoke its sessions in the admin console if it
must lose access at once, since changing the config does not end sessions
already issued.

## Automated tests

The same settings suit end-to-end tests that only need a signed-in user: the
test enters the address and the fixed code, with no mailbox or Mailpit lookup.

```text
review-accounts     = e2e-user@example.org=<random code>
review-client-ids   = civitas-mobile, civitas-web
review-valid-until  = 2027-12-31
```

- Use it in a **test realm only**: within its date the code is a password
  that does not expire. In production keep it for the review window itself.
- Create each test user in the realm setup, enabled, with the data the tests
  expect. The code never creates users.
- Keep the code in one CI secret. Wrong guesses count against the user under
  the realm's brute force policy, so a test that keeps guessing locks its
  user out until the policy's window passes.
- Tests that start a login for the same address within two minutes share one
  challenge (the ordinary send throttle). That works, since the code is the
  same; one test user per suite avoids surprises.
- The repository's own smoke (`scripts/smoke-default-themes.py`) logs in with
  a real emailed code through Mailpit on purpose: it checks the email path.
  Leave it that way; the fixed code is for tests that just need a user.

## Limits

- One exact address per entry: no wildcards, and `appreview+x@…` is a
  different address.
- One bad entry (short code, typo, duplicate) disables the whole list, with a
  warning, rather than silently dropping one account.
- The client restriction gates this login method only. An existing SSO
  session, or another identity provider, is unaffected.
- Settings are checked by `ReviewAccessTest` and the two-step flow by
  `TesseraOtpReviewTest` in `plugins/tessera-otp` (`mvn package`).
