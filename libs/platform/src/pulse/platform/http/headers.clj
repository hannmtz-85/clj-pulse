(ns pulse.platform.http.headers
  "Security headers for every response (ADR-0018), and the few helpers on response headers that
  CORS shares with it. Plain Ring middleware, with no policy of its own: what the headers are is
  the service's to say.

  Header names are case-insensitive, so a response that already has `cache-control` is not given a
  second `Cache-Control`."
  (:require [clojure.string :as str]))

(defn header-key
  "The key under which `headers` holds `name`, whatever its case, or nil."
  [headers name]
  (let [wanted (str/lower-case name)]
    (some #(when (= wanted (str/lower-case %)) %) (keys headers))))

(defn origin-of
  "The origin of `url`: its scheme, host and port. `\"http://localhost:8080/realms/pulse\"` is
  `\"http://localhost:8080\"`. Nil when `url` does not start like an http(s) URL. A policy that names
  the identity provider (where the page may send a form or call from script) names its origin."
  [url]
  (re-find #"^https?://[^/?#\s]+" (str url)))

(defn assoc-missing
  "`response` with each header of `headers`, a map from name to value, that it does not have
  already. A nil value means no header at all."
  [response headers]
  (reduce-kv (fn [response header-name value]
               (if (or (nil? value) (header-key (:headers response) header-name))
                 response
                 (assoc-in response [:headers header-name] value)))
             response
             headers))

(defn- under?
  "Whether `uri` is `prefix` or lies below it, by whole path segments: \"/docs\" covers \"/docs\"
  and \"/docs/index.html\", not \"/docsx\"."
  [prefix uri]
  (or (= prefix uri)
      (str/starts-with? (str uri) (str prefix "/"))))

(defn wrap-security-headers
  "Adds `:headers`, a map from name to value, to every response that does not set them itself,
  whoever made it: a handler, an error, the router's 404, a file. `:rules` is a vector of
  `{:prefix \"/docs\" :headers {...}}`: the first whose prefix is the request path, or lies above
  it, replaces the defaults it names, and a nil value removes a default. A response that is nil
  stays nil."
  [handler {:keys [headers rules]}]
  (fn [request]
    (let [rule (first (filter #(under? (:prefix %) (:uri request)) rules))]
      (some-> (handler request)
              (assoc-missing (merge headers (:headers rule)))))))
