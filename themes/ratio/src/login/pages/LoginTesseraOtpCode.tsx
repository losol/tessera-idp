// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * One-time-code entry page for the passwordless login flow (custom page
 * `login-tessera-otp-code.ftl`, rendered by the tessera-otp authenticator).
 *
 * The user types the code that was emailed to `sentToEmail` (the full address,
 * shown so they can confirm they typed it correctly). The form POSTs a single
 * `otp-code` field to `url.loginAction`; that field name is the contract the
 * Java authenticator reads, so it must not change. A secondary "wrong email"
 * form POSTs a `reset-email` marker to the same action to go back to the email
 * step. Styling reuses the Ratio Template and `ratio-login__*` classes from the
 * standard Login page.
 *
 * The address is attacker-controlled echo: it is rendered as plain escaped
 * React text (never dangerouslySetInnerHTML), and the instruction string's
 * `{0}` arg is interpolated by `msg(...)`, which escapes args when rendered as
 * a JSX child.
 */
import { useState } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { Button } from "@eventuras/ratio-ui/core/Button";
import { Input } from "@eventuras/ratio-ui/forms";
import { Label } from "@eventuras/ratio-ui/forms";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

export default function LoginTesseraOtpCode(
    props: PageProps<Extract<KcContext, { pageId: "login-tessera-otp-code.ftl" }>, I18n>
) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { url, sentToEmail, messagesPerField } = kcContext;

    const { msg, msgStr } = i18n;

    const [isSubmitDisabled, setIsSubmitDisabled] = useState(false);

    const hasError = messagesPerField.existsError("otp-code");

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={!hasError}
            headerNode={msg("tesseraOtpTitle")}
        >
            <div id="kc-form">
                <div id="kc-form-wrapper">
                    <form
                        id="kc-otp-form"
                        className="ratio-login__form"
                        onSubmit={() => {
                            setIsSubmitDisabled(true);
                            return true;
                        }}
                        action={url.loginAction}
                        method="post"
                    >
                        <div className="ratio-login__field">
                            <Label htmlFor="otp-code">{msg("tesseraOtpInstruction", sentToEmail ?? "")}</Label>
                            <Input
                                className="ratio-login__input"
                                id="otp-code"
                                name="otp-code"
                                type="text"
                                autoFocus
                                autoComplete="one-time-code"
                                inputMode="text"
                                autoCapitalize="characters"
                                autoCorrect="off"
                                spellCheck={false}
                                aria-invalid={hasError}
                                aria-describedby={hasError ? "otp-code-error" : undefined}
                            />
                            {hasError && (
                                <span
                                    id="otp-code-error"
                                    className="ratio-login__field-error"
                                    aria-live="polite"
                                    dangerouslySetInnerHTML={{
                                        __html: kcSanitize(messagesPerField.getFirstError("otp-code"))
                                    }}
                                />
                            )}
                        </div>

                        <Button
                            disabled={isSubmitDisabled}
                            variant="primary"
                            block
                            name="login"
                            id="kc-login"
                            type="submit"
                        >
                            {msgStr("doSubmit")}
                        </Button>
                    </form>

                    {/*
                     * Separate form so the reset marker never rides along with
                     * the main `otp-code` submit. Styled as a plain text link.
                     */}
                    <form
                        id="kc-otp-reset-form"
                        className="ratio-login__otp"
                        action={url.loginAction}
                        method="post"
                    >
                        <input type="hidden" name="reset-email" value="true" />
                        <button type="submit" className="ratio-login__textlink">
                            {msgStr("tesseraOtpWrongEmail")}
                        </button>
                    </form>
                </div>
            </div>
        </Template>
    );
}
