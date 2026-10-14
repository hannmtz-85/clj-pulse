(ns pulse.api.security
  "The api's policy on the headers of its responses (ADR-0018): which they are, and the one rule for
  the page of Swagger UI. The mechanism is `pulse.platform.http.headers`; the origins CORS lets in
  are configuration, in `pulse.api.config`."
  (:require [clojure.string :as str]))

(def ^:private api-policy
  "What a response of the api, which is JSON and nothing a browser should run or show, may load: nothing."
  "default-src 'none'; frame-ancestors 'none'")

(def headers
  "On every response, unless it sets one itself. The answers are clinical data, so nothing may be
  cached; the body is what the `Content-Type` says, never something a browser guessed; nothing
  may frame it, and nothing it links to is told where the reader came from."
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "no-referrer"
   "X-Frame-Options" "DENY"
   "Permissions-Policy" "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
   "Cross-Origin-Resource-Policy" "same-site"
   "Cache-Control" "no-store"
   "Content-Security-Policy" api-policy})

(defn origin-of
  "The origin of `url`: its scheme, host and port. `\"http://localhost:8080/realms/pulse\"` is
  `\"http://localhost:8080\"`. Nil when `url` does not start like an http(s) URL."
  [url]
  (re-find #"^https?://[^/?#\s]+" (str url)))

(defn docs-policy
  "The Content-Security-Policy of the page of Swagger UI, which runs a script of its own: it may
  load what this server serves, call this server and the identity provider at `issuer` (the token
  of its sign-in comes from there), and nothing else. It may not be framed, nor load plugins, nor
  change the base of its links, and a form of it may only go home."
  [issuer]
  (str/join "; " ["default-src 'none'"
                  "script-src 'self'"
                  ;; Swagger UI sets styles on its elements from script
                  "style-src 'self' 'unsafe-inline'"
                  "img-src 'self' data:"
                  "font-src 'self' data:"
                  (str "connect-src 'self'" (some->> (origin-of issuer) (str " ")))
                  "frame-ancestors 'none'"
                  "base-uri 'none'"
                  "object-src 'none'"
                  "form-action 'self'"]))

(defn header-options
  "The options of `pulse.platform.http.headers/wrap-security-headers`: the `headers` for every
  response, and for the pages under `/docs` a Content-Security-Policy that lets Swagger UI run and
  no `Cache-Control`, so that its bundle can be cached. `issuer` is the identity provider's, as
  a browser reaches it."
  [issuer]
  {:headers headers
   :rules [{:prefix "/docs"
            :headers {"Content-Security-Policy" (docs-policy issuer)
                      "Cache-Control" nil}}]})
