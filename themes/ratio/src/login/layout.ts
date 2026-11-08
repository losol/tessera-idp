// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Per-client page layout and footer links, read from client attributes:
 *
 * - `ratio.layout`      — `split` (default: a brand panel beside the form),
 *                         `card` (a centred card), `column` (one calm column,
 *                         no card) or `panel` (the form in an edge panel, the
 *                         service you sign in to beside it).
 * - `ratio.help-url`    — a "Help" link in the footer.
 * - `ratio.privacy-url` — a "Privacy" link in the footer.
 *
 * Absent or unknown values fall back to the default; a link that is not an
 * absolute http(s) URL is left out.
 */

export const LAYOUT_ATTRIBUTE = "ratio.layout";
export const HELP_URL_ATTRIBUTE = "ratio.help-url";
export const PRIVACY_URL_ATTRIBUTE = "ratio.privacy-url";

const LAYOUTS = ["split", "card", "column", "panel"] as const;

export type RatioLayout = (typeof LAYOUTS)[number];

export const DEFAULT_LAYOUT: RatioLayout = "split";

export function resolveLayout(attributes: Record<string, string> | undefined): RatioLayout {
    const value = attributes?.[LAYOUT_ATTRIBUTE]?.trim().toLowerCase();
    return LAYOUTS.find(layout => layout === value) ?? DEFAULT_LAYOUT;
}

/** The URL if it is an absolute http(s) URL, so `javascript:` and the like never become a link. */
export function safeUrl(raw: string | undefined): string | undefined {
    if (!raw) return undefined;
    try {
        const parsed = new URL(raw.trim());
        return parsed.protocol === "https:" || parsed.protocol === "http:" ? parsed.href : undefined;
    } catch {
        return undefined;
    }
}
