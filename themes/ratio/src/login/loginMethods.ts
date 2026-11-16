// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Per-client login methods, read from the client attribute
 * `tessera.login-methods`: a comma-separated, ordered list of identity-provider
 * aliases and/or the literal `otp` (the email one-time-code form).
 *
 * - Order is the preference: the first renderable entry is the lead method the
 *   page emphasises; the rest are offered alongside it, in list order.
 * - `passkey` is the WebAuthn passwordless step, renderable only on a page
 *   Keycloak offers it on (see authSelections.ts). Elsewhere it is ignored,
 *   like an IdP that is not enabled in the realm or any unknown entry.
 * - Attribute absent, or nothing renderable → `null`, and the page falls back
 *   to its default: every realm provider, email code behind the link. Falling
 *   back rather than rendering an empty page is deliberate — being strict here
 *   would lock people out.
 *
 * This is presentation only. It does not stop a crafted POST to the email form
 * or a `kc_idp_hint` redirect to a hidden provider; restricting a client for
 * real is a per-client browser flow on the Keycloak side.
 */

import type { KcContext } from "./KcContext";

export const LOGIN_METHODS_ATTRIBUTE = "tessera.login-methods";

/** The reserved entry for the email one-time-code form. */
export const OTP_METHOD = "otp";

/** The reserved entry for the passkey (WebAuthn passwordless) step. */
export const PASSKEY_METHOD = "passkey";

/** An identity provider as the start page receives it (see KcContext.ts). */
export type Provider = NonNullable<
    Extract<KcContext, { pageId: "login-tessera-otp-start.ftl" }>["social"]["providers"]
>[number];

export type LoginMethod = { kind: "otp" } | { kind: "passkey" } | { kind: "idp"; provider: Provider };

/**
 * Resolves the configured methods against the realm's providers. Returns the
 * ordered, renderable methods (at least one), or `null` for the default page.
 */
export function resolveLoginMethods(
    attributes: Record<string, string> | undefined,
    providers: Provider[] | undefined,
    passkeyOffered = false
): LoginMethod[] | null {
    const raw = attributes?.[LOGIN_METHODS_ATTRIBUTE];
    if (raw === undefined) return null;

    const methods: LoginMethod[] = [];
    const seen = new Set<string>();

    for (const entry of raw.split(",").map(s => s.trim())) {
        if (entry === "" || seen.has(entry)) continue;
        seen.add(entry);

        if (entry === OTP_METHOD) {
            methods.push({ kind: "otp" });
            continue;
        }
        if (entry === PASSKEY_METHOD) {
            if (passkeyOffered) methods.push({ kind: "passkey" });
            continue;
        }
        // Aliases are case-sensitive in Keycloak, so match exactly.
        const provider = providers?.find(p => p.alias === entry);
        if (provider !== undefined) methods.push({ kind: "idp", provider });
    }

    return methods.length === 0 ? null : methods;
}
