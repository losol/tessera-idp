// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Error page (`error.ftl`). Replaces keycloakify's default so that a failed
 * login ends on a page people can act on: the message, a way back to the
 * application, and a reference support can find in the logs.
 *
 * The reference is Keycloak's OpenTelemetry `traceId`, present when tracing is
 * enabled; it is the same id that prefixes the request's log lines. The time
 * is always shown (UTC, to the second), so a report can be matched against
 * the logs even when there is no trace id.
 */
import { useState } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

/** `2026-10-04 09:43:39 UTC` — the format Keycloak's logs use, minus millis. */
function formatUtc(date: Date): string {
    return `${date.toISOString().slice(0, 19).replace("T", " ")} UTC`;
}

export default function ErrorPage(props: PageProps<Extract<KcContext, { pageId: "error.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { message, client, skipLink, traceId } = kcContext;

    const { msg, msgStr } = i18n;

    // Fixed at first render, so it does not tick and matches when the error was shown.
    const [shownAt] = useState(() => formatUtc(new Date()));
    const [copied, setCopied] = useState(false);

    const copyReference = async () => {
        const text = traceId ? `${traceId} (${shownAt})` : shownAt;
        try {
            await navigator.clipboard.writeText(text);
            setCopied(true);
        } catch {
            // Clipboard can be unavailable (insecure context, denied permission);
            // the reference stays selectable on the page.
        }
    };

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={false}
            headerNode={msg("errorTitle")}
        >
            <div id="kc-error-message" className="ratio-login__error">
                <p
                    className="ratio-login__error-message"
                    dangerouslySetInnerHTML={{ __html: kcSanitize(message.summary) }}
                />

                {!skipLink && client?.baseUrl && (
                    <a id="backToApplication" className="ratio-login__btn ratio-login__btn--primary ratio-login__btn--block" href={client.baseUrl}>
                        {/* The bundled text leads with "« " (as an HTML entity); the button needs no arrow. */}
                        {msgStr("backToApplication").replace(/^(«|&laquo;)\s*/, "")}
                    </a>
                )}

                <div className="ratio-login__error-reference">
                    <dl>
                        {traceId && (
                            <div>
                                <dt>{msg("ratioErrorReference")}</dt>
                                <dd>
                                    <code id="traceId">{traceId}</code>
                                </dd>
                            </div>
                        )}
                        <div>
                            <dt>{msg("ratioErrorTime")}</dt>
                            <dd>
                                <code>{shownAt}</code>
                            </dd>
                        </div>
                    </dl>
                    <p className="ratio-login__error-hint">
                        {traceId ? msg("ratioErrorSupportHint") : msg("ratioErrorSupportHintTimeOnly")}
                    </p>
                    <button type="button" className="ratio-login__textlink" onClick={copyReference}>
                        {copied ? msgStr("ratioErrorCopied") : msgStr("ratioErrorCopy")}
                    </button>
                </div>
            </div>
        </Template>
    );
}
