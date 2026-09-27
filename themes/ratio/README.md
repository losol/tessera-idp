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
- `Template.tsx` — shared shell (card, brand, footer)
- `pages/` — per-page components (e.g. `Login.tsx`)
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

This is presentation only: the email form and every provider stay reachable by
a crafted POST or a `kc_idp_hint`. Restricting a client for real is a
per-client browser flow on the Keycloak side.

Preview a layout in the smoke harness with `?pageId=login-tessera-otp-start.ftl&methods=otp,github`.

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
