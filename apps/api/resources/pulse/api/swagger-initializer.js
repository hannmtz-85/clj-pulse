// Starts Swagger UI for the Pulse API (ADR-0017). It stands in for the script of the same name in
// the ring-swagger-ui jar, which cannot be told which client to sign in with.
//
// The only change from the jar's script is `initOAuth`: the sign-in is the Authorization Code flow
// with PKCE (S256) for `pulse-docs`, a public client of the realm, so there is no secret anywhere
// in this page. The address of the document and the validator setting come from ./config.json,
// as in the jar's script. Nothing here is inline in the page: its Content-Security-Policy allows
// scripts from this server only.
window.onload = function () {
  window.ui = SwaggerUIBundle({
    url: "/openapi.json",
    dom_id: "#swagger-ui",
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    plugins: [SwaggerUIBundle.plugins.DownloadUrl],
    configUrl: "./config.json",
    layout: "StandaloneLayout",
  });

  window.ui.initOAuth({
    clientId: "pulse-docs",
    appName: "Pulse API",
    scopes: "openid",
    usePkceWithAuthorizationCodeGrant: true,
  });
};
