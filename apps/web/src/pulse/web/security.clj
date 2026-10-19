(ns pulse.web.security
  "The portal's policy on the headers of its responses (ADR-0028, ADR-0018). The mechanism is
  `pulse.platform.http.headers`. The pages show a patient's own record, so nothing may be cached; the pages load only what
  this server serves, and no script or style of their own, so a policy that forbids all inline code can be strict;
  and the one place a form may send its data to besides this server is the identity provider, whose answer to signing
  out is a redirect (Chrome applies `form-action` to the redirect that follows a form, and blocks it otherwise).
  HSTS, and the `Secure` flag of the cookie, come with the sign-in, since they depend on the address the portal is
  served at."
  (:require [clojure.string :as str]
            [pulse.platform.http.headers :as headers]))

(defn policy
  "The Content-Security-Policy of every page. `issuer` is the identity provider as a browser reaches it: its origin is
  where a form of the portal may lead, besides the portal itself. Images may be `data:` because the components of the
  design draw their arrows and marks with them; a font, a script and a stylesheet are this server's, nothing is
  framed, nothing is a plugin, and `base` cannot be changed by an injected element."
  [issuer]
  (str/join "; " ["default-src 'self'"
                  "script-src 'self'"
                  "style-src 'self'"
                  "img-src 'self' data:"
                  "font-src 'self'"
                  "connect-src 'self'"
                  "object-src 'none'"
                  "frame-ancestors 'none'"
                  "base-uri 'none'"
                  (str "form-action 'self'" (some->> (headers/origin-of issuer) (str " ")))]))

(def defaults
  "On every response, unless it sets one itself (the files of the portal say how long they may be kept). The pages are a
  patient's record, so nothing may be cached; the body is what the `Content-Type` says; nothing may frame it.

  The Referrer-Policy is `same-origin` and not `no-referrer` on purpose: with `no-referrer` a browser sends `Origin:
  null` with the form it posts to its own server, and the check of the origin of a request that changes something
  (ADR-0028) could not tell a form of the portal from one that a sandboxed page made. With `same-origin` the form
  carries the portal's origin, and the identity provider is told nothing of where the person was."
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "same-origin"
   "X-Frame-Options" "DENY"
   "Permissions-Policy" "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
   "Cross-Origin-Opener-Policy" "same-origin"
   "Cross-Origin-Resource-Policy" "same-origin"
   "Cache-Control" "no-store"})

(defn header-options
  "The options of `pulse.platform.http.headers/wrap-security-headers`: the `defaults` and the policy for `issuer`."
  [issuer]
  {:headers (assoc defaults "Content-Security-Policy" (policy issuer))})
