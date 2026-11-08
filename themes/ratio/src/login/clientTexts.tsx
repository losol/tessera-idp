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
 * The texts reach every lookup a page makes: `msg` and `msgStr` take a message
 * key; `advancedMsg` and `advancedMsgStr` take a key or a `${key}` reference,
 * which is how keycloakify's profile form labels the standard fields
 * (`${firstName}` → `ratio.text.firstName`).
 *
 * Client texts are plain text: markup in an attribute shows as typed, it is
 * never interpreted as HTML. `msg` and `advancedMsg` render them as text (the
 * theme's own messages stay sanitised HTML, as in keycloakify). The strings
 * from `msgStr` and `advancedMsgStr` go into text, attributes and the page
 * title, except in a few places that build HTML out of them. Those are handled
 * where they do it:
 *
 * - The recovery-code print sheet escapes its texts
 *   (pages/LoginRecoveryAuthnCodeConfig.tsx).
 * - keycloakify's info and consent pages put `advancedMsgStr` into HTML (the
 *   required actions, the client name in the title), so on those pages it
 *   keeps the theme's text. `msg`, `msgStr` and `advancedMsg` still use the
 *   client's there.
 */
import type { I18n } from "./i18n";
import type { KcContext } from "./KcContext";

export const TEXT_ATTRIBUTE_PREFIX = "ratio.text.";

const REALM_REFERENCE = /^\$\{([a-zA-Z0-9-_.]+)\}$/;

/** How keycloakify's advancedMsg/advancedMsgStr read a `${key}` reference. */
const KEY_REFERENCE = /^\$\{(.+)\}$/;

/** Pages whose keycloakify component turns advancedMsgStr into HTML. */
const ADVANCED_MSG_STR_AS_HTML: ReadonlySet<KcContext["pageId"]> = new Set(["info.ftl", "login-oauth-grant.ftl"]);

function own(attributes: Record<string, string>, name: string): string | undefined {
    return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : undefined;
}

/**
 * Fills `{0}`, `{1}` … the way keycloakify fills the theme's own messages: the
 * first argument goes to the lowest placeholder in the text, so a text for
 * Keycloak's "Minimal length is {1}." gets the length in `{1}`.
 */
function fill(text: string, args: readonly (string | undefined)[]): string {
    const indexes = Array.from(text.matchAll(/\{(\d+)\}/g), match => Number(match[1]));
    if (indexes.length === 0) return text;
    const first = Math.min(...indexes);
    return text.replace(/\{(\d+)\}/g, (placeholder, index: string) => args[Number(index) - first] ?? placeholder);
}

export function withClientTexts(
    i18n: I18n,
    attributes: Record<string, string> | undefined,
    pageId: KcContext["pageId"]
): I18n {
    if (attributes === undefined || !Object.keys(attributes).some(name => name.startsWith(TEXT_ATTRIBUTE_PREFIX))) {
        return i18n;
    }

    const languageTag = i18n.currentLanguage.languageTag;

    const lookup = (key: string): string | undefined => {
        if (key === "") return undefined;

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

    // A key, a `${key}` reference, or text keycloakify shows as is.
    const advancedLookup = (key: string) => lookup(key.match(KEY_REFERENCE)?.[1] ?? key);

    const msgStr: I18n["msgStr"] = (key, ...args) => {
        const text = lookup(key);
        return text === undefined ? i18n.msgStr(key, ...args) : fill(text, args);
    };

    const msg: I18n["msg"] = (key, ...args) => {
        const text = lookup(key);
        return text === undefined ? i18n.msg(key, ...args) : <span data-kc-msg={key}>{fill(text, args)}</span>;
    };

    const advancedMsgStr: I18n["advancedMsgStr"] = (key, ...args) => {
        const text = advancedLookup(key);
        return text === undefined ? i18n.advancedMsgStr(key, ...args) : fill(text, args);
    };

    const advancedMsg: I18n["advancedMsg"] = (key, ...args) => {
        const text = advancedLookup(key);
        return text === undefined ? i18n.advancedMsg(key, ...args) : <span data-kc-msg={key}>{fill(text, args)}</span>;
    };

    return {
        ...i18n,
        msg,
        msgStr,
        advancedMsg,
        advancedMsgStr: ADVANCED_MSG_STR_AS_HTML.has(pageId) ? i18n.advancedMsgStr : advancedMsgStr
    };
}
