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
// mock realm offers Vipps and GitHub. `lead` is the element that must come
// first and be emphasised; `absent` must not be rendered at all.
const LOGIN_METHOD_CASES: {
    methods: string | null;
    providers: string[];
    lead: string | null;
    emailForm: "open" | "link" | "none";
}[] = [
    // Attribute absent → unchanged default (doktorinord): all providers, no lead.
    { methods: null, providers: ["vipps", "github"], lead: null, emailForm: "link" },
    { methods: "github", providers: ["github"], lead: "#social-github", emailForm: "none" },
    { methods: "otp", providers: [], lead: "#kc-email-form", emailForm: "open" },
    { methods: "otp,github", providers: ["github"], lead: "#kc-email-form", emailForm: "open" },
    { methods: "github, otp", providers: ["github"], lead: "#social-github", emailForm: "link" },
    { methods: "github,vipps", providers: ["github", "vipps"], lead: "#social-github", emailForm: "none" },
    // Unknown entries are ignored; nothing renderable falls back to the default.
    { methods: "passkey,otp", providers: [], lead: "#kc-email-form", emailForm: "open" },
    { methods: "passkey", providers: ["vipps", "github"], lead: null, emailForm: "link" }
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

        if (c.lead !== null) {
            // The lead comes first in the form wrapper.
            const first = page.locator("#kc-form-wrapper > :first-child");
            await expect(first.locator(c.lead).or(first.and(page.locator(c.lead)))).toHaveCount(1);
        }
    });
}

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
