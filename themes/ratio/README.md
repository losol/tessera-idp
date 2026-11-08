# keycloak-ratio-theme

Keycloak **login theme** built with [Keycloakify](https://keycloakify.dev) and the
[`@eventuras/ratio-ui`](https://www.npmjs.com/package/@eventuras/ratio-ui)
design system.

One theme (`ratio`) serves every realm — the realm name is rendered from
`realm.displayName`, so no per-realm copy is needed.

## Stack

- Keycloakify v11 (React 19, Vite)
- `@eventuras/ratio-ui` (Tailwind v4 design system) — components, tokens, fonts
- Targets Keycloak 26

## Develop

Requires Node >= 20.

```bash
npm install
npm run dev        # Vite dev server — preview a page by uncommenting the
                   # getKcContextMock block in src/main.tsx
```

Source lives in `src/login/`:

- `KcPage.tsx` — page router (switches on `kcContext.pageId`)
- `Template.tsx` — shared shell in four layouts (see [Layout per client](#layout-per-client))
- `pages/` — per-page components (e.g. `Login.tsx`)
- `ProviderIcon.tsx` — brand marks for the Google, Apple and GitHub buttons
- `main.css` — imports Ratio CSS + fonts and the login layout

## Login methods per client

The passwordless start page (`login-tessera-otp-start.ftl`) reads the client
attribute **`tessera.login-methods`**: a comma-separated, ordered list of
identity-provider aliases and/or the literal `otp` (the email one-time-code
form).

| `tessera.login-methods` | Page |
| --- | --- |
| absent | Every realm provider as equal buttons, email code behind a link (the default) |
| `github` | Only GitHub |
| `otp` | Only the email form, open |
| `otp,github` | Email form on top, GitHub below the "or" divider |
| `github,otp` | GitHub as the filled primary button, email code behind a link |

- **The first entry leads.** Every listed method is on the same page; the first
  is the emphasised one (a lead provider gets the filled primary button, a lead
  `otp` puts the open email form first). The rest follow in list order.
- **Unknown entries are ignored**, so infra can list a method (e.g. `passkey`)
  before the theme can render it, or name a provider the realm does not enable.
- **Nothing renderable falls back to the default page**, never an empty one.

Provider buttons read "Continue with <display name>" (localised), with a brand
mark for Google, Apple and GitHub (matched on Keycloak's provider id, or the
alias for a generic OIDC provider named like one). Other providers get the
display name only.

This is presentation only: the email form and every provider stay reachable by
a crafted POST or a `kc_idp_hint`. Restricting a client for real is a
per-client browser flow on the Keycloak side.

Preview a layout in the smoke harness with `?pageId=login-tessera-otp-start.ftl&methods=otp,github`.

## Ratio theme per client

Two client attributes pick the Ratio theme the login pages wear for that
client (Admin console: Clients → the client → Advanced → Attributes, or
`attributes` in a realm import):

| Attribute | Values | Default |
| --- | --- | --- |
| `ratio.theme` | `standard`, `bureau`, `ink`, `tabula` | `standard` |
| `ratio.color-scheme` | `light`, `dark`, `auto` (follows the visitor's system setting) | `light` |

```json
"attributes": { "ratio.theme": "ink", "ratio.color-scheme": "dark" }
```

- **Absent or unknown values fall back to the default**, so a typo shows the
  standard light theme rather than a broken page.
- The theme is set on `<html>` (`data-theme`, `data-color-scheme`) before the
  first paint, the way Ratio's own theme files expect.
- **Pages without a client** (an unknown realm, some error pages) use the
  default theme.
- The named themes come from `@eventuras/ratio-ui/themes/*`. **Every theme
  uses Ratio's own fonts** (Source Serif 4, Source Sans 3): bureau and
  tabula's Fira Sans and Space Mono are overridden in `main.css` and their
  font files are not shipped.

Preview in the smoke harness with `?pageId=login-tessera-otp-start.ftl&theme=bureau&scheme=dark`.

## Layout per client

The client attribute `ratio.layout` picks the page layout:

| Value | Layout |
| --- | --- |
| `split` (default) | A brand panel in the theme's primary colour beside the form; on a phone it becomes a band above it. |
| `card` | A centred card, the theme's original layout. |
| `column` | One calm column under a slim top bar with the realm name, no card. |
| `panel` | The form in an edge panel; beside it, the service you sign in to (the client's name, description and a link back). |

Two more attributes add footer links, in every layout. A value that is not
an absolute `https://` or `http://` URL is left out.

| Attribute | Link |
| --- | --- |
| `ratio.help-url` | "Help" |
| `ratio.privacy-url` | "Privacy" |

The layouts show a few texts of their own. All of them can be set per client
like any other text (`ratio.text.<key>`, below); the empty ones stay hidden
until a client sets them.

| Key | Where | Default |
| --- | --- | --- |
| `ratioBrandEyebrow` | small label above the headline (`split`, `column`, `panel` without a client name) | empty |
| `ratioBrandHeadline` | the big line in the brand panel (`split`, `panel` without a client name) | "One sign-in for all your services." |
| `ratioBrandLead` | a sentence under the headline or the page title | empty |
| `ratioFooterNote` | a short note, e.g. who runs the sign-in | empty |
| `ratioSigningInTo`, `ratioBackTo` | "You are signing in to" / "Back to {0}" (`panel`) | |
| `ratioHelp`, `ratioPrivacy` | the footer link labels | "Help" / "Privacy" |

The `panel` layout reads the client's **Name** and **Description** (Clients →
the client → Settings); both may be `${key}` references to realm localization.
Without a name it shows the brand headline instead.

```json
"attributes": {
  "ratio.layout": "split",
  "ratio.theme": "standard",
  "ratio.help-url": "https://example.org/help",
  "ratio.text.ratioBrandHeadline.nb": "Én innlogging til alle tjenestene i kommunen."
}
```

Preview in the smoke harness with
`?pageId=login-tessera-otp-start.ftl&attr.ratio.layout=panel&clientName=Eventuras`.

## Favicon

`public/favicon.svg` is the Tessera mark: four mosaic tiles on Linseed blue,
one in ochre. `favicon-32x32.png` and `apple-touch-icon.png` are rendered
from it; regenerate them if the SVG changes.

## Texts per client

Any text the login pages show can be replaced for one client with a client
attribute named after its message key:

| Attribute | Applies to |
| --- | --- |
| `ratio.text.<messageKey>` | every language |
| `ratio.text.<messageKey>.<lang>` | one language, e.g. `.nb` or `.en`; wins over the line above |

The value is either the text itself or `${realmKey}`, a reference to a text
under Realm settings → Localization → Realm overrides, where Keycloak keeps one
value per language. Use a reference when the realm offers several languages
and you would rather keep the translations in one place.

```json
"attributes": {
  "ratio.text.tesseraOtpEmailTitle.nb": "Logg inn på Min side",
  "ratio.text.tesseraOtpEmailTitle.en": "Sign in to My page",
  "ratio.text.tesseraContinueWith": "${losvikContinueWith}"
}
```

- `{0}`, `{1}` … are filled in as in the theme's own messages, e.g.
  `ratio.text.tesseraContinueWith` = `Bruk {0}` gives "Bruk GitHub".
- **The texts are plain text.** Markup in an attribute shows as typed and is
  never interpreted as HTML.
- An empty value, an unknown key or a reference Keycloak cannot resolve falls
  back to the theme's own text.

The keys are the theme's messages in [`src/login/i18n.ts`](src/login/i18n.ts)
(e.g. `tesseraOtpEmailTitle`, `tesseraOtpEmailInstruction`, `tesseraOtpUseCode`,
`tesseraContinueWith`, `tesseraOtpTitle`, `tesseraOtpInstruction`) plus
Keycloak's standard login messages (e.g. `loginTitle` for the browser tab,
`doSubmit`, `errorTitle`).

Preview in the smoke harness with `?pageId=login-tessera-otp-start.ftl&text.tesseraOtpEmailTitle=Hei`.

## Build

```bash
npm run build-keycloak-theme
```

Produces `dist_keycloak/keycloak-ratio-theme.jar` (a Keycloak theme
provider JAR).

## Deployment

The JAR is **not** released on its own. It is baked into the custom Keycloak
image by the repo-root [`Dockerfile`](../../Dockerfile) (a Node build stage) into
`/opt/keycloak/providers/`, and published together with that image.

To activate it on a realm, set the realm's `loginTheme` to `ratio` (via the Admin
console, a realm import, or the Admin REST API) — only once an image containing
the theme has been deployed.
