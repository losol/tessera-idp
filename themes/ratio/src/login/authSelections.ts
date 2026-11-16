// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Keycloak's "try another way" list, `auth.authenticationSelections`: the
 * alternatives of the current flow step the user may switch to by POSTing
 * `authenticationExecution=<authExecId>` to the login action.
 *
 * Only steps Keycloak allows at this point are listed. Before the user is
 * identified that excludes credential-bound steps, so the passkey step is
 * offered only when it comes first in the flow (see docs/passkeys.md); the
 * email code (tessera-otp) needs no user and is always listed next to it.
 */
import type { AuthenticationSelection } from "./KcContext";

/** The form field Keycloak reads the chosen alternative from. */
export const SELECTION_FIELD = "authenticationExecution";

// Keycloak's credential metadata for the WebAuthn passwordless step.
const PASSKEY_ICON_CLASS = "kcAuthenticatorWebAuthnPasswordlessClass";
const PASSKEY_DISPLAY_NAME = "webauthn-passwordless-display-name";

export function isPasskeySelection(selection: AuthenticationSelection): boolean {
    return selection.iconCssClass === PASSKEY_ICON_CLASS || selection.displayName === PASSKEY_DISPLAY_NAME;
}

/** The passkey step, when the user may switch to it. */
export function passkeySelection(selections: AuthenticationSelection[] | undefined): AuthenticationSelection | undefined {
    return selections?.find(isPasskeySelection);
}

/** The other steps the user may switch to, in Keycloak's order. */
export function otherSelections(selections: AuthenticationSelection[] | undefined): AuthenticationSelection[] {
    return selections?.filter(s => !isPasskeySelection(s)) ?? [];
}
