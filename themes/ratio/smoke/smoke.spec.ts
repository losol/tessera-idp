/**
 * Headless smoke test for the login theme. For every custom login pageId it
 * mounts the real <KcPage> (via smoke/entry.tsx served by `vite preview` from a
 * production-mode Vite build), then asserts the page rendered non-blank with zero console errors
 * and zero uncaught page errors. Guards against the "blank white login page"
 * class of runtime-render regressions that a JAR build cannot catch.
 */
import { test, expect } from "@playwright/test";

const PAGE_IDS = ["login.ftl", "login-tessera-otp-start.ftl", "login-tessera-otp-code.ftl", "login-update-profile.ftl", "error.ftl"];

// `null` = realm default locale; "nb" exercises Keycloak's Norwegian Bokmål
// tag, which keycloakify's built-in default set does not know (it uses "no").
// A missing "nb" registration throws during async translation loading and
// blanks the page, so this is the key regression case to guard.
const LOCALES = [null, "nb"];

for (const pageId of PAGE_IDS) {
    for (const locale of LOCALES) {
        const label = locale === null ? "default locale" : `locale=${locale}`;

        test(`renders ${pageId} (${label}) without errors`, async ({ page }) => {
            const errors: string[] = [];

            page.on("console", msg => {
                if (msg.type() === "error") {
                    errors.push(`console.error: ${msg.text()}`);
                }
            });
            page.on("pageerror", err => {
                errors.push(`pageerror: ${err.message}`);
            });

            const query = new URLSearchParams({ pageId });
            if (locale !== null) {
                query.set("locale", locale);
            }
            await page.goto(`/smoke/index.html?${query.toString()}`);

            // Wait for the React tree (lazy chunks + Suspense) to settle. Use the
            // Template card, which is present on every page (the Tessera OTP page
            // keeps its submit button behind a "use a one-time code" toggle, so
            // #kc-login is not always rendered up front).
            await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });

            // Allow the async translation load (prI18n_currentLanguage) to run;
            // a missing language registration throws here, not on first render.
            await page.waitForTimeout(500);

            const rootText = (await page.locator("#root").innerText()).trim();
            expect(rootText.length, "root should not be blank").toBeGreaterThan(0);

            // The page must be actually visible, not just present in the DOM.
            // Ratio's base CSS sets `html { opacity: 0 }` until a data-theme is
            // applied; without it the page renders but stays invisible (which
            // Playwright's toBeVisible does not catch, as it ignores opacity).
            const htmlOpacity = await page.evaluate(
                () => getComputedStyle(document.documentElement).opacity
            );
            expect(htmlOpacity, `<html> must be visible for ${pageId} (${label})`).toBe("1");

            expect(errors, `unexpected console/page errors for ${pageId} (${label})`).toEqual([]);
        });
    }
}

// Per-client login methods on the Tessera OTP start page, driven by the
// client attribute `tessera.login-methods` (smoke/entry.tsx `?methods=`). The
// mock realm offers Vipps, GitHub, Google and Apple. `lead` is the element
// that must come first and be emphasised; `absent` must not be rendered at all.
const LOGIN_METHOD_CASES: {
    methods: string | null;
    providers: string[];
    lead: string | null;
    emailForm: "open" | "link" | "none";
}[] = [
    // Attribute absent → unchanged default (doktorinord): all providers, no lead.
    { methods: null, providers: ["vipps", "github", "google", "apple"], lead: null, emailForm: "link" },
    { methods: "google,apple,otp", providers: ["google", "apple"], lead: "#social-google", emailForm: "link" },
    { methods: "github", providers: ["github"], lead: "#social-github", emailForm: "none" },
    { methods: "otp", providers: [], lead: "#kc-email-form", emailForm: "open" },
    { methods: "otp,github", providers: ["github"], lead: "#kc-email-form", emailForm: "open" },
    { methods: "github, otp", providers: ["github"], lead: "#social-github", emailForm: "link" },
    { methods: "github,vipps", providers: ["github", "vipps"], lead: "#social-github", emailForm: "none" },
    // Unknown entries are ignored; nothing renderable falls back to the default.
    { methods: "passkey,otp", providers: [], lead: "#kc-email-form", emailForm: "open" },
    { methods: "passkey", providers: ["vipps", "github", "google", "apple"], lead: null, emailForm: "link" }
];

