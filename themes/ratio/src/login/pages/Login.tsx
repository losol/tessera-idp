// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Standard username + password login page (login.ftl), restyled with Ratio
 * UI components. The form action, field names and hidden inputs are kept
 * identical to keycloakify's default so the Keycloak auth flow (POST to
 * `url.loginAction`) is unchanged; only the presentation is replaced.
 */
import { useState, type ReactNode } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import { useIsPasswordRevealed } from "keycloakify/tools/useIsPasswordRevealed";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { Button } from "@eventuras/ratio-ui/core/Button";
import { Input } from "@eventuras/ratio-ui/forms";
import { Label } from "@eventuras/ratio-ui/forms";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

export default function Login(props: PageProps<Extract<KcContext, { pageId: "login.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { social, realm, url, usernameHidden, login, auth, registrationDisabled, messagesPerField, enableWebAuthnConditionalUI } = kcContext;

    const { msg, msgStr } = i18n;

    const [isLoginButtonDisabled, setIsLoginButtonDisabled] = useState(false);

    const hasError = messagesPerField.existsError("username", "password");

    const usernameLabel = !realm.loginWithEmailAllowed
        ? msg("username")
        : !realm.registrationEmailAsUsername
          ? msg("usernameOrEmail")
          : msg("email");

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={!hasError}
            headerNode={msg("loginAccountTitle")}
            displayInfo={realm.password && realm.registrationAllowed && !registrationDisabled}
            infoNode={
                <div id="kc-registration">
                    <span>
                        {msg("noAccount")}{" "}
                        <a tabIndex={8} href={url.registrationUrl}>
                            {msg("doRegister")}
                        </a>
                    </span>
                </div>
            }
            socialProvidersNode={
                realm.password && social?.providers !== undefined && social.providers.length !== 0 ? (
                    <div id="kc-social-providers" className="ratio-login__social">
                        <hr />
                        <h2>{msg("identity-provider-login-label")}</h2>
                        <ul className="ratio-login__social-list">
                            {social.providers.map(p => (
                                <li key={p.alias}>
                                    <a id={`social-${p.alias}`} href={p.loginUrl}>
                                        <span dangerouslySetInnerHTML={{ __html: kcSanitize(p.displayName) }} />
                                    </a>
                                </li>
                            ))}
                        </ul>
                    </div>
                ) : null
            }
        >
            <div id="kc-form">
                <div id="kc-form-wrapper">
                    {realm.password && (
                        <form
                            id="kc-form-login"
                            className="ratio-login__form"
                            onSubmit={() => {
                                setIsLoginButtonDisabled(true);
                                return true;
                            }}
                            action={url.loginAction}
                            method="post"
                        >
                            {!usernameHidden && (
                                <div className="ratio-login__field">
                                    <Label htmlFor="username">{usernameLabel}</Label>
                                    <Input
                                        className="ratio-login__input"
                                        tabIndex={2}
                                        id="username"
                                        name="username"
                                        defaultValue={login.username ?? ""}
                                        type="text"
                                        autoFocus
                                        autoComplete={enableWebAuthnConditionalUI ? "username webauthn" : "username"}
                                        aria-invalid={hasError}
                                    />
                                    {hasError && (
                                        <span
                                            id="input-error"
                                            className="ratio-login__field-error"
                                            aria-live="polite"
                                            dangerouslySetInnerHTML={{
                                                __html: kcSanitize(messagesPerField.getFirstError("username", "password"))
                                            }}
                                        />
                                    )}
                                </div>
                            )}

                            <div className="ratio-login__field">
                                <Label htmlFor="password">{msg("password")}</Label>
                                <PasswordWrapper i18n={i18n} passwordInputId="password">
                                    <Input
                                        className="ratio-login__input"
                                        tabIndex={3}
                                        id="password"
                                        name="password"
                                        type="password"
                                        autoComplete="current-password"
                                        aria-invalid={hasError}
                                    />
                                </PasswordWrapper>
                                {usernameHidden && hasError && (
                                    <span
                                        id="input-error"
                                        className="ratio-login__field-error"
                                        aria-live="polite"
                                        dangerouslySetInnerHTML={{
                                            __html: kcSanitize(messagesPerField.getFirstError("username", "password"))
                                        }}
                                    />
                                )}
                            </div>

                            <div className="ratio-login__form-options">
                                {realm.rememberMe && !usernameHidden && (
                                    <label className="ratio-login__remember-me">
                                        <input
                                            tabIndex={5}
                                            id="rememberMe"
                                            name="rememberMe"
                                            type="checkbox"
                                            defaultChecked={!!login.rememberMe}
                                        />{" "}
                                        {msg("rememberMe")}
                                    </label>
                                )}
                                {realm.resetPasswordAllowed && (
                                    <a tabIndex={6} href={url.loginResetCredentialsUrl}>
                                        {msg("doForgotPassword")}
                                    </a>
                                )}
                            </div>

                            <input type="hidden" id="id-hidden-input" name="credentialId" value={auth.selectedCredential} />
                            <Button
                                tabIndex={7}
                                disabled={isLoginButtonDisabled}
                                variant="primary"
                                block
                                name="login"
                                id="kc-login"
                                type="submit"
                            >
                                {msgStr("doLogIn")}
                            </Button>
                        </form>
                    )}
                </div>
            </div>
        </Template>
    );
}

function PasswordWrapper(props: { i18n: I18n; passwordInputId: string; children: ReactNode }) {
    const { i18n, passwordInputId, children } = props;

    const { msgStr } = i18n;

    const { isPasswordRevealed, toggleIsPasswordRevealed } = useIsPasswordRevealed({ passwordInputId });

    return (
        <div className="ratio-login__password-group">
            {children}
            <button
                type="button"
                className="ratio-login__password-toggle"
                aria-label={msgStr(isPasswordRevealed ? "hidePassword" : "showPassword")}
                aria-controls={passwordInputId}
                onClick={toggleIsPasswordRevealed}
            >
                {msgStr(isPasswordRevealed ? "hidePassword" : "showPassword")}
            </button>
        </div>
    );
}
