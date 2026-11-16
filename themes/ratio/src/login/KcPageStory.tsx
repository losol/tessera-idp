import type { DeepPartial } from "keycloakify/tools/DeepPartial";
import type { KcContext } from "./KcContext";
import KcPage from "./KcPage";
import { createGetKcContextMock } from "keycloakify/login/KcContext";
import type { KcContextExtension, KcContextExtensionPerPage } from "./KcContext";
import { themeNames, kcEnvDefaults } from "../kc.gen";

const kcContextExtension: KcContextExtension = {
    themeName: themeNames[0],
    properties: {
        ...kcEnvDefaults
    }
};
// The mock realm's identity providers, on every page that lists them.
const providers = [
                {
                    loginUrl: "#",
                    alias: "vipps",
                    providerId: "vipps",
                    displayName: "Vipps"
                },
                {
                    loginUrl: "#",
                    alias: "github",
                    providerId: "github",
                    displayName: "GitHub"
                },
                {
                    loginUrl: "#",
                    alias: "google",
                    providerId: "google",
                    displayName: "Google"
                },
                {
                    loginUrl: "#",
                    alias: "apple",
                    providerId: "apple",
                    displayName: "Apple"
                }
];

// Keycloak's "try another way" list on a flow with the passkey step first
// and the email code as the alternative (docs/passkeys.md).
export const PASSKEY_EXECUTION_ID = "exec-passkey";
export const OTP_EXECUTION_ID = "exec-otp";
const authenticationSelections = [
    {
        authExecId: PASSKEY_EXECUTION_ID,
        displayName: "webauthn-passwordless-display-name",
        helpText: "webauthn-passwordless-help-text",
        iconCssClass: "kcAuthenticatorWebAuthnPasswordlessClass"
    },
    {
        authExecId: OTP_EXECUTION_ID,
        displayName: "Tessera OTP",
        helpText: "Sends a one-time code to the user's email address for passwordless authentication.",
        iconCssClass: "kcAuthenticatorDefaultClass"
    }
];

const kcContextExtensionPerPage: KcContextExtensionPerPage = {
    "webauthn-authenticate.ftl": {
        social: { providers },
        auth: { authenticationSelections }
    },
    "webauthn-error.ftl": {
        auth: { authenticationSelections }
    },
    "login-tessera-otp-start.ftl": {
        social: { providers }
    },
    "login-tessera-otp-code.ftl": {
        sentToEmail: "user@example.com"
    },
    // No traceId by default: Keycloak only sets it when tracing is enabled.
    "error.ftl": {}
};

export const { getKcContextMock } = createGetKcContextMock({
    kcContextExtension,
    kcContextExtensionPerPage,
    overrides: {},
    overridesPerPage: {}
});

export function createKcPageStory<PageId extends KcContext["pageId"]>(params: {
    pageId: PageId;
}) {
    const { pageId } = params;

    function KcPageStory(props: {
        kcContext?: DeepPartial<Extract<KcContext, { pageId: PageId }>>;
    }) {
        const { kcContext: overrides } = props;

        const kcContextMock = getKcContextMock({
            pageId,
            overrides
        });

        return <KcPage kcContext={kcContextMock} />;
    }

    return { KcPageStory };
}
