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
