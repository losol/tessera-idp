// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/* eslint-disable @typescript-eslint/no-unused-vars */
import { i18nBuilder } from "keycloakify/login";
import type { ThemeName } from "../kc.gen";

/**
 * Norwegian custom one-time-code strings, shared by the "no" (keycloakify default
 * set) and "nb" (Keycloak runtime) language tags. See the note on the builder
 * below for why both tags must be registered.
 */
const norwegianTesseraOtpMessages = {
    tesseraOtpEmailTitle: "Logg inn",
    tesseraOtpEmailInstruction: "Skriv inn e-postadressen din for å få en innloggingskode.",
    tesseraOtpInvalidEmail: "Vennligst skriv inn en gyldig e-postadresse.",
    tesseraOtpTitle: "Skriv inn kode",
    tesseraOtpInstruction: "Vi sendte en kode til {0}. Skriv den inn nedenfor.",
    tesseraOtpRequired: "Vennligst skriv inn koden.",
    tesseraOtpInvalid: "Ugyldig kode. Prøv igjen.",
    tesseraOtpExpired: "Koden har utløpt.",
    tesseraOtpExpiredResent: "Koden har utløpt. Vi har sendt deg en ny.",
    tesseraOtpTooManyAttempts: "For mange feil forsøk. Vennligst be om en ny kode.",
    tesseraOtpThrottled:
        "En kode er allerede sendt. Skriv den inn nedenfor, eller vent litt før du ber om en ny.",
    tesseraOtpSendFailed: "Kunne ikke sende e-post. Prøv igjen senere.",
    tesseraOtpWrongEmail: "Feil e-post? Gå tilbake",
    tesseraOtpUseCode: "Logg inn med engangspassord",
    tesseraOtpOr: "eller",
    altchaRequired: "Vennligst fullfør verifiseringen.",
    altchaFailed: "Verifiseringen mislyktes. Prøv igjen."
};

/**
 * The tessera-otp authenticator ships its message keys in the plugin's
 * theme-resources bundle, which Keycloak merges into the realm bundle at
 * runtime. We register the same keys here so they also resolve in the
 * keycloakify dev/storybook environment and, more importantly, so
 * `msgStr("tesseraOtpInstruction", sentToEmail)` interpolates the `{0}`
 * placeholder reliably. Values mirror the plugin's messages_en/messages_nb
 * .properties files; English and Norwegian must both resolve.
 *
 * Keycloak's Norwegian Bokmål locale uses the tag "nb", but keycloakify's
 * built-in default message set keys Norwegian as "no". When the realm locale
 * is "nb", keycloakify finds no built-in default set for that tag and falls
 * back to its `extraLanguageTranslations` map; if "nb" is missing there it
 * throws ("Wrong assertion encountered") while loading translations, which
 * aborts the React render and leaves a blank login page. We therefore register
 * "nb" via `withExtraLanguages`, sourcing its base translations from
 * keycloakify's "no" default set, and provide the custom Tessera OTP keys under
 * both "no" and "nb".
 *
 * @see: https://docs.keycloakify.dev/features/i18n
 */
const { useI18n, ofTypeI18n } = i18nBuilder
    .withThemeName<ThemeName>()
    .withExtraLanguages({
        nb: {
            label: "Norsk (bokmål)",
            // Source "nb" base translations from keycloakify's built-in "no"
            // set. That set is a partial translation, so merge it over the
            // complete English set to satisfy the full default-message-key
            // contract; untranslated keys fall back to their English text.
            getMessages: async () => {
                const [{ default: en }, { default: no }] = await Promise.all([
                    import("keycloakify/login/i18n/messages_defaultSet/en"),
                    import("keycloakify/login/i18n/messages_defaultSet/no")
                ]);
                return { default: { ...en, ...no } };
            }
        }
    })
    .withCustomTranslations({
        en: {
            tesseraOtpEmailTitle: "Sign in",
            tesseraOtpEmailInstruction: "Enter your email address to receive a login code.",
            tesseraOtpInvalidEmail: "Please enter a valid email address.",
            tesseraOtpTitle: "Enter code",
            tesseraOtpInstruction: "We sent a code to {0}. Enter it below.",
            tesseraOtpRequired: "Please enter the code.",
            tesseraOtpInvalid: "Invalid code. Please try again.",
            tesseraOtpExpired: "The code has expired.",
            tesseraOtpExpiredResent: "The code has expired. We sent you a new one.",
            tesseraOtpTooManyAttempts:
                "Too many incorrect attempts. Please request a new code.",
            tesseraOtpThrottled:
                "A code was already sent. Enter it below, or wait a moment before requesting a new one.",
            tesseraOtpSendFailed: "Failed to send email. Please try again later.",
            tesseraOtpWrongEmail: "Wrong email? Go back",
            tesseraOtpUseCode: "Log in with a one-time code",
            tesseraOtpOr: "or",
            altchaRequired: "Please complete the verification challenge.",
            altchaFailed: "Verification failed. Please try again."
        },
        no: norwegianTesseraOtpMessages,
        nb: norwegianTesseraOtpMessages
    })
    .build();

type I18n = typeof ofTypeI18n;

export { useI18n, type I18n };
