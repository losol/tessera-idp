// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Per-client texts. Any message the theme shows can be replaced for one
 * client with a client attribute named after its message key:
 *
 *   ratio.text.<messageKey>          every language
 *   ratio.text.<messageKey>.<lang>   one language (e.g. `.nb`, `.en`), wins
 *
 * The value is either the text itself, or `${realmKey}`, a reference to a
 * text in Realm settings → Localization, where Keycloak keeps one value per
 * language. `{0}`, `{1}` … are filled in as in the theme's own messages
 * (e.g. `ratio.text.tesseraContinueWith` = `Bruk {0}`).
 *
 * `${realmKey}` works because keycloakify's FTL resolves every string in the
 * data model that looks like a message key, client attributes included, and
 * hands the result to the page. A reference Keycloak cannot resolve, an empty
 * value or an unknown key falls back to the theme's own text.
 *
 * Client texts are rendered as plain text: markup in an attribute shows as
 * typed, it is never interpreted as HTML.
 */
import type { I18n } from "./i18n";

export const TEXT_ATTRIBUTE_PREFIX = "ratio.text.";

const REALM_REFERENCE = /^\$\{([a-zA-Z0-9-_.]+)\}$/;

function own(attributes: Record<string, string>, name: string): string | undefined {
    return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : undefined;
}

function fill(text: string, args: (string | undefined)[]): string {
    return text.replace(/\{(\d+)\}/g, (placeholder, index: string) => args[Number(index)] ?? placeholder);
}

export function withClientTexts(i18n: I18n, attributes: Record<string, string> | undefined): I18n {
    if (attributes === undefined || !Object.keys(attributes).some(name => name.startsWith(TEXT_ATTRIBUTE_PREFIX))) {
        return i18n;
    }

    const languageTag = i18n.currentLanguage.languageTag;

    const lookup = (key: string): string | undefined => {
        const raw = (
            own(attributes, `${TEXT_ATTRIBUTE_PREFIX}${key}.${languageTag}`) ?? own(attributes, `${TEXT_ATTRIBUTE_PREFIX}${key}`)
        )?.trim();
        if (!raw) return undefined;

        const reference = raw.match(REALM_REFERENCE);
        if (reference === null) return raw;

        // advancedMsgStr answers an unresolved reference with what it was given.
        const resolved = i18n.advancedMsgStr(raw);
        return resolved === raw || resolved === reference[1] ? undefined : resolved;
    };

    const msgStr: I18n["msgStr"] = (key, ...args) => {
        const text = lookup(key);
        return text === undefined ? i18n.msgStr(key, ...args) : fill(text, args);
    };

    const msg: I18n["msg"] = (key, ...args) => {
        const text = lookup(key);
        return text === undefined ? i18n.msg(key, ...args) : <span data-kc-msg={key}>{fill(text, args)}</span>;
    };

    return { ...i18n, msg, msgStr };
}
