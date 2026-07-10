import { defineConfig, devices } from "@playwright/test";

/**
 * Smoke-test config. Builds the minified smoke harness (dist_smoke/, produced
 * by smoke/vite.smoke.config.ts) and serves it via `vite preview`, reproducing
 * the exact bundle behaviour of the deployed theme so the suite catches
 * production-only "blank page" runtime errors that the dev server hides.
 */
export default defineConfig({
    testDir: "./smoke",
    fullyParallel: true,
    reporter: [["list"]],
    use: {
        baseURL: "http://localhost:4173"
    },
    projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
    webServer: {
        command:
            "npx vite build --config smoke/vite.smoke.config.ts && npx vite preview --config smoke/vite.smoke.config.ts --port 4173 --strictPort",
        url: "http://localhost:4173/smoke/index.html",
        reuseExistingServer: !process.env.CI,
        timeout: 120000
    }
});
