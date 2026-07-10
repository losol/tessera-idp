import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { keycloakify } from "keycloakify/vite-plugin";

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [
        react(),
        tailwindcss(),
        keycloakify({
            themeName: "ratio",
            accountThemeImplementation: "none",
            // We run Keycloak 26 → build a single JAR with a stable name.
            keycloakVersionTargets: {
                "22-to-25": false,
                "all-other-versions": "keycloak-ratio-theme.jar"
            }
        })
    ]
});
