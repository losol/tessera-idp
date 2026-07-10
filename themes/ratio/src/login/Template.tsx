// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

import { useEffect } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { TemplateProps } from "keycloakify/login/TemplateProps";
import { useInitialize } from "keycloakify/login/Template.useInitialize";
import { Card } from "@eventuras/ratio-ui/core/Card";
import { Heading } from "@eventuras/ratio-ui/core/Heading";
import { Text } from "@eventuras/ratio-ui/core/Text";
import type { I18n } from "./i18n";
import type { KcContext } from "./KcContext";

/**
 * Shell wrapping every login page. Replaces keycloakify's default
 * (PatternFly-based) template with a centered, Ratio-styled card layout.
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

    const { msg, msgStr, currentLanguage, enabledLanguages } = i18n;

    const { realm, auth, url, message, isAppInitiatedAction } = kcContext;

    const realmLabel = realm.displayName || realm.name;

    useEffect(() => {
        document.title = documentTitle ?? msgStr("loginTitle", realmLabel);
    }, []);

    useEffect(() => {
        // Apply Ratio surface styling to the page chrome so the card sits
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

    return (
        <div className="ratio-login__page">
            <Card as="main" className="ratio-login__card" shadow="md">
                <header className="ratio-login__header">
                    {realmLabel && (
                        <Heading as="h1" className="ratio-login__brand">
                            {realmLabel}
                        </Heading>
                    )}
                    {enabledLanguages.length > 1 && (
                        <nav className="ratio-login__locales" aria-label={msgStr("languages")}>
                            <ul>
                                {enabledLanguages.map(({ languageTag, label, href }) => (
                                    <li key={languageTag}>
                                        <a
                                            href={href}
                                            aria-current={languageTag === currentLanguage.languageTag ? "true" : undefined}
                                        >
                                            {label}
                                        </a>
                                    </li>
                                ))}
                            </ul>
                        </nav>
                    )}
                    <Heading as="h2" className="ratio-login__title">
                        {headerNode}
                    </Heading>
                </header>

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
            </Card>

            <footer className="ratio-login__footer">
                <Text size="xs" variant="subtle">
                    {realmLabel}
                </Text>
            </footer>
        </div>
    );
}
