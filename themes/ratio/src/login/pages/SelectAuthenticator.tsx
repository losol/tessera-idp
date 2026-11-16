// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Keycloak's chooser (`select-authenticator.ftl`), reached by "try another
 * way" when a step has more than one alternative. One button per step; the
 * passkey and the email code get the theme's own labels, anything else
 * Keycloak's.
 */
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { isPasskeySelection, SELECTION_FIELD } from "../authSelections";
import { PasskeyIcon } from "../PasskeyIcon";

// The tessera-otp authenticator's display type, as Keycloak lists it.
const TESSERA_OTP_DISPLAY_NAME = "Tessera OTP";

export default function SelectAuthenticator(props: PageProps<Extract<KcContext, { pageId: "select-authenticator.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;
    const { url, auth } = kcContext;
    const { msg, msgStr, advancedMsg } = i18n;

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayInfo={false}
            headerNode={msg("ratioPasskeyChooseTitle")}
        >
            <form id="kc-select-credential-form" action={url.loginAction} method="post" className="ratio-login__social">
                <ul className="ratio-login__social-list">
                    {auth.authenticationSelections.map(selection => {
                        const passkey = isPasskeySelection(selection);
                        const otp = selection.displayName === TESSERA_OTP_DISPLAY_NAME;
                        return (
                            <li key={selection.authExecId}>
                                <button
                                    type="submit"
                                    name={SELECTION_FIELD}
                                    value={selection.authExecId}
                                    className={passkey ? "ratio-login__social-btn ratio-login__social-btn--lead" : "ratio-login__social-btn"}
                                >
                                    {passkey && <PasskeyIcon />}
                                    <span>
                                        {passkey
                                            ? msgStr("ratioPasskeyLogin")
                                            : otp
                                              ? msgStr("tesseraOtpUseCode")
                                              : advancedMsg(selection.displayName)}
                                    </span>
                                </button>
                            </li>
                        );
                    })}
                </ul>
            </form>
        </Template>
    );
}
