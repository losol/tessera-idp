// ratio · Keycloak login theme
// SPDX-FileCopyrightText: 2026 Losol AS
// SPDX-License-Identifier: MPL-2.0

/**
 * Per-client Ratio theme, read from two client attributes:
 *
 * - `ratio.theme`        — the palette: `standard` (default), `bureau`, `ink`
 *                          or `tabula` (the named themes shipped by
 *                          `@eventuras/ratio-ui/themes/*.css`).
 * - `ratio.color-scheme` — `light` (default), `dark`, or `auto` to follow the
 *                          visitor's system setting.
 *
 * Absent or unknown values fall back to the default, so a typo never breaks
 * the page; it only shows the standard light theme.
 *
 * Ratio themes are keyed on `<html>`: the standard theme carries its mode in
 * `data-theme` (`light` / `dark`), a named theme puts its palette there and
 * the mode in `data-color-scheme`. Both attributes are always set, so the
 * `dark:` utilities and every theme file see the same mode.
 *
 * Pages rendered without a client (e.g. an unknown realm) get the default.
 */

export const THEME_ATTRIBUTE = "ratio.theme";
export const COLOR_SCHEME_ATTRIBUTE = "ratio.color-scheme";

const PALETTES = ["standard", "bureau", "ink", "tabula"] as const;
const COLOR_SCHEMES = ["light", "dark", "auto"] as const;

export type RatioPalette = (typeof PALETTES)[number];
export type RatioColorScheme = (typeof COLOR_SCHEMES)[number];
export type RatioTheme = { palette: RatioPalette; colorScheme: RatioColorScheme };

export const DEFAULT_THEME: RatioTheme = { palette: "standard", colorScheme: "light" };

// Array lookup, not an object: attribute values are admin-chosen strings, and
// an object lookup would answer `__proto__` with Object.prototype.
function pick<T extends string>(raw: string | undefined, allowed: readonly T[]): T | undefined {
    const value = raw?.trim().toLowerCase();
    return allowed.find(option => option === value);
}

export function resolveRatioTheme(attributes: Record<string, string> | undefined): RatioTheme {
    return {
        palette: pick(attributes?.[THEME_ATTRIBUTE], PALETTES) ?? DEFAULT_THEME.palette,
        colorScheme: pick(attributes?.[COLOR_SCHEME_ATTRIBUTE], COLOR_SCHEMES) ?? DEFAULT_THEME.colorScheme
    };
}

/** Writes the theme onto `<html>`. `prefersDark` decides an `auto` scheme. */
export function applyRatioTheme(theme: RatioTheme, prefersDark: boolean, root: HTMLElement = document.documentElement) {
    const scheme = theme.colorScheme === "auto" ? (prefersDark ? "dark" : "light") : theme.colorScheme;
    root.dataset.theme = theme.palette === "standard" ? scheme : theme.palette;
    root.dataset.colorScheme = scheme;
}
