// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Email-entry page for the passwordless login flow (custom page
 * `login-tessera-otp-start.ftl`, rendered by the tessera-otp authenticator).
 *
 * Layout favours the identity providers (Vipps first, ordered by guiOrder) as
 * the primary choice. The email one-time-code path is secondary: a text link
 * ("Log in with a one-time code") reveals the email field on demand. The form
 * POSTs a single `email` field to `url.loginAction` — that field name is the
 * contract the Java authenticator reads, so it must not change.
 */
import { useState, useRef, useEffect } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { Button } from "@eventuras/ratio-ui/core/Button";
import { Input } from "@eventuras/ratio-ui/forms";
import { Label } from "@eventuras/ratio-ui/forms";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

// Side-effect import: registers the <altcha-widget> custom element (v3). v3 ships
// no JSX typings, so the element is declared in altcha-widget.d.ts. The widget runs
// in self-contained mode from the `challenge` attribute (no challenge server).
import "altcha";
// Register the Norwegian (Bokmål) widget locale. The widget auto-detects the
// language from <html lang>, which Keycloak sets when the realm serves nb.
import "altcha/i18n/nb";

export default function LoginTesseraOtpStart(
    props: PageProps<Extract<KcContext, { pageId: "login-tessera-otp-start.ftl" }>, I18n>
) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { social, url, messagesPerField, altchaChallenge } = kcContext;

    const { msg, msgStr } = i18n;

    const hasError = messagesPerField.existsError("email");
    const hasProviders = social?.providers !== undefined && social.providers.length !== 0;

    // The email path is secondary; reveal it on demand. Open it straight away
    // when there are no identity providers, or when a submitted email errored.
    const [showEmailForm, setShowEmailForm] = useState(!hasProviders || hasError);
    const [isSubmitDisabled, setIsSubmitDisabled] = useState(false);

    // ALTCHA (when enabled): the widget reads the pre-generated challenge from
    // its `challenge` attribute and runs the proof-of-work. v3 has no `auto`
    // HTML attribute, so trigger verify() programmatically to solve on load
    // (invisible — no checkbox click). The checkbox stays as a manual fallback.
    const altchaRef = useRef<HTMLElement | null>(null);
    useEffect(() => {
        // The widget is inside the (initially hidden) email form, so depend on
        // showEmailForm too — the effect must re-run once the form is revealed
        // and the element is actually mounted, otherwise it never auto-solves.
        if (!altchaChallenge || !showEmailForm) return;
        // v3 needs the challenge as a parsed object: a JSON string set via the
        // React property isn't auto-parsed, so verify() can't solve it. Parse
        // once up front — a malformed challenge is unsolvable, so bail.
        let parsedChallenge: unknown;
        try {
            parsedChallenge = JSON.parse(altchaChallenge);
        } catch {
            return;
        }
        const el = altchaRef.current as
            | (HTMLElement & {
                  verify?: () => void;
                  configure?: (opts: { challenge: unknown }) => void;
                  getState?: () => string;
              })
            | null;
        if (!el) return;
        let cancelled = false;
        let configured = false;
        let tries = 0;
        let timer: number | undefined;
        const tick = () => {
            if (cancelled || el.getState?.() === "verified") return;
            // Feed the parsed challenge through configure(), then verify() runs
            // the proof-of-work — no checkbox click needed.
            if (!configured && typeof el.configure === "function") {
                el.configure({ challenge: parsedChallenge });
                configured = true;
            }
            el.verify?.();
            // Retry briefly in case the element hadn't finished upgrading on the
            // first tick. Keep the single latest timer id so cleanup cancels it.
            if (++tries < 8) timer = window.setTimeout(tick, 400);
        };
        timer = window.setTimeout(tick, 150);
        return () => {
            cancelled = true;
            window.clearTimeout(timer);
        };
    }, [altchaChallenge, showEmailForm]);

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={!hasError}
            headerNode={msg("tesseraOtpEmailTitle")}
        >
            <div id="kc-form">
                <div id="kc-form-wrapper">
                    {hasProviders && (
                        <div id="kc-social-providers" className="ratio-login__social">
                            <ul className="ratio-login__social-list">
                                {social!.providers!.map(p => (
                                    <li key={p.alias}>
                                        <a
                                            id={`social-${p.alias}`}
                                            href={p.loginUrl}
                                            className="ratio-login__social-btn"
                                            aria-label={p.displayName}
                                        >
                                            <span
                                                dangerouslySetInnerHTML={{ __html: kcSanitize(p.displayName) }}
                                            />
                                        </a>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}

                    <div className="ratio-login__otp">
                        {hasProviders && <div className="ratio-login__divider">{msg("tesseraOtpOr")}</div>}

                        {!showEmailForm ? (
                            <button
                                type="button"
                                className="ratio-login__textlink"
                                onClick={() => setShowEmailForm(true)}
                            >
                                {msgStr("tesseraOtpUseCode")}
                            </button>
                        ) : (
                            <form
                                id="kc-email-form"
                                className="ratio-login__form"
                                onSubmit={() => {
                                    setIsSubmitDisabled(true);
                                    return true;
                                }}
                                action={url.loginAction}
                                method="post"
                            >
                                <div className="ratio-login__field">
                                    <Label htmlFor="email">{msg("tesseraOtpEmailInstruction")}</Label>
                                    <Input
                                        className="ratio-login__input"
                                        id="email"
                                        name="email"
                                        type="email"
                                        autoFocus
                                        autoComplete="email"
                                        inputMode="email"
                                        aria-invalid={hasError}
                                        aria-describedby={hasError ? "email-error" : undefined}
                                    />
                                    {hasError && (
                                        <span
                                            id="email-error"
                                            className="ratio-login__field-error"
                                            aria-live="polite"
                                            dangerouslySetInnerHTML={{
                                                __html: kcSanitize(messagesPerField.getFirstError("email"))
                                            }}
                                        />
                                    )}
                                </div>

                                {/* ALTCHA captcha — only when the authenticator
                                    supplied a challenge (i.e. an HMAC key is
                                    configured). Its hidden `altcha` field is
                                    submitted with `email`. Absent → no widget,
                                    so the no-captcha case + smoke still render. */}
                                {altchaChallenge && (
                                    <div className="ratio-login__altcha">
                                        <altcha-widget
                                            ref={altchaRef}
                                            challenge={altchaChallenge}
                                            name="altcha"
                                        />
                                    </div>
                                )}

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
                        )}
                    </div>
                </div>
            </div>
        </Template>
    );
}
