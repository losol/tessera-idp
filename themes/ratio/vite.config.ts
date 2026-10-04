import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { keycloakify } from "keycloakify/vite-plugin";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

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
            },
            // Keycloak serves Norwegian Bokmål as "nb", but ships its Norwegian
            // messages as "no", so on an nb realm every server-rendered message
            // (error pages, field errors) fell back to English. Add an nb bundle:
            // the "no" messages plus server-messages/messages_nb.properties.
            // Runs in the generated resources dir, before the JAR is packed.
            postBuild: async () => {
                const dir = "theme/ratio/login";
                const no = await readFile(`${dir}/messages/messages_no.properties`, "utf8");
                const extra = await readFile(
                    fileURLToPath(new URL("./server-messages/messages_nb.properties", import.meta.url)),
                    "utf8"
                );
                await writeFile(`${dir}/messages/messages_nb.properties`, `${no}\n${extra}`);

                const themeProperties = await readFile(`${dir}/theme.properties`, "utf8");
                await writeFile(
                    `${dir}/theme.properties`,
                    themeProperties.replace(/^locales=(.*)$/m, (line, list: string) =>
                        list.split(",").includes("nb") ? line : `locales=${list},nb`
                    )
                );
            }
        })
    ]
});
