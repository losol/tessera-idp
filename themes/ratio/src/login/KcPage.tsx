// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

import "./main.css";
import { Suspense, lazy } from "react";
import type { ClassKey } from "keycloakify/login";
import type { KcContext } from "./KcContext";
import { useI18n } from "./i18n";
import DefaultPage from "keycloakify/login/DefaultPage";
import Template from "./Template";
const UserProfileFormFields = lazy(
    () => import("keycloakify/login/UserProfileFormFields")
);
const Login = lazy(() => import("./pages/Login"));
const LoginTesseraOtpStart = lazy(() => import("./pages/LoginTesseraOtpStart"));
const LoginTesseraOtpCode = lazy(() => import("./pages/LoginTesseraOtpCode"));

// Ratio's base CSS hides <html> (opacity: 0) until a data-theme is set; that
// attribute also activates the light/dark design tokens (--primary, --text, …).
// Set it at module load — before first paint — so every login page is visible
// and themed from the start. Without it the page renders but stays invisible.
if (typeof document !== "undefined") {
    document.documentElement.dataset.theme ??= "light";
}

const doMakeUserConfirmPassword = true;

export default function KcPage(props: { kcContext: KcContext }) {
    const { kcContext } = props;

    const { i18n } = useI18n({ kcContext });

    return (
        <Suspense>
            {(() => {
                switch (kcContext.pageId) {
                    case "login.ftl":
                        return (
                            <Login
                                kcContext={kcContext}
                                i18n={i18n}
                                classes={classes}
                                Template={Template}
                                doUseDefaultCss={false}
                            />
                        );
                    case "login-tessera-otp-start.ftl":
                        return (
                            <LoginTesseraOtpStart
                                kcContext={kcContext}
                                i18n={i18n}
                                classes={classes}
                                Template={Template}
                                doUseDefaultCss={false}
                            />
                        );
                    case "login-tessera-otp-code.ftl":
                        return (
                            <LoginTesseraOtpCode
                                kcContext={kcContext}
                                i18n={i18n}
                                classes={classes}
                                Template={Template}
                                doUseDefaultCss={false}
                            />
                        );
                    default:
                        return (
                            <DefaultPage
                                kcContext={kcContext}
                                i18n={i18n}
                                classes={classes}
                                Template={Template}
                                doUseDefaultCss={false}
                                UserProfileFormFields={UserProfileFormFields}
                                doMakeUserConfirmPassword={doMakeUserConfirmPassword}
                            />
                        );
                }
            })()}
        </Suspense>
    );
}

/**
 * Maps keycloakify's `ClassKey`s to Ratio-styled `ratio-login__*` classes.
 *
 * The custom pages (Login, the Tessera OTP pages) and the custom Template
 * supply their own markup and ignore this map. It exists for the pages that
 * fall through to keycloakify's `DefaultPage` (update-profile, update-password,
 * info, error, …): with `doUseDefaultCss={false}` those pages render Keycloak's
 * stock markup but apply no classes unless mapped here. The keys below cover
 * the elements those pages render — inputs, labels, error text, form layout
 * and the submit/primary button — so they match the look of the custom pages.
 *
 * Button classes are additive: keycloakify joins `kcButtonClass` with the
 * primary/block/large modifiers, so the base carries the shared button shape
 * and the modifiers layer on fill, full-width and sizing.
 */
const classes = {
    // Text/email/password inputs, textareas and selects.
    kcInputClass: "ratio-login__input",
    kcTextareaClass: "ratio-login__input",
    // Labels and the wrappers around each field.
    kcLabelClass: "ratio-login__label",
    kcLabelWrapperClass: "ratio-login__field",
    kcInputWrapperClass: "ratio-login__input-wrapper",
    kcFormGroupClass: "ratio-login__field",
    // Inline validation/error text.
    kcInputErrorMessageClass: "ratio-login__field-error",
    // Helper text rendered before/after a field.
    kcInputHelperTextBeforeClass: "ratio-login__field-help",
    kcInputHelperTextAfterClass: "ratio-login__field-help",
    // Form and button layout containers.
    kcFormClass: "ratio-login__form",
    kcFormGroupHeader: "ratio-login__field-group-header",
    kcFormButtonsClass: "ratio-login__buttons",
    kcFormOptionsClass: "ratio-login__form-options",
    // Buttons. Base shape + additive modifiers (primary fill, full-width).
    kcButtonClass: "ratio-login__btn",
    kcButtonPrimaryClass: "ratio-login__btn--primary",
    kcButtonSecondaryClass: "ratio-login__btn--secondary",
    kcButtonDefaultClass: "ratio-login__btn--secondary",
    kcButtonBlockClass: "ratio-login__btn--block",
    // Password show/hide toggle reused from the custom login styling.
    kcInputGroup: "ratio-login__password-group",
    kcFormPasswordVisibilityButtonClass: "ratio-login__password-toggle"
} satisfies { [key in ClassKey]?: string };
