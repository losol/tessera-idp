/**
 * Smoke-test entrypoint. Mounts the real <KcPage> with a mocked kcContext for
 * the pageId given in the `?pageId=` query string, exercising the exact render
 * path Keycloak uses in production. Driven headlessly by `smoke/smoke.spec.ts`.
 *
 * An optional `?locale=` param turns on realm internationalization and forces a
 * current language tag, reproducing the real Keycloak environment where the
 * Norwegian Bokmål tag is "nb" (not keycloakify's built-in "no").
 */
import { createRoot } from "react-dom/client";
import { StrictMode } from "react";
import type { DeepPartial } from "keycloakify/tools/DeepPartial";
import KcPage from "../src/login/KcPage";
import { getKcContextMock } from "../src/login/KcPageStory";
import type { KcContext } from "../src/login/KcContext";

const params = new URLSearchParams(window.location.search);

const pageId = (params.get("pageId") ?? "login.ftl") as KcContext["pageId"];
const locale = params.get("locale");

const overrides: DeepPartial<KcContext> | undefined =
    locale === null
        ? undefined
        : {
              realm: { internationalizationEnabled: true },
              locale: {
                  currentLanguageTag: locale,
                  supported: [
                      { languageTag: "en", url: "#", label: "English" },
                      { languageTag: locale, url: "#", label: "Norsk (bokmål)" }
                  ]
              }
          };

const kcContext = getKcContextMock({ pageId, overrides });

createRoot(document.getElementById("root")!).render(
    <StrictMode>
        <KcPage kcContext={kcContext} />
    </StrictMode>
);
