// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * The passkey step (`webauthn-authenticate.ftl`), rendered as the start page.
 *
 * Keycloak offers the passkey step before the user is identified only when
 * it comes first in the flow (see docs/passkeys.md), so on a realm with
 * passkeys this page is what the user lands on. It therefore shows the same
 * choices as the email-code start page: the passkey, the identity providers,
 * and the email code, which is a switch to the tessera-otp step through
 * Keycloak's "try another way" mechanism (`authenticationExecution`).
 *
 * `tessera.login-methods` orders and emphasises them as on the start page,
 * with `passkey` as the entry for this step. The way to the email code is
 * always shown, so nobody without a passkey is stuck here.
 *
 * The WebAuthn call itself is Keycloak's own script, bound by keycloakify's
 * hook to the passkey button; the hidden `webauth` form and its fields are
 * the script's contract and must keep their ids.
 */
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { useScript } from "keycloakify/login/pages/WebauthnAuthenticate.useScript";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { otherSelections, SELECTION_FIELD } from "../authSelections";
import { resolveLoginMethods, type LoginMethod, type Provider } from "../loginMethods";
import { PasskeyIcon } from "../PasskeyIcon";
import { ProviderIcon } from "../ProviderIcon";

const AUTH_BUTTON_ID = "authenticateWebAuthnButton";

export default function WebauthnAuthenticate(
    props: PageProps<Extract<KcContext, { pageId: "webauthn-authenticate.ftl" }>, I18n>
) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;
    const { url, client, social, auth, authenticators } = kcContext;
    const { msg, msgStr } = i18n;

    useScript({ authButtonId: AUTH_BUTTON_ID, kcContext, i18n });

    // Switching to the email code: one other step posts straight to it, more
    // than one goes through Keycloak's chooser.
    const others = otherSelections(auth?.authenticationSelections);
    const otpSelection = others.length === 1 ? others[0] : undefined;
    const canSwitch = others.length !== 0;

    // Default (no attribute): passkey leads, every provider follows, the email
    // code is a link. With the attribute, the first entry leads.
    const methods: LoginMethod[] = resolveLoginMethods(client.attributes, social?.providers, true) ?? [
        { kind: "passkey" },
        ...(social?.providers ?? []).map(provider => ({ kind: "idp" as const, provider })),
    ];
    const otpListed = methods.some(m => m.kind === "otp");

    const passkeyButton = (isLead: boolean) => (
        <button
            type="button"
            id={AUTH_BUTTON_ID}
            autoFocus
            className={isLead ? "ratio-login__social-btn ratio-login__social-btn--lead" : "ratio-login__social-btn"}
        >
            <PasskeyIcon />
            <span>{msgStr("ratioPasskeyLogin")}</span>
        </button>
    );

    const providerButton = (provider: Provider, isLead: boolean) => (
        <a
            id={`social-${provider.alias}`}
            href={provider.loginUrl}
            className={isLead ? "ratio-login__social-btn ratio-login__social-btn--lead" : "ratio-login__social-btn"}
        >
            <ProviderIcon providerId={provider.providerId} alias={provider.alias} />
            <span>{msgStr("tesseraContinueWith", provider.displayName)}</span>
        </a>
    );

    // The email code as a form, because switching is a POST. Rendered as a
    // button in the list when listed, as a link below it otherwise.
    const switchForm = (children: React.ReactNode) =>
        otpSelection ? (
            <form action={url.loginAction} method="post" className="ratio-login__switch">
                <input type="hidden" name={SELECTION_FIELD} value={otpSelection.authExecId} />
                {children}
            </form>
        ) : (
            <form action={url.loginAction} method="post" className="ratio-login__switch">
                <input type="hidden" name="tryAnotherWay" value="on" />
                {children}
            </form>
        );
    const otpButton = (isLead: boolean) =>
        switchForm(
            <button
                type="submit"
                id="kc-switch-to-otp"
                className={isLead ? "ratio-login__social-btn ratio-login__social-btn--lead" : "ratio-login__social-btn"}
            >
                <span>{otpSelection ? msgStr("tesseraOtpUseCode") : msgStr("ratioPasskeyOtherWay")}</span>
            </button>
        );
    const otpLink = switchForm(
        <button type="submit" id="kc-switch-to-otp" className="ratio-login__textlink">
            {otpSelection ? msgStr("tesseraOtpUseCode") : msgStr("ratioPasskeyOtherWay")}
        </button>
    );

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            headerNode={msg("tesseraOtpEmailTitle")}
        >
            {/* Keycloak's script fills these and submits the form. */}
            <form id="webauth" action={url.loginAction} method="post">
                <input type="hidden" id="clientDataJSON" name="clientDataJSON" />
                <input type="hidden" id="authenticatorData" name="authenticatorData" />
                <input type="hidden" id="signature" name="signature" />
                <input type="hidden" id="credentialId" name="credentialId" />
                <input type="hidden" id="userHandle" name="userHandle" />
                <input type="hidden" id="error" name="error" />
            </form>
            {/* With an identified user, the script limits the call to their keys. */}
            {authenticators && (
                <form id="authn_select">
                    {authenticators.authenticators.map(authenticator => (
                        <input key={authenticator.credentialId} type="hidden" name="authn_use_chk" value={authenticator.credentialId} />
                    ))}
                </form>
            )}

            <div id="kc-form-webauthn" className="ratio-login__social">
                <ul className="ratio-login__social-list">
                    {methods.map((method, index) => (
                        <li key={method.kind === "idp" ? `idp-${method.provider.alias}` : method.kind}>
                            {method.kind === "passkey" && passkeyButton(index === 0)}
                            {method.kind === "passkey" && index === 0 && <p className="ratio-login__hint">{msg("ratioPasskeyHint")}</p>}
                            {method.kind === "idp" && providerButton(method.provider, index === 0)}
                            {method.kind === "otp" && canSwitch && otpButton(index === 0)}
                        </li>
                    ))}
                </ul>
            </div>

            {canSwitch && !otpListed && (
                <div className="ratio-login__otp">
                    <div className="ratio-login__divider">{msg("tesseraOtpOr")}</div>
                    {otpLink}
                </div>
            )}
        </Template>
    );
}
