// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

import { useEffect, type ReactNode } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { TemplateProps } from "keycloakify/login/TemplateProps";
import { useInitialize } from "keycloakify/login/Template.useInitialize";
import { Card } from "@eventuras/ratio-ui/core/Card";
import { Heading } from "@eventuras/ratio-ui/core/Heading";
import { Text } from "@eventuras/ratio-ui/core/Text";
import type { I18n } from "./i18n";
import type { KcContext } from "./KcContext";
import { HELP_URL_ATTRIBUTE, PRIVACY_URL_ATTRIBUTE, resolveLayout, safeUrl } from "./layout";

/**
 * Shell wrapping every login page. Replaces keycloakify's default
 * (PatternFly-based) template with Ratio-styled layouts, chosen per client
 * with the `ratio.layout` attribute (see layout.ts):
 *
 * - `split`  — a brand panel beside the form; a band above it on phones (the default).
 * - `card`   — a centred card.
 * - `column` — one calm column under a slim top bar, no card.
 * - `panel`  — the form in an edge panel, the service you sign in to beside it.
 *
 * Every layout renders the same parts: the page title, the message alert,
 * the page's own content, "try another way", social providers and info. The
 * element holding them keeps the `ratio-login__card` class in every layout.
 *
 * `doUseDefaultCss` is forced off by KcPage so none of Keycloak's stock
 * stylesheets load; styling comes entirely from the Ratio bundle imported
 * in `main.css`. The keycloakify initialization hook is still used so the
 * required Keycloak scripts (session polling, auth checker) are injected.
 */
