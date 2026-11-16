// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Creating a passkey (`webauthn-register.ftl`), the "Webauthn Register
 * Passwordless" required action. Meant to be offered, not forced: as an
 * application-initiated action (`kc_action=webauthn-register-passwordless`)
 * it has a "Not now"; as a required action on the account it has not.
 *
 * The WebAuthn call is Keycloak's own script, bound by keycloakify's hook to
 * the create button, which stays outside the hidden `register` form so the
 * script, not the click, submits it. That script asks for a name for the
 * passkey with a `prompt()` dialog; the page answers it with the default
 * label instead, so the user sees the device's own passkey dialog only.
 */
import { useEffect } from "react";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { useScript } from "keycloakify/login/pages/WebauthnRegister.useScript";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { PasskeyIcon } from "../PasskeyIcon";

const AUTH_BUTTON_ID = "authenticateWebAuthnButton";

export default function WebauthnRegister(props: PageProps<Extract<KcContext, { pageId: "webauthn-register.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;
    const { url, isSetRetry, isAppInitiatedAction } = kcContext;
    const { msg, msgStr } = i18n;

    useScript({ authButtonId: AUTH_BUTTON_ID, kcContext, i18n });

    // Keycloak's script names the passkey through window.prompt(prompt, default).
    // Answer with the default; the user named nothing else on this page.
    useEffect(() => {
        const original = window.prompt;
        window.prompt = (_message?: string, defaultValue?: string) => defaultValue ?? "";
        return () => {
            window.prompt = original;
        };
    }, []);

    const canCancel = isAppInitiatedAction && !isSetRetry;

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            headerNode={msg("ratioPasskeyRegisterTitle")}
        >
            <div className="ratio-login__passkey">
                <div className="ratio-login__passkey-mark">
                    <PasskeyIcon size={26} className="" />
                </div>
                <p className="ratio-login__passkey-lead">{msg("ratioPasskeyRegisterLead")}</p>
                <ul className="ratio-login__points">
                    <li>{msg("ratioPasskeyPoint1")}</li>
                    <li>{msg("ratioPasskeyPoint2")}</li>
                    <li>{msg("ratioPasskeyPoint3")}</li>
                </ul>

                {/* Keycloak's script fills these and submits the form. */}
                <form id="register" action={url.loginAction} method="post">
                    <input type="hidden" id="clientDataJSON" name="clientDataJSON" />
                    <input type="hidden" id="attestationObject" name="attestationObject" />
                    <input type="hidden" id="publicKeyCredentialId" name="publicKeyCredentialId" />
                    <input type="hidden" id="authenticatorLabel" name="authenticatorLabel" />
                    <input type="hidden" id="transports" name="transports" />
                    <input type="hidden" id="authenticatorAttachment" name="authenticatorAttachment" />
                    <input type="hidden" id="error" name="error" />
                </form>

                <div className="ratio-login__buttons">
                    <button type="button" id={AUTH_BUTTON_ID} autoFocus className="ratio-login__btn ratio-login__btn--primary ratio-login__btn--block">
                        {msgStr("ratioPasskeyCreate")}
                    </button>
                    {canCancel && (
                        <form action={url.loginAction} method="post" id="kc-webauthn-settings-form">
                            <button
                                type="submit"
                                id="cancelWebAuthnAIA"
                                name="cancel-aia"
                                value="true"
                                className="ratio-login__btn ratio-login__btn--secondary ratio-login__btn--block"
                            >
                                {msgStr("ratioPasskeyNotNow")}
                            </button>
                        </form>
                    )}
                </div>
                {canCancel && <p className="ratio-login__hint">{msg("ratioPasskeyLater")}</p>}
            </div>
        </Template>
    );
}
