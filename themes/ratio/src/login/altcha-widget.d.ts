import type { DetailedHTMLProps, HTMLAttributes } from "react";

/**
 * JSX typing for the ALTCHA custom element. The `altcha` package (v3) no longer
 * ships its own JSX augmentation, and React 19 exposes the JSX namespace from
 * the "react" module (React.JSX) rather than globally — so declare the element
 * (and the attributes we use) by augmenting the react module's IntrinsicElements.
 * `challenge` carries the self-contained, server-signed challenge (JSON string);
 * v3 replaced v2's `challengejson`/`challengeurl` with this single attribute.
 */
declare module "react" {
    namespace JSX {
        interface IntrinsicElements {
            "altcha-widget": DetailedHTMLProps<HTMLAttributes<HTMLElement>, HTMLElement> & {
                challenge?: string;
                name?: string;
            };
        }
    }
}