export default function Template(props: TemplateProps<KcContext, I18n>) {
    const {
        displayInfo = false,
        displayMessage = true,
        headerNode,
        socialProvidersNode = null,
        infoNode = null,
        documentTitle,
        bodyClassName,
        kcContext,
        i18n,
        doUseDefaultCss,
        children
    } = props;

    const { msg, msgStr, advancedMsgStr, currentLanguage, enabledLanguages } = i18n;

    const { realm, auth, url, message, isAppInitiatedAction, client } = kcContext;

    const realmLabel = realm.displayName || realm.name;

    useEffect(() => {
        document.title = documentTitle ?? msgStr("loginTitle", realmLabel);
    }, []);

    useEffect(() => {
        // Apply Ratio surface styling to the page chrome so the layout sits
        // on the design-system background instead of the browser default.
        document.documentElement.classList.add("ratio-login");
        document.body.classList.add("ratio-login__body");
        if (bodyClassName) {
            document.body.classList.add(bodyClassName);
        }
    }, []);

    const { isReadyToRender } = useInitialize({ kcContext, doUseDefaultCss });

    if (!isReadyToRender) {
        return null;
    }

    // Some pages render without a client (an unknown realm, some errors).
    const attributes: Record<string, string> | undefined = client?.attributes;
    const layout = resolveLayout(attributes);

    // Optional texts are empty by default and left out until a client sets
    // them (`ratio.text.<key>`, see clientTexts.tsx).
    const optional = (text: string) => (text.trim() === "" ? undefined : text);
    const eyebrow = optional(msgStr("ratioBrandEyebrow"));
    const headline = msgStr("ratioBrandHeadline");
    const lead = optional(msgStr("ratioBrandLead"));
    const note = optional(msgStr("ratioFooterNote"));

    // The client's name and description may be `${key}` references to realm
    // localization; keycloakify resolves those for us.
    const clientName = client?.name ? optional(advancedMsgStr(client.name)) : undefined;
    const clientDescription = client?.description ? optional(advancedMsgStr(client.description)) : undefined;
    const clientUrl = safeUrl((client as { baseUrl?: string } | undefined)?.baseUrl);
    const helpUrl = safeUrl(attributes?.[HELP_URL_ATTRIBUTE]);
    const privacyUrl = safeUrl(attributes?.[PRIVACY_URL_ATTRIBUTE]);

    const locales = enabledLanguages.length > 1 && (
        <nav className="ratio-login__locales" aria-label={msgStr("languages")}>
            <ul>
                {enabledLanguages.map(({ languageTag, label, href }) => (
                    <li key={languageTag}>
                        <a href={href} aria-current={languageTag === currentLanguage.languageTag ? "true" : undefined}>
                            {label}
                        </a>
                    </li>
                ))}
            </ul>
        </nav>
    );

    const wordmark = <p className="ratio-login__wordmark">{realmLabel}</p>;

    const title = (
        <Heading as={layout === "card" ? "h2" : "h1"} className="ratio-login__title">
            {headerNode}
        </Heading>
    );

    const content = (
        <div id="kc-content">
            <div id="kc-content-wrapper">
                {/* App-initiated actions should not see warning messages about the need to complete the action during login. */}
                {displayMessage && message !== undefined && (message.type !== "warning" || !isAppInitiatedAction) && (
                    <div className={`ratio-login__alert ratio-login__alert--${message.type}`} role="alert">
                        {/* Keycloak message summaries may contain markup; render sanitized. */}
                        <span
                            dangerouslySetInnerHTML={{
                                __html: kcSanitize(message.summary)
                            }}
                        />
                    </div>
                )}

                {children}

                {auth !== undefined && auth.showTryAnotherWayLink && (
                    <form id="kc-select-try-another-way-form" action={url.loginAction} method="post">
                        <input type="hidden" name="tryAnotherWay" value="on" />
                        <a
                            href="#"
                            id="try-another-way"
                            onClick={event => {
                                event.preventDefault();
                                const form = document.getElementById("kc-select-try-another-way-form");
                                if (form instanceof HTMLFormElement) {
                                    form.requestSubmit();
                                }
                            }}
                        >
                            {msg("doTryAnotherWay")}
                        </a>
                    </form>
                )}

                {socialProvidersNode}

                {displayInfo && (
                    <div id="kc-info" className="ratio-login__info">
                        <div id="kc-info-wrapper">{infoNode}</div>
                    </div>
                )}
            </div>
        </div>
    );

    const footer = (extra: ReactNode) => (
        <footer className="ratio-login__footer">
            {extra}
            {(helpUrl || privacyUrl) && (
                <ul className="ratio-login__links">
                    {helpUrl && (
                        <li>
                            <a href={helpUrl}>{msg("ratioHelp")}</a>
                        </li>
                    )}
                    {privacyUrl && (
                        <li>
                            <a href={privacyUrl}>{msg("ratioPrivacy")}</a>
                        </li>
                    )}
                </ul>
            )}
        </footer>
    );

    const noteText = note && <p className="ratio-login__note">{note}</p>;

    switch (layout) {
        case "split":
            return (
                <div className="ratio-login__page ratio-login__page--split">
                    <aside className="ratio-login__brand-panel">
                        <svg className="ratio-login__rings" viewBox="0 0 400 400" fill="none" aria-hidden="true">
                            <circle cx="200" cy="200" r="60" />
                            <circle cx="200" cy="200" r="100" />
                            <circle cx="200" cy="200" r="140" />
                            <circle cx="200" cy="200" r="180" />
                        </svg>
                        {wordmark}
                        <div className="ratio-login__brand-copy">
                            {eyebrow && <p className="ratio-login__eyebrow">{eyebrow}</p>}
                            <p className="ratio-login__headline">{headline}</p>
                            {lead && <p className="ratio-login__lead">{lead}</p>}
                        </div>
                        {noteText}
                    </aside>
                    <div className="ratio-login__main">
                        <div className="ratio-login__topbar">{locales}</div>
                        <main className="ratio-login__card ratio-login__card--flat">
                            <header className="ratio-login__header ratio-login__header--start">{title}</header>
                            {content}
                        </main>
                        {footer(null)}
                    </div>
                </div>
            );

        case "column":
            return (
                <div className="ratio-login__page ratio-login__page--column">
                    <header className="ratio-login__topbar ratio-login__topbar--bar">
                        {wordmark}
                        {locales}
                    </header>
                    <main className="ratio-login__card ratio-login__card--flat ratio-login__card--wide">
                        <header className="ratio-login__header ratio-login__header--start">
                            {(eyebrow ?? clientName) && <p className="ratio-login__eyebrow">{eyebrow ?? clientName}</p>}
                            {title}
                            {lead && <p className="ratio-login__lead">{lead}</p>}
                        </header>
                        {content}
                    </main>
                    {footer(noteText)}
                </div>
            );

        case "panel":
            return (
                <div className="ratio-login__page ratio-login__page--panel">
                    <div className="ratio-login__side">
                        <div className="ratio-login__topbar">
                            {wordmark}
                            {locales}
                        </div>
                        <main className="ratio-login__card ratio-login__card--flat">
                            <header className="ratio-login__header ratio-login__header--start">{title}</header>
                            {content}
                        </main>
                        {footer(null)}
                    </div>
                    <section className="ratio-login__context" aria-label={clientName ?? realmLabel}>
                        {clientName ? (
                            <>
                                <p className="ratio-login__eyebrow">{msg("ratioSigningInTo")}</p>
                                <p className="ratio-login__context-name">{clientName}</p>
                                {clientDescription && <p className="ratio-login__lead">{clientDescription}</p>}
                                {clientUrl && (
                                    <a className="ratio-login__context-link" href={clientUrl}>
                                        {msgStr("ratioBackTo", clientName)}
                                    </a>
                                )}
                            </>
                        ) : (
                            <>
                                {eyebrow && <p className="ratio-login__eyebrow">{eyebrow}</p>}
                                <p className="ratio-login__context-name">{headline}</p>
                                {lead && <p className="ratio-login__lead">{lead}</p>}
                            </>
                        )}
                        {noteText}
                    </section>
                </div>
            );

        default: // "card"
            return (
                <div className="ratio-login__page ratio-login__page--card">
                    <Card as="main" className="ratio-login__card" shadow="md">
                        <header className="ratio-login__header">
                            {realmLabel && (
                                <Heading as="h1" className="ratio-login__brand">
                                    {realmLabel}
                                </Heading>
                            )}
                            {locales}
                            {title}
                        </header>
                        {content}
                    </Card>

                    {footer(
                        <>
                            <Text size="xs" variant="subtle">
                                {realmLabel}
                            </Text>
                            {noteText}
                        </>
                    )}
                </div>
            );
    }
}
