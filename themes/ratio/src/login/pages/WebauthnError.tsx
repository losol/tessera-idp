// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * The passkey did not work (`webauthn-error.ftl`): the user cancelled, the
 * device has no passkey for the account, or the response was rejected.
 * Keycloak's message is shown by the Template.
 *
 * The way out is the email code when the flow offers it (a switch to that
 * step, as on the passkey start page), with retrying the passkey second;
 * during registration there is nothing to switch to, so retry leads and an
 * application-initiated registration can be cancelled.
 */
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { otherSelections, SELECTION_FIELD } from "../authSelections";

export default function WebauthnError(props: PageProps<Extract<KcContext, { pageId: "webauthn-error.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;
    const { url, auth, execution, isAppInitiatedAction } = kcContext;
    const { msg, msgStr } = i18n;

    const others = otherSelections(auth?.authenticationSelections);
    const otpSelection = others.length === 1 ? others[0] : undefined;

    const retry = (
        <form action={url.loginAction} method="post" id="kc-error-credential-form">
            <input type="hidden" name={SELECTION_FIELD} value={execution} />
            <input type="hidden" name="isSetRetry" value="retry" />
            <button
                type="submit"
                id="kc-try-again"
                className={`ratio-login__btn ratio-login__btn--block ${otpSelection ? "ratio-login__btn--secondary" : "ratio-login__btn--primary"}`}
            >
                {msgStr("ratioPasskeyTryAgain")}
            </button>
        </form>
    );

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={true}
            headerNode={msg("ratioPasskeyErrorTitle")}
        >
            <div className="ratio-login__passkey">
                <p className="ratio-login__passkey-lead">{msg("ratioPasskeyErrorBody")}</p>
                <div className="ratio-login__buttons">
                    {otpSelection && (
                        <form action={url.loginAction} method="post" id="kc-switch-to-otp-form">
                            <input type="hidden" name={SELECTION_FIELD} value={otpSelection.authExecId} />
                            <button type="submit" id="kc-switch-to-otp" className="ratio-login__btn ratio-login__btn--primary ratio-login__btn--block">
                                {msgStr("ratioPasskeyEmailCode")}
                            </button>
                        </form>
                    )}
                    {retry}
                    {isAppInitiatedAction && (
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
            </div>
        </Template>
    );
}
