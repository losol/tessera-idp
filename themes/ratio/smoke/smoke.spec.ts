/**
 * Headless smoke test for the login theme. For every custom login pageId it
 * mounts the real <KcPage> (via smoke/entry.tsx served by `vite preview` from a
 * production-mode Vite build), then asserts the page rendered non-blank with zero console errors
 * and zero uncaught page errors. Guards against the "blank white login page"
 * class of runtime-render regressions that a JAR build cannot catch.
 */
import { test, expect } from "@playwright/test";

const PAGE_IDS = ["login.ftl", "login-tessera-otp-start.ftl", "login-tessera-otp-code.ftl", "login-update-profile.ftl"];

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
