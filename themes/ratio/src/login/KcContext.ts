// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/* eslint-disable @typescript-eslint/no-empty-object-type */
import type { ExtendKcContext } from "keycloakify/login";
import type { KcEnvName, ThemeName } from "../kc.gen";

export type KcContextExtension = {
    themeName: ThemeName;
    properties: Record<KcEnvName, string> & {};
    // NOTE: Here you can declare more properties to extend the KcContext
    // See: https://docs.keycloakify.dev/faq-and-help/some-values-you-need-are-missing-from-in-kccontext
};

export type KcContextExtensionPerPage = {
    // Custom pages rendered by the tessera-otp authenticator SPI. The
    // `url.loginAction`, `messagesPerField` and `message` fields come from
    // KcContext.Common; only the authenticator-specific extras are declared
    // here. Field names below must match the POST contract the Java
    // authenticator expects (`email` / `otp-code`).
    "login-tessera-otp-start.ftl": {
        // Identity providers, pre-ordered by guiOrder (Vipps, then GitHub).
        social: {
            providers?: {
                loginUrl: string;
                alias: string;
                providerId: string;
                displayName: string;
                iconClasses?: string;
            }[];
        };
        // ALTCHA proof-of-work captcha. Set by the authenticator ONLY when an
        // HMAC key is configured: `altchaChallenge` is the server-generated
        // challenge JSON passed to the <altcha-widget> in self-contained mode.
        // Both are absent when the captcha is disabled, so the page must render
        // fine without them (the no-captcha case + smoke).
        altchaChallenge?: string;
        altchaEnabled?: string;
    };
    "login-tessera-otp-code.ftl": {
        // Full address the one-time code was sent to, shown so the user can
        // confirm they typed it correctly. Rendered as escaped React text.
        sentToEmail?: string;
    };
};

export type KcContext = ExtendKcContext<KcContextExtension, KcContextExtensionPerPage>;
