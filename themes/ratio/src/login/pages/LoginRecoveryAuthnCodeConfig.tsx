// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-FileCopyrightText: 2020 GitHub user u/garronej (keycloakify)
// SPDX-License-Identifier: MPL-2.0 AND MIT

/**
 * Recovery-code setup page (login-recovery-authn-code-config.ftl).
 *
 * keycloakify's own page, with its Print, Download and Copy scripts turned
 * into click handlers and one fix. Print writes a sheet into a new window as
 * HTML, and keycloakify puts three messages into it as they are: markup in
 * one is parsed there, and its scripts run with the login page's origin.
 * Client texts (`ratio.text.*`, see clientTexts.tsx) can replace those
 * messages and must stay plain text, so the sheet escapes them. The
 * downloaded file is plain text and keeps them as written.
 *
 * Markup, ids and the form are keycloakify's, so the Keycloak flow and the
 * page's look are unchanged.
 */
import { useRef, useState } from "react";
import { getKcClsx, type KcClsx } from "keycloakify/login/lib/kcClsx";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { clsx } from "keycloakify/tools/clsx";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

/** Text going into the print sheet, which is HTML. */
function escapeHtml(text: string): string {
    return text.replace(/[&<>"']/g, char => `&#${char.charCodeAt(0)};`);
}

function formatCurrentDateTime(): string {
    return new Date().toLocaleString("en-US", {
        month: "long",
        day: "numeric",
        year: "numeric",
        hour: "numeric",
        minute: "numeric",
        timeZoneName: "short"
    });
}

const PRINT_STYLES = `@page { size: auto;  margin-top: 0; }
    body { width: 480px; }
    div { list-style-type: none; font-family: monospace }
    p:first-of-type { margin-top: 48px }`;

export default function LoginRecoveryAuthnCodeConfig(
    props: PageProps<Extract<KcContext, { pageId: "login-recovery-authn-code-config.ftl" }>, I18n>
) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { kcClsx } = getKcClsx({
        doUseDefaultCss,
        classes
    });

    const { recoveryAuthnCodesConfigBean, isAppInitiatedAction } = kcContext;

    const { msg, msgStr } = i18n;

    const codesList = useRef<HTMLOListElement>(null);
    const [isConfirmed, setIsConfirmed] = useState(false);

    // "1: abcd-efgh-ijkl", one per code, as shown.
    const codeLines = () => Array.from(codesList.current?.querySelectorAll("li") ?? [], li => li.innerText);

    const copyRecoveryCodes = () => {
        const textarea = document.createElement("textarea");
        textarea.value = codeLines()
            .map(line => `${line}\n`)
            .join("");
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand("copy");
        document.body.removeChild(textarea);
    };

    const downloadRecoveryCodes = () => {
        const content =
            msgStr("recovery-codes-download-file-header") +
            "\n\n" +
            codeLines()
                .map(line => `${line}\r\n`)
                .join("") +
            "\n" +
            msgStr("recovery-codes-download-file-description") +
            "\n\n" +
            msgStr("recovery-codes-download-file-date") +
            " " +
            formatCurrentDateTime();

        const link = document.createElement("a");
        link.setAttribute("href", "data:text/plain;charset=utf-8," + encodeURIComponent(content));
        link.setAttribute("download", "kc-download-recovery-codes.txt");
        link.style.display = "none";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    };

    const printRecoveryCodes = () => {
        const sheet = window.open();
        if (sheet === null) return;
        // The codes list is the page's own markup, which React already escaped.
        sheet.document.write(
            "<html><style>" +
                PRINT_STYLES +
                "</style><body>" +
                "<title>kc-download-recovery-codes</title>" +
                "<p>" +
                escapeHtml(msgStr("recovery-codes-download-file-header")) +
                "</p>" +
                "<div>" +
                (codesList.current?.innerHTML ?? "") +
                "</div>" +
                "<p>" +
                escapeHtml(msgStr("recovery-codes-download-file-description")) +
                "</p>" +
                "<p>" +
                escapeHtml(msgStr("recovery-codes-download-file-date")) +
                " " +
                formatCurrentDateTime() +
                "</p>" +
                "</body></html>"
        );
        sheet.print();
        sheet.close();
    };

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            headerNode={msg("recovery-code-config-header")}
        >
            <div className={clsx("pf-c-alert", "pf-m-warning", "pf-m-inline", kcClsx("kcRecoveryCodesWarning"))} aria-label="Warning alert">
                <div className="pf-c-alert__icon">
                    <i className="pficon-warning-triangle-o" aria-hidden="true" />
                </div>
                <h4 className="pf-c-alert__title">
                    <span className="pf-screen-reader">Warning alert:</span>
                    {msg("recovery-code-config-warning-title")}
                </h4>
                <div className="pf-c-alert__description">
                    <p>{msg("recovery-code-config-warning-message")}</p>
                </div>
            </div>

            <ol id="kc-recovery-codes-list" ref={codesList} className={kcClsx("kcRecoveryCodesList")}>
                {recoveryAuthnCodesConfigBean.generatedRecoveryAuthnCodesList.map((code, index) => (
                    <li key={index}>
                        <span>{index + 1}:</span> {code.slice(0, 4)}-{code.slice(4, 8)}-{code.slice(8)}
                    </li>
                ))}
            </ol>

            {/* actions */}
            <div className={kcClsx("kcRecoveryCodesActions")}>
                <button id="printRecoveryCodes" className={clsx("pf-c-button", "pf-m-link")} type="button" onClick={printRecoveryCodes}>
                    <i className="pficon-print" aria-hidden="true" /> {msg("recovery-codes-print")}
                </button>
                <button id="downloadRecoveryCodes" className={clsx("pf-c-button", "pf-m-link")} type="button" onClick={downloadRecoveryCodes}>
                    <i className="pficon-save" aria-hidden="true" /> {msg("recovery-codes-download")}
                </button>
                <button id="copyRecoveryCodes" className={clsx("pf-c-button", "pf-m-link")} type="button" onClick={copyRecoveryCodes}>
                    <i className="pficon-blueprint" aria-hidden="true" /> {msg("recovery-codes-copy")}
                </button>
            </div>

            {/* confirmation checkbox */}
            <div className={kcClsx("kcFormOptionsClass")}>
                <input
                    className={kcClsx("kcCheckInputClass")}
                    type="checkbox"
                    id="kcRecoveryCodesConfirmationCheck"
                    name="kcRecoveryCodesConfirmationCheck"
                    onChange={event => setIsConfirmed(event.target.checked)}
                />
                <label htmlFor="kcRecoveryCodesConfirmationCheck">{msg("recovery-codes-confirmation-message")}</label>
            </div>

            <form action={kcContext.url.loginAction} className={kcClsx("kcFormGroupClass")} id="kc-recovery-codes-settings-form" method="post">
                <input type="hidden" name="generatedRecoveryAuthnCodes" value={recoveryAuthnCodesConfigBean.generatedRecoveryAuthnCodesAsString} />
                <input type="hidden" name="generatedAt" value={recoveryAuthnCodesConfigBean.generatedAt} />
                <input type="hidden" id="userLabel" name="userLabel" value={msgStr("recovery-codes-label-default")} />

                <LogoutOtherSessions kcClsx={kcClsx} i18n={i18n} />

                {isAppInitiatedAction ? (
                    <>
                        <input
                            type="submit"
                            className={kcClsx("kcButtonClass", "kcButtonPrimaryClass", "kcButtonLargeClass")}
                            id="saveRecoveryAuthnCodesBtn"
                            value={msgStr("recovery-codes-action-complete")}
                            disabled={!isConfirmed}
                        />
                        <button
                            type="submit"
                            className={kcClsx("kcButtonClass", "kcButtonDefaultClass", "kcButtonLargeClass")}
                            id="cancelRecoveryAuthnCodesBtn"
                            name="cancel-aia"
                            value="true"
                        >
                            {msg("recovery-codes-action-cancel")}
                        </button>
                    </>
                ) : (
                    <input
                        type="submit"
                        className={kcClsx("kcButtonClass", "kcButtonPrimaryClass", "kcButtonBlockClass", "kcButtonLargeClass")}
                        id="saveRecoveryAuthnCodesBtn"
                        value={msgStr("recovery-codes-action-complete")}
                        disabled={!isConfirmed}
                    />
                )}
            </form>
        </Template>
    );
}

function LogoutOtherSessions(props: { kcClsx: KcClsx; i18n: I18n }) {
    const { kcClsx, i18n } = props;

    const { msg } = i18n;

    return (
        <div id="kc-form-options" className={kcClsx("kcFormOptionsClass")}>
            <div className={kcClsx("kcFormOptionsWrapperClass")}>
                <div className="checkbox">
                    <label>
                        <input type="checkbox" id="logout-sessions" name="logout-sessions" value="on" />
                        {msg("logoutOtherSessions")}
                    </label>
                </div>
            </div>
        </div>
    );
}