for (const c of LOGIN_METHOD_CASES) {
    test(`login-tessera-otp-start.ftl offers methods=${c.methods ?? "(absent)"}`, async ({ page }) => {
        const query = new URLSearchParams({ pageId: "login-tessera-otp-start.ftl" });
        if (c.methods !== null) query.set("methods", c.methods);
        await page.goto(`/smoke/index.html?${query.toString()}`);
        await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });

        const rendered = await page
            .locator(".ratio-login__social-btn")
            .evaluateAll(els => els.map(el => el.id.replace(/^social-/, "")));
        expect(rendered, "providers, in order").toEqual(c.providers);

        const leads = page.locator(".ratio-login__social-btn--lead");
        await expect(leads).toHaveCount(c.lead?.startsWith("#social-") ? 1 : 0);

        await expect(page.locator("#kc-email-form")).toHaveCount(c.emailForm === "open" ? 1 : 0);
        await expect(page.locator(".ratio-login__textlink")).toHaveCount(c.emailForm === "link" ? 1 : 0);

        // Google, Apple and GitHub carry a brand mark; Vipps (generic OIDC) does not.
        const withIcon = await page
            .locator(".ratio-login__social-btn:has(.ratio-login__social-icon)")
            .evaluateAll(els => els.map(el => el.id.replace(/^social-/, "")));
        expect(withIcon, "providers with an icon").toEqual(c.providers.filter(p => p !== "vipps"));
        // Every button is labelled "Continue with <provider>".
        for (const p of c.providers) {
            await expect(page.locator(`#social-${p}`)).toContainText(/^Continue with /);
        }

        // A leading Google keeps its mark on a white badge (brand guideline).
        if (c.lead === "#social-google") {
            await expect(page.locator("#social-google .ratio-login__social-icon--badge")).toHaveCSS("background-color", "rgb(255, 255, 255)");
        }

        if (c.lead !== null) {
            // The lead comes first in the form wrapper.
            const first = page.locator("#kc-form-wrapper > :first-child");
            await expect(first.locator(c.lead).or(first.and(page.locator(c.lead)))).toHaveCount(1);
        }
    });
}

// A generic OIDC provider may carry any alias Keycloak accepts, including one
// that collides with an object property; it must render as a plain button.
test("login-tessera-otp-start.ftl renders a provider aliased __proto__", async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", err => errors.push(err.message));
    await page.goto("/smoke/index.html?pageId=login-tessera-otp-start.ftl&providers=__proto__,constructor");
    await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });
    await expect(page.locator("#social-__proto__")).toContainText("Continue with __proto__");
    await expect(page.locator("#social-__proto__ .ratio-login__social-icon")).toHaveCount(0);
    await expect(page.locator("#social-constructor .ratio-login__social-icon")).toHaveCount(0);
    expect(errors).toEqual([]);
});

// Error page: the time is always shown; the trace id only when Keycloak set
// one (tracing enabled), and then it is the reference support looks up.
for (const traceId of [null, "4c2aec90375e5ef6c3ecc0a3ac7d87fe"]) {
    test(`error.ftl shows a support reference (traceId ${traceId === null ? "absent" : "present"})`, async ({ page }) => {
        const query = new URLSearchParams({ pageId: "error.ftl" });
        if (traceId !== null) query.set("traceId", traceId);
        await page.goto(`/smoke/index.html?${query.toString()}`);
        await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });

        const reference = page.locator(".ratio-login__error-reference");
        await expect(reference).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} UTC/);
        if (traceId === null) {
            await expect(page.locator("#traceId")).toHaveCount(0);
        } else {
            await expect(page.locator("#traceId")).toHaveText(traceId);
        }
    });
}

// Per-client Ratio theme, driven by the client attributes `ratio.theme` and
// `ratio.color-scheme` (smoke/entry.tsx `?theme=` / `?scheme=`). Each case
// checks the attributes written on <html> and that the theme's tokens
// actually reached the page (its surface colour differs from the default).
const THEME_CASES: {
    theme: string | null;
    scheme: string | null;
    dataTheme: string;
    dataColorScheme: string;
}[] = [
    // Absent → the standard light theme, as before.
    { theme: null, scheme: null, dataTheme: "light", dataColorScheme: "light" },
    { theme: null, scheme: "dark", dataTheme: "dark", dataColorScheme: "dark" },
    { theme: "standard", scheme: "light", dataTheme: "light", dataColorScheme: "light" },
    { theme: "bureau", scheme: null, dataTheme: "bureau", dataColorScheme: "light" },
    { theme: "bureau", scheme: "dark", dataTheme: "bureau", dataColorScheme: "dark" },
    { theme: "ink", scheme: "dark", dataTheme: "ink", dataColorScheme: "dark" },
    { theme: " Tabula ", scheme: null, dataTheme: "tabula", dataColorScheme: "light" },
    // Unknown values fall back to the default rather than break the page.
    { theme: "__proto__", scheme: "purple", dataTheme: "light", dataColorScheme: "light" }
];

async function surfaceOf(page: import("@playwright/test").Page) {
    return page.evaluate(() => getComputedStyle(document.body).backgroundColor);
}

