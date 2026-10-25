(ns pulse.spa.handler
  "Assembles the Ring handler of the server of the workspace's release (ADR-0029, point 9). From the outside in: the request id, the
  access log, the security headers (the policy of `pulse.spa.security`, on every response, an error among them) and the exception
  handler, around the files. `/healthz` says that the process is serving."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.http.headers :as headers]
            [pulse.platform.http.middleware :as mw]
            [pulse.spa.files :as files]
            [pulse.spa.security :as security]
            [ring.middleware.not-modified :as not-modified]))

(defn- liveness [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string {:status "up"})})

(defn ring-handler
  "The Ring handler for the files under `:root`. `options` are `:root`, `:api-base-url` and `:issuer` (what the policy names),
  `:quiet-paths` (probes, logged at debug) and `:base-url`, the address the workspace is served at, whose scheme says whether
  the headers are those of https."
  [{:keys [root api-base-url issuer quiet-paths base-url]}]
  (let [files (not-modified/wrap-not-modified (files/handler root))
        secure? (str/starts-with? (str base-url) "https://")]
    (-> (fn [request]
          (if (= "/healthz" (:uri request))
            (liveness request)
            (files request)))
        (mw/wrap-exceptions {})
        (headers/wrap-security-headers (security/header-options {:api-base-url api-base-url :issuer issuer} {:secure? secure?}))
        (mw/wrap-access-log {:quiet-paths quiet-paths})
        (mw/wrap-request-id))))

(defn create
  "The handler of the `:pulse.spa/handler` component."
  [options]
  (ring-handler options))
