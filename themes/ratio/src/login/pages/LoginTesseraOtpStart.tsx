// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Email-entry page for the passwordless login flow (custom page
 * `login-tessera-otp-start.ftl`, rendered by the tessera-otp authenticator).
 *
 * Default layout (no `tessera.login-methods` on the client): the identity
 * providers (ordered by guiOrder) are the primary choice, and the email
 * one-time-code path is secondary — a text link ("Log in with a one-time code")
 * reveals the email field on demand.
 *
 * When the client lists its login methods (see ../loginMethods.ts), only those
 * are offered, all on the same page, and the first one leads: a lead provider
 * gets the filled primary button, a lead `otp` puts the open email form on top
 * with the providers below the divider.
 *
 * The form POSTs a single `email` field to `url.loginAction` — that field name
 * is the contract the Java authenticator reads, so it must not change.
 */
import { useState, useRef, useEffect } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { Button } from "@eventuras/ratio-ui/core/Button";
import { Input } from "@eventuras/ratio-ui/forms";
import { Label } from "@eventuras/ratio-ui/forms";
import type { KcContext } from "../KcContext";
import { resolveLoginMethods, type Provider } from "../loginMethods";
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

    const { social, client, url, messagesPerField, altchaChallenge } = kcContext;

    const { msg, msgStr } = i18n;

    const hasError = messagesPerField.existsError("email");

    // null → default page (every realm provider, email code behind the link).
    const methods = resolveLoginMethods(client.attributes, social?.providers);
    const lead = methods?.[0];
    const leadIsOtp = lead?.kind === "otp";
    const offersOtp = methods === null || methods.some(m => m.kind === "otp");
    const providers: Provider[] =
        methods === null
            ? (social?.providers ?? [])
            : methods.flatMap(m => (m.kind === "idp" ? [m.provider] : []));
    const leadAlias = lead?.kind === "idp" ? lead.provider.alias : undefined;
    const hasProviders = providers.length !== 0;

    // The email path is secondary unless it leads; reveal it on demand. Open it
    // straight away when it leads, when there are no identity providers to
    // offer, or when a submitted email errored.
    const [showEmailForm, setShowEmailForm] = useState(leadIsOtp || !hasProviders || hasError);
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

    const providerList = hasProviders && (
        <div id="kc-social-providers" className="ratio-login__social">
            <ul className="ratio-login__social-list">
                {providers.map(p => (
                    <li key={p.alias}>
                        <a
                            id={`social-${p.alias}`}
                            href={p.loginUrl}
                            className={
                                p.alias === leadAlias
                                    ? "ratio-login__social-btn ratio-login__social-btn--lead"
                                    : "ratio-login__social-btn"
                            }
                            aria-label={p.displayName}
                        >
                            <span dangerouslySetInnerHTML={{ __html: kcSanitize(p.displayName) }} />
                        </a>
                    </li>
                ))}
            </ul>
        </div>
    );

    const emailForm = (
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

            {/* ALTCHA captcha — only when the authenticator supplied a
                challenge (i.e. an HMAC key is configured). Its hidden `altcha`
                field is submitted with `email`. Absent → no widget, so the
                no-captcha case + smoke still render. */}
            {altchaChallenge && (
                <div className="ratio-login__altcha">
                    <altcha-widget ref={altchaRef} challenge={altchaChallenge} name="altcha" />
                </div>
            )}

            <Button disabled={isSubmitDisabled} variant="primary" block name="login" id="kc-login" type="submit">
                {msgStr("doSubmit")}
            </Button>
        </form>
    );

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            // The field error is shown inline under the email input; when this
            // client hides the email form, let the Template show it instead.
            displayMessage={!hasError || !offersOtp}
            headerNode={msg("tesseraOtpEmailTitle")}
        >
            <div id="kc-form">
                <div id="kc-form-wrapper">
                    {leadIsOtp ? (
                        <>
                            {emailForm}
                            {hasProviders && (
                                <div className="ratio-login__alternatives">
                                    <div className="ratio-login__divider">{msg("tesseraOtpOr")}</div>
                                    {providerList}
                                </div>
                            )}
                        </>
                    ) : (
                        <>
                            {providerList}
                            {offersOtp && (
                                <div className="ratio-login__otp">
                                    {hasProviders && (
                                        <div className="ratio-login__divider">{msg("tesseraOtpOr")}</div>
                                    )}
                                    {!showEmailForm ? (
                                        <button
                                            type="button"
                                            className="ratio-login__textlink"
                                            onClick={() => setShowEmailForm(true)}
                                        >
                                            {msgStr("tesseraOtpUseCode")}
                                        </button>
                                    ) : (
                                        emailForm
                                    )}
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>
        </Template>
    );
}
