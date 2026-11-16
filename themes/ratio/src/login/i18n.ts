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
    tesseraContinueWith: "Fortsett med {0}",
    altchaRequired: "Vennligst fullfør verifiseringen.",
    altchaFailed: "Verifiseringen mislyktes. Prøv igjen.",
    ratioErrorReference: "Referanse",
    ratioErrorTime: "Tidspunkt",
    ratioErrorSupportHint: "Kontakter du support, ta med referansen og tidspunktet.",
    ratioErrorSupportHintTimeOnly: "Kontakter du support, ta med tidspunktet.",
    ratioErrorCopy: "Kopier",
    ratioErrorCopied: "Kopiert",
    // Layouts (Template.tsx). Empty texts are left out of the page; set them
    // per client with `ratio.text.<key>` to show them.
    ratioBrandEyebrow: "",
    ratioBrandHeadline: "Én innlogging til alle tjenestene dine.",
    ratioBrandLead: "",
    ratioFooterNote: "",
    ratioSigningInTo: "Du logger inn på",
    ratioBackTo: "Tilbake til {0}",
    ratioHelp: "Hjelp",
    ratioPrivacy: "Personvern",
    // Passkeys (docs/passkeys.md). The keys Keycloak's own WebAuthn scripts
    // read have no Norwegian in keycloakify's set, so they are given here.
    ratioPasskeyLogin: "Logg inn med passnøkkel",
    ratioPasskeyHint: "Fingeravtrykk, ansikt eller PIN på enheten din.",
    ratioPasskeyOtherWay: "Prøv en annen måte",
    ratioPasskeyChooseTitle: "Hvordan vil du logge inn?",
    ratioPasskeyRegisterTitle: "Logg inn raskere neste gang?",
    ratioPasskeyRegisterLead: "Lag en passnøkkel på denne enheten, så slipper du koden på e-post.",
    ratioPasskeyPoint1: "Du bruker fingeravtrykk, ansikt eller PIN, som du allerede gjør for å låse opp enheten.",
    ratioPasskeyPoint2: "Passnøkkelen kan ikke gjettes eller lures ut av deg, og den ligger i nøkkelringen din.",
    ratioPasskeyPoint3: "Mister du enheten, logger du inn med e-postkode som før.",
    ratioPasskeyCreate: "Lag passnøkkel",
    ratioPasskeyNotNow: "Ikke nå",
    ratioPasskeyLater: "Du kan lage en senere under Min konto.",
    ratioPasskeyErrorTitle: "Det gikk ikke med passnøkkel",
    ratioPasskeyErrorBody:
        "Du kan prøve igjen, eller få en kode på e-post. Når du er inne, kan du lage en passnøkkel på denne enheten.",
    ratioPasskeyEmailCode: "Få kode på e-post",
    ratioPasskeyTryAgain: "Prøv passnøkkel igjen",
    "webauthn-unsupported-browser-text": "Nettleseren støtter ikke passnøkler. Prøv en annen nettleser.",
    "webauthn-registration-init-label": "Passnøkkel",
    "webauthn-registration-init-label-prompt": "Gi passnøkkelen et navn"
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
            tesseraContinueWith: "Continue with {0}",
            altchaRequired: "Please complete the verification challenge.",
            altchaFailed: "Verification failed. Please try again.",
            ratioErrorReference: "Reference",
            ratioErrorTime: "Time",
            ratioErrorSupportHint: "If you contact support, include the reference and the time.",
            ratioErrorSupportHintTimeOnly: "If you contact support, include the time.",
            ratioErrorCopy: "Copy",
            ratioErrorCopied: "Copied",
            ratioBrandEyebrow: "",
            ratioBrandHeadline: "One sign-in for all your services.",
            ratioBrandLead: "",
            ratioFooterNote: "",
            ratioSigningInTo: "You are signing in to",
            ratioBackTo: "Back to {0}",
            ratioHelp: "Help",
            ratioPrivacy: "Privacy",
            ratioPasskeyLogin: "Sign in with passkey",
            ratioPasskeyHint: "Fingerprint, face or PIN on your device.",
            ratioPasskeyOtherWay: "Try another way",
            ratioPasskeyChooseTitle: "How do you want to sign in?",
            ratioPasskeyRegisterTitle: "Sign in faster next time?",
            ratioPasskeyRegisterLead: "Create a passkey on this device and skip the code by email.",
            ratioPasskeyPoint1: "You use your fingerprint, face or PIN, as you already do to unlock the device.",
            ratioPasskeyPoint2: "A passkey cannot be guessed or tricked out of you, and it stays in your keychain.",
            ratioPasskeyPoint3: "Lose the device, and you sign in with a code by email as before.",
            ratioPasskeyCreate: "Create passkey",
            ratioPasskeyNotNow: "Not now",
            ratioPasskeyLater: "You can create one later under My account.",
            ratioPasskeyErrorTitle: "That did not work with the passkey",
            ratioPasskeyErrorBody:
                "You can try again or get a code by email. Once you are in, you can create a passkey on this device.",
            ratioPasskeyEmailCode: "Get a code by email",
            ratioPasskeyTryAgain: "Try the passkey again",
            "webauthn-unsupported-browser-text": "This browser does not support passkeys. Try another browser.",
            "webauthn-registration-init-label": "Passkey",
            "webauthn-registration-init-label-prompt": "Name this passkey"
        },
        no: norwegianTesseraOtpMessages,
        nb: norwegianTesseraOtpMessages
    })
    .build();

type I18n = typeof ofTypeI18n;

export { useI18n, type I18n };
