import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { resolve } from "node:path";

/**
 * Production-mode build of the smoke harness (smoke/index.html -> entry.tsx).
 *
 * Deliberately does NOT use the keycloakify vite plugin: we want a plain,
 * minified Vite build that mirrors how the deployed theme bundle is compiled,
 * so the smoke test can catch prod-only runtime errors (minification /
 * tree-shaking) that the dev server hides. Output goes to dist_smoke/ and is
 * served by `vite preview` for the Playwright run.
 */
export default defineConfig({
    root: resolve(__dirname, ".."),
    plugins: [react(), tailwindcss()],
    build: {
        outDir: resolve(__dirname, "../dist_smoke"),
        emptyOutDir: true,
        rollupOptions: {
            input: resolve(__dirname, "index.html")
        }
    }
});
