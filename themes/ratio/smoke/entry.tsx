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
 * Optional `?theme=` and `?scheme=` params set the client attributes
 * `ratio.theme` and `ratio.color-scheme` (e.g. `theme=bureau&scheme=dark`), so
 * each per-client Ratio theme can be rendered.
 *
 * `?text.<key>=` params set per-client texts, the client attribute
 * `ratio.text.<key>` (e.g. `text.tesseraOtpEmailTitle=Hello` or a
 * language-specific `text.tesseraOtpEmailTitle.nb=Hei`). `?realmText.<key>=`
 * params stand in for texts Keycloak resolved from Realm settings →
 * Localization, so a `${key}` reference in a client text can be rendered.
 *
 * `?attr.<name>=` params set any other client attribute (e.g.
 * `attr.ratio.layout=split`, `attr.ratio.help-url=https://…`), and
 * `?clientName=` / `?clientUrl=` the client's name and base URL, so each
 * per-client layout can be rendered.
 *
 * An optional `?traceId=` param sets the error page's trace id, as Keycloak
 * does when tracing is enabled.
 *
 * An optional `?providers=` param adds generic OIDC providers with the given
 * aliases to the mock realm (e.g. `providers=__proto__`), so odd but valid
 * aliases can be rendered.
 */
import { createRoot } from "react-dom/client";
import { StrictMode } from "react";
import type { DeepPartial } from "keycloakify/tools/DeepPartial";
import KcPage from "../src/login/KcPage";
import { getKcContextMock } from "../src/login/KcPageStory";
import type { KcContext } from "../src/login/KcContext";
import { LOGIN_METHODS_ATTRIBUTE } from "../src/login/loginMethods";
import { COLOR_SCHEME_ATTRIBUTE, THEME_ATTRIBUTE } from "../src/login/ratioTheme";
import { TEXT_ATTRIBUTE_PREFIX } from "../src/login/clientTexts";

const params = new URLSearchParams(window.location.search);

const pageId = (params.get("pageId") ?? "login.ftl") as KcContext["pageId"];
const locale = params.get("locale");
const methods = params.get("methods");
const traceId = params.get("traceId");
const extraProviders = params.get("providers");
const theme = params.get("theme");
const scheme = params.get("scheme");

const clientAttributes: Record<string, string> = {
    ...(methods !== null && { [LOGIN_METHODS_ATTRIBUTE]: methods }),
    ...(theme !== null && { [THEME_ATTRIBUTE]: theme }),
    ...(scheme !== null && { [COLOR_SCHEME_ATTRIBUTE]: scheme })
};
const realmTexts: Record<string, string> = {};
for (const [name, value] of params) {
    if (name.startsWith("text.")) clientAttributes[TEXT_ATTRIBUTE_PREFIX + name.slice("text.".length)] = value;
    if (name.startsWith("realmText.")) realmTexts[name.slice("realmText.".length)] = value;
    if (name.startsWith("attr.")) clientAttributes[name.slice("attr.".length)] = value;
}
const clientName = params.get("clientName");
const clientUrl = params.get("clientUrl");

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
    ...((Object.keys(clientAttributes).length !== 0 || clientName !== null || clientUrl !== null) && {
        client: {
            attributes: clientAttributes,
            ...(clientName !== null && { name: clientName }),
            ...(clientUrl !== null && { baseUrl: clientUrl })
        }
    }),
    ...(traceId !== null && { traceId })
};

const kcContext = getKcContextMock({ pageId, overrides });

// What keycloakify's FTL hands the page for `${key}` strings it resolved.
Object.assign(kcContext["x-keycloakify"].messages, realmTexts);

if (extraProviders !== null && kcContext.pageId === "login-tessera-otp-start.ftl") {
    kcContext.social.providers = [
        ...(kcContext.social.providers ?? []),
        ...extraProviders.split(",").map(alias => ({ loginUrl: "#", alias, providerId: "oidc", displayName: alias }))
    ];
}

createRoot(document.getElementById("root")!).render(
    <StrictMode>
        <KcPage kcContext={kcContext} />
    </StrictMode>
);
