(ns pulse.spa.security
  "The policy of the workspace on the headers of its responses (ADR-0029, point 9; ADR-0018, ADR-0028). The mechanism is
  `pulse.platform.http.headers`.

  This page holds the token of the person in the memory of its JavaScript, which is out of the reach of the server: whatever runs
  in the page can read it. So the policy is the strictest that the page can live with: its scripts, styles, fonts and images are
  this server's, no inline code and no `eval`, nothing is framed, nothing is a plugin, no form leaves it, and the page may call
  only the api and the identity provider (the code of the sign-in is exchanged for the tokens by a `fetch` to it). A script that
  the page did not ship cannot run, and cannot send the token anywhere else."
  (:require [clojure.string :as str]
            [pulse.platform.http.headers :as headers]))

(defn policy
  "The Content-Security-Policy of every response. `api-base-url` and `issuer` are the api and the identity provider as the browser
  reaches them: their origins are the only places besides this server that the page may connect to. Images may be `data:`
  because the components of the design draw their arrows and marks with them."
  [{:keys [api-base-url issuer]}]
  (str/join "; " ["default-src 'self'"
                  "script-src 'self'"
                  "style-src 'self'"
                  "img-src 'self' data:"
                  "font-src 'self'"
                  (str/join " " (remove nil? ["connect-src 'self'" (headers/origin-of api-base-url) (headers/origin-of issuer)]))
                  "object-src 'none'"
                  "frame-ancestors 'none'"
                  "base-uri 'none'"
                  "form-action 'none'"]))

(def defaults
  "On every response, unless it sets one itself (the files say how long they may be kept). The body is what the `Content-Type`
  says and nothing may frame it. The Referrer-Policy is `same-origin`: the identity provider and the api are told nothing of
  where in the workspace the person was."
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "same-origin"
   "X-Frame-Options" "DENY"
   "Permissions-Policy" "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
   "Cross-Origin-Opener-Policy" "same-origin"
   "Cross-Origin-Resource-Policy" "same-origin"
   "Cache-Control" "no-store"})

(def hsts
  "What the browser is told to remember about a workspace that is served over https: only https, for two years, subdomains too."
  "max-age=63072000; includeSubDomains")

(defn header-options
  "The options of `pulse.platform.http.headers/wrap-security-headers`: the `defaults` and the policy for `settings` (`:api-base-url`
  and `:issuer`), and, when the workspace is served over https (`:secure?`), HSTS."
  ([settings] (header-options settings nil))
  ([settings {:keys [secure?]}]
   {:headers (cond-> (assoc defaults "Content-Security-Policy" (policy settings))
               secure? (assoc "Strict-Transport-Security" hsts))}))
