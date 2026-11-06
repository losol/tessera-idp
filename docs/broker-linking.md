# Verified-email broker contract

Google (#92) and Apple (#93) share the policy in
`no.losol.keycloak.tessera.broker`. Apple is an additive dependent integration;
this PR contains no Apple provider. This contract targets Keycloak **26.8.2**.
Deployment declarations, realm/client configuration and secrets belong in
**eventuras-infra**, not in this repository. Civitas continues using its existing
Tessera realm issuer and application callback.

## Required realm/provider wiring

For each enabled provider, use one permanent alias (`google` or `apple`), pinned
to its original issuer. Never repurpose an alias for another issuer/provider,
delete/recreate it to migrate identities, or change its subject mapping. Keycloak
stores federated identity as **provider alias + subject**; a stable alias bound to
one validated issuer implements issuer + subject resolution. Returning login
does not search by email. A changed or missing provider email does not change
the local email, account ID, profile or citizen association.

1. Create a top-level basic first-login flow, for example
   `tessera-first-broker`, with **only** `tessera-broker-linking` as a **REQUIRED**
   execution. Select it as the provider's First login flow.
2. Create a top-level basic post-login flow, for example
   `tessera-post-broker`, with `tessera-broker-policy` as **REQUIRED**. Select it
   as the provider's Post login flow. It applies to returning users too.
3. Add the provider mapper **Validated broker identity guard**
   (`tessera-broker-claims`), sync mode `IMPORT`. It rejects unsafe configuration
   before broker account updates, including native authenticated linking.
4. Set provider `syncMode=IMPORT`, `trustEmail=false`,
   `validateSignature=true`, `disableNonce=false`, `storeToken=false`.
   Use the provider's fixed issuer and HTTPS JWKS endpoint, with
   `useJwksUrl=true`. Do not add email/username/profile/subject-rewriting mappers
   or mappers that overwrite security/citizen attributes. Do not add stock
   auto-link, override-link or review-profile executions to the first flow.
5. Keep realm email uniqueness enabled (`duplicateEmailsAllowed=false`), SMTP
   configured, brute-force protection enabled and existing required actions.
   Do not expose legacy external token exchange. Keep security attributes
   administrator-managed in the realm user profile.
6. Continue the existing Tessera OTP browser flow and Ratio theme. The optional
   standard identity-provider redirector execution supports `kc_idp_hint`;
   it is separate from the security policy. Client `tessera.login-methods` only
   controls presentation; it is not a security control.

`scripts/broker-realm.py` is a **local test realm generator**, not a deployment
declaration. It shows the flow/mapper configuration with synthetic identities.
Do not import it into a deployed realm.

## Decisions and stronger security

| Situation | First attachment |
| --- | --- |
| Validated, verified Gmail (`gmail.com`) or Workspace (`hd` nonempty) | Create a verified account, or attach to exactly one matching previously verified ordinary account |
| Google external email without `hd`, including `email_verified=true` | Fresh Keycloak email-link verification, or authenticate the intended account and link through native account settings |
| Validated Apple `email_verified` boolean `true` or exact string `"true"` | Same trusted-email path, including relay addresses |
| Multiple matches, username/email collision, disabled/unverified existing account, conflicting provider subject | Reject; never choose, merge or override an account |
| Credentials, pending required actions, stricter account/client/action policy | Reject broker-only access; use the existing stronger authentication path |

The initial lookup requires token email to match broker context email exactly;
browser forms and profile mappers cannot substitute an address. The provider's
validated ID token supplies `iss`, `sub`, `email_verified` and `hd`. Google string
`"true"` is not accepted as a boolean. Apple relay addresses are opaque addresses;
there is no inference of a private original email or cross-address matching.

All accounts with stored credentials (including passwords, TOTP and passkeys)
or pending required actions are conservatively protected. Existing ordinary
accounts are selected without rewriting any local attributes. New accounts get
a random internal username, validated email and provider-supplied names.

The security key is **`tessera.broker.security`**:

| Scope | Values/default |
| --- | --- |
| Realm attribute | `standard` (absent default), `strict` |
| Client attribute | Same; cannot relax realm/account requirements |
| Administrator-managed user attribute | Same; cannot relax other scopes |
| Server-set authentication-session **client note** | Same; for action-specific protection, preserved across first-broker flow reset |

Any `strict` or unknown value fails closed. Requested `acr_values` and
`kc_action` also block the email-equivalent login path. The action note must be
set by a trusted server extension **before** brokering; never copy it from an
arbitrary query parameter or user-editable attribute. Clients/actions with
stronger assurance requirements must be marked strict and retain their existing
step-up/authorization checks. This component does not mint a higher ACR or
implement MFA. Strict mode requires the configured stronger login route and
does not silently fall back to provider email or email OTP.

For different-address linking, authenticate the **existing target account**
using its normal protections, then use Keycloak's native account-console/AIA
`idp_link` action. Keycloak validates its authenticated user session, state and
provider callback and rejects identities already owned by another user. This
intentional authenticated path bypasses first/post-login flows; the claims
mapper still validates identity/configuration before attachment. It does not
make a protected account eligible for later broker-only login. Never implement
linking by posting a target account ID/email to the first-login authenticator.

Fresh external-email proof uses Keycloak's native signed, expiring, one-use
broker email-link action, bound to account/email/provider/subject/session. No
provider `email_verified` flag substitutes for that proof. SMTP absence fails
closed. An unverified pending new account may remain after cancellation; it
cannot be silently attached from another session. Account recovery/cleanup is
an administrator operation, not email-based identity migration.

## Validation and extension points

`ValidatedBrokerIdentity.from(context, firstAttachment)` accepts only built-in
`google` with `https://accounts.google.com`, and the separately hardened
`tessera-apple` with `https://appleid.apple.com`. It consumes
`OIDCIdentityProvider.VALIDATED_ID_TOKEN` after provider validation; context-key
presence alone is not token validation. The Apple integration must preserve
signature/issuer/audience/expiry/nonce checks before calling the broker. No
generic OIDC provider, browser claims or production issuer override is accepted.

`BrokerLinkingPolicy` is reusable and contains no Keycloak lookup side effects.
Provider additions must supply a validated identity and add shared trust tests,
not copy or replace policy. Apple owns provider-specific crypto/callback tests.
The shared policy/flow tests cover trust encodings, relay addresses, missing
returning email, ambiguous/conflicting identities, stronger policies and
preservation of the selected existing account.

Pinned upstream behavior was checked against
[Keycloak's Google broker](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/social/google/GoogleIdentityProvider.java),
[OIDC token/nonce validation](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/broker/oidc/OIDCIdentityProvider.java),
[broker resolution/linking order](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/services/resources/IdentityBrokerService.java),
[email-link verification](https://github.com/keycloak/keycloak/blob/26.8.2/services/src/main/java/org/keycloak/authentication/authenticators/broker/IdpEmailVerificationAuthenticator.java)
and [email import/update behavior](https://github.com/keycloak/keycloak/blob/26.8.2/server-spi-private/src/main/java/org/keycloak/broker/provider/AbstractIdentityProvider.java).