for (const c of THEME_CASES) {
    test(`client theme ${c.theme ?? "(absent)"} / scheme ${c.scheme ?? "(absent)"}`, async ({ page }) => {
        const errors: string[] = [];
        page.on("pageerror", err => errors.push(err.message));

        // Reference: the default page's surface colour.
        await page.goto("/smoke/index.html?pageId=login-tessera-otp-start.ftl");
        await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });
        const defaultSurface = await surfaceOf(page);

        const query = new URLSearchParams({ pageId: "login-tessera-otp-start.ftl" });
        if (c.theme !== null) query.set("theme", c.theme);
        if (c.scheme !== null) query.set("scheme", c.scheme);
        await page.goto(`/smoke/index.html?${query.toString()}`);
        await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });

        const html = page.locator("html");
        await expect(html).toHaveAttribute("data-theme", c.dataTheme);
        await expect(html).toHaveAttribute("data-color-scheme", c.dataColorScheme);

        const isDefault = c.dataTheme === "light";
        const surface = await surfaceOf(page);
        if (isDefault) {
            expect(surface, "default theme keeps the default surface").toBe(defaultSurface);
        } else {
            expect(surface, "theme tokens should restyle the page").not.toBe(defaultSurface);
        }

        expect(await html.evaluate(el => getComputedStyle(el).opacity)).toBe("1");
        expect(errors).toEqual([]);
    });
}

// `auto` follows the visitor's system setting.
for (const colorScheme of ["light", "dark"] as const) {
    test(`client scheme auto follows a ${colorScheme} system setting`, async ({ page }) => {
        await page.emulateMedia({ colorScheme });
        await page.goto("/smoke/index.html?pageId=login-tessera-otp-start.ftl&theme=ink&scheme=auto");
        await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });
        await expect(page.locator("html")).toHaveAttribute("data-theme", "ink");
        await expect(page.locator("html")).toHaveAttribute("data-color-scheme", colorScheme);
    });
}

// Square-cornered themes reach the login's own buttons too, not only Ratio's.
test("tabula squares the provider buttons", async ({ page }) => {
    await page.goto("/smoke/index.html?pageId=login-tessera-otp-start.ftl&theme=tabula");
    await expect(page.locator(".ratio-login__card")).toBeVisible({ timeout: 10000 });
    await expect(page.locator(".ratio-login__social-btn").first()).toHaveCSS("border-top-left-radius", "0px");
});

// Per-client texts: `ratio.text.<key>` (smoke/entry.tsx `?text.<key>=`).
test.describe("client texts", () => {
    const start = "/smoke/index.html?pageId=login-tessera-otp-start.ftl";
    const card = (page: import("@playwright/test").Page) => page.locator(".ratio-login__card");

    test("replace a theme text for the client", async ({ page }) => {
        await page.goto(`${start}&text.tesseraOtpEmailTitle=${encodeURIComponent("Logg inn på Min side")}`);
        await expect(page.locator(".ratio-login__title")).toHaveText("Logg inn på Min side");
    });

    test("fill {0} like the theme's own messages", async ({ page }) => {
        await page.goto(`${start}&text.tesseraContinueWith=${encodeURIComponent("Bruk {0}")}`);
        await expect(page.locator("#social-github")).toHaveText("Bruk GitHub");
    });

    test("prefer the text for the current language", async ({ page }) => {
        const query = new URLSearchParams({ pageId: "login-tessera-otp-start.ftl", locale: "nb" });
        query.set("text.tesseraOtpEmailTitle", "Sign in to My page");
        query.set("text.tesseraOtpEmailTitle.nb", "Logg inn på Min side");
        await page.goto(`/smoke/index.html?${query.toString()}`);
        await expect(page.locator(".ratio-login__title")).toHaveText("Logg inn på Min side");
    });

    test("resolve a ${key} reference from realm localization", async ({ page }) => {
        const query = new URLSearchParams({ pageId: "login-tessera-otp-start.ftl" });
        query.set("text.tesseraOtpEmailTitle", "${losvikLoginTitle}");
        query.set("realmText.losvikLoginTitle", "Velkommen til Losvik");
        await page.goto(`/smoke/index.html?${query.toString()}`);
        await expect(page.locator(".ratio-login__title")).toHaveText("Velkommen til Losvik");
    });

    test("fall back to the theme text for an unresolved reference or an empty value", async ({ page }) => {
        const query = new URLSearchParams({ pageId: "login-tessera-otp-start.ftl" });
        query.set("text.tesseraOtpEmailTitle", "${noSuchKey}");
        query.set("text.tesseraOtpUseCode", "  ");
        await page.goto(`/smoke/index.html?${query.toString()}`);
        await expect(page.locator(".ratio-login__title")).toHaveText("Sign in");
        await expect(page.locator(".ratio-login__textlink")).toHaveText("Log in with a one-time code");
    });

    test("render markup in a client text as plain text", async ({ page }) => {
        const errors: string[] = [];
        page.on("pageerror", err => errors.push(err.message));
        await page.goto(`${start}&text.tesseraOtpEmailTitle=${encodeURIComponent('<img src=x onerror="alert(1)">Hi')}`);
        await expect(page.locator(".ratio-login__title")).toHaveText('<img src=x onerror="alert(1)">Hi');
        await expect(card(page).locator("img")).toHaveCount(0);
        expect(errors).toEqual([]);
    });

    test("leave the default page untouched without client texts", async ({ page }) => {
        await page.goto(start);
        await expect(page.locator(".ratio-login__title")).toHaveText("Sign in");
        await expect(page.locator("#social-github")).toHaveText("Continue with GitHub");
    });
});
