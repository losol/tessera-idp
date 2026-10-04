/**
 * Smoke-test entrypoint. Mounts the real <KcPage> with a mocked kcContext for
 * the pageId given in the `?pageId=` query string, exercising the exact render
 * path Keycloak uses in production. Driven headlessly by `smoke/smoke.spec.ts`.
 *
 * An optional `?locale=` param turns on realm internationalization and forces a
 * current language tag, reproducing the real Keycloak environment where the
 * Norwegian Bokmål tag is "nb" (not keycloakify's built-in "no").
 *
 * An optional `?methods=` param sets the client attribute
 * `tessera.login-methods` (e.g. `methods=otp,github`), so each per-client
 * layout of the Tessera OTP start page can be rendered.
 *
 * An optional `?traceId=` param sets the error page's trace id, as Keycloak
 * does when tracing is enabled.
 */
import { createRoot } from "react-dom/client";
import { StrictMode } from "react";
import type { DeepPartial } from "keycloakify/tools/DeepPartial";
import KcPage from "../src/login/KcPage";
import { getKcContextMock } from "../src/login/KcPageStory";
import type { KcContext } from "../src/login/KcContext";
import { LOGIN_METHODS_ATTRIBUTE } from "../src/login/loginMethods";

const params = new URLSearchParams(window.location.search);

const pageId = (params.get("pageId") ?? "login.ftl") as KcContext["pageId"];
const locale = params.get("locale");
const methods = params.get("methods");
const traceId = params.get("traceId");

const overrides: DeepPartial<KcContext> = {
    ...(locale !== null && {
        realm: { internationalizationEnabled: true },
        locale: {
            currentLanguageTag: locale,
            supported: [
                { languageTag: "en", url: "#", label: "English" },
                { languageTag: locale, url: "#", label: "Norsk (bokmål)" }
            ]
        }
    }),
    ...(methods !== null && {
        client: { attributes: { [LOGIN_METHODS_ATTRIBUTE]: methods } }
    }),
    ...(traceId !== null && { traceId })
};

const kcContext = getKcContextMock({ pageId, overrides });

createRoot(document.getElementById("root")!).render(
    <StrictMode>
        <KcPage kcContext={kcContext} />
    </StrictMode>
);
