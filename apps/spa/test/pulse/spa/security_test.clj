(ns pulse.spa.security-test
  "The policy of the workspace on the headers of its responses (ADR-0029, point 9). The headers on real responses are in
  `handler-test`, and what a browser does with the policy is in the acceptance."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.spa.security :as security]))

(def ^:private settings {:api-base-url "http://localhost:4000" :issuer "http://localhost:8080/realms/pulse"})

(defn- directives [policy]
  (into {} (for [d (str/split policy #"; ")
                 :let [[name & sources] (str/split d #" ")]]
             [name (vec sources)])))

(deftest the-page-is-allowed-what-it-ships-and-two-servers
  (is (= {"default-src" ["'self'"]
          "script-src" ["'self'"]
          "style-src" ["'self'"]
          "img-src" ["'self'" "data:"]
          "font-src" ["'self'"]
          "connect-src" ["'self'" "http://localhost:4000" "http://localhost:8080"]
          "object-src" ["'none'"]
          "frame-ancestors" ["'none'"]
          "base-uri" ["'none'"]
          "form-action" ["'none'"]}
         (directives (security/policy settings)))))

(deftest the-policy-names-the-origins-and-not-the-paths
  (let [policy (security/policy {:api-base-url "https://api.pulse.example/v1/" :issuer "https://id.pulse.example:8443/realms/pulse"})]
    (is (= ["'self'" "https://api.pulse.example" "https://id.pulse.example:8443"] (get (directives policy) "connect-src")))
    (is (not (str/includes? policy "/realms")))
    (is (not (str/includes? policy "/v1")))))

(deftest nothing-that-would-let-a-script-in-is-allowed
  (let [policy (security/policy settings)]
    (is (not (str/includes? policy "unsafe-inline")))
    (is (not (str/includes? policy "unsafe-eval")))
    (is (not (str/includes? policy "*")))
    (is (not (re-find #"(?:script|style|default)-src[^;]*https?:" policy)) "no other server may give the page a script or a style")))

(deftest an-address-that-is-not-one-names-no-origin
  (is (= ["'self'"] (get (directives (security/policy {:api-base-url "not a url" :issuer nil})) "connect-src"))))

(deftest the-headers-are-those-of-a-page-with-a-secret
  (let [{headers :headers} (security/header-options settings)]
    (is (= (security/policy settings) (get headers "Content-Security-Policy")))
    (is (= "nosniff" (get headers "X-Content-Type-Options")))
    (is (= "same-origin" (get headers "Referrer-Policy")))
    (is (= "DENY" (get headers "X-Frame-Options")))
    (is (= "same-origin" (get headers "Cross-Origin-Opener-Policy")))
    (is (= "same-origin" (get headers "Cross-Origin-Resource-Policy")))
    (is (= "no-store" (get headers "Cache-Control")) "what a file does not say of itself is not kept")
    (is (str/includes? (get headers "Permissions-Policy") "camera=()"))
    (is (not (contains? headers "Strict-Transport-Security")) "the local stack speaks http")))

(deftest an-address-that-is-https-gets-hsts
  (is (= "max-age=63072000; includeSubDomains" (get-in (security/header-options settings {:secure? true}) [:headers "Strict-Transport-Security"])))
  (is (not (contains? (:headers (security/header-options settings {:secure? false})) "Strict-Transport-Security"))))
