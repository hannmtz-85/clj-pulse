(ns pulse.web.security-test
  "The policy of the portal's pages (ADR-0028): what a page may load and where a form may lead, and the headers that go
  with every answer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.web.security :as security]))

(def ^:private issuer "http://localhost:8080/realms/pulse")

(defn- directives
  "The policy as `{directive [sources]}`."
  [policy]
  (into {} (for [part (str/split policy #";\s*")
                 :let [[directive & sources] (str/split part #"\s+")]]
             [directive (vec sources)])))

(deftest the-policy-lets-a-page-load-what-this-server-serves-and-nothing-else
  (let [d (directives (security/policy issuer))]
    (is (= ["'self'"] (get d "default-src")))
    (is (= ["'self'"] (get d "script-src")) "no inline script, no eval, no other server")
    (is (= ["'self'"] (get d "style-src")) "no inline style")
    (is (= ["'self'"] (get d "font-src")))
    (is (= ["'self'"] (get d "connect-src")) "htmx asks this server, and the script of a page asks no other")
    (is (= ["'self'" "data:"] (get d "img-src")) "the arrows and marks that the components draw are data: addresses")
    (is (= ["'none'"] (get d "object-src")))
    (is (= ["'none'"] (get d "frame-ancestors")) "nothing may frame a page of a patient's record")
    (is (= ["'none'"] (get d "base-uri")) "an element injected into a page cannot change where its links lead")))

(deftest a-form-may-lead-home-and-to-the-identity-provider-and-nowhere-else
  (testing "to the origin of Keycloak, without its path"
    (is (= ["'self'" "http://localhost:8080"] (get (directives (security/policy issuer)) "form-action")))
    (is (= ["'self'" "https://id.example"]
           (get (directives (security/policy "https://id.example/realms/pulse")) "form-action"))))
  (testing "to itself alone when there is no identity provider to name"
    (is (= ["'self'"] (get (directives (security/policy nil)) "form-action")))
    (is (= ["'self'"] (get (directives (security/policy "not an address")) "form-action")))))

(deftest the-policy-has-nothing-that-weakens-it
  (let [policy (security/policy issuer)]
    (doseq [weak ["'unsafe-inline'" "'unsafe-eval'" "'unsafe-hashes'" "'strict-dynamic'" "blob:" "filesystem:" " *"]]
      (is (not (str/includes? policy weak)) weak))
    (testing "no source is a whole scheme, which would be any server that speaks it"
      (is (nil? (re-find #"\shttps?:(\s|;|$)" policy))))
    (testing "the only other server it names is the identity provider's, and only for forms"
      (is (= ["http://localhost:8080"] (re-seq #"https?://[^\s;]+" policy)))
      (is (str/includes? policy "form-action 'self' http://localhost:8080")))))

(deftest every-directive-the-policy-needs-is-there
  (is (= #{"default-src" "script-src" "style-src" "img-src" "font-src" "connect-src" "object-src" "frame-ancestors"
           "base-uri" "form-action"}
         (set (keys (directives (security/policy issuer)))))))

(deftest the-headers-are-those-of-a-page-of-a-patients-record
  (let [{:keys [headers]} (security/header-options issuer)]
    (is (= "nosniff" (get headers "X-Content-Type-Options")))
    (is (= "DENY" (get headers "X-Frame-Options")))
    (is (= "no-store" (get headers "Cache-Control")) "nothing of a record is kept")
    (is (= "same-origin" (get headers "Cross-Origin-Opener-Policy")))
    (is (= "same-origin" (get headers "Cross-Origin-Resource-Policy")))
    (is (= (security/policy issuer) (get headers "Content-Security-Policy")))
    (testing "the camera, the microphone and the rest are off"
      (doseq [feature ["camera" "microphone" "geolocation" "payment" "usb"]]
        (is (str/includes? (get headers "Permissions-Policy") (str feature "=()")) feature)))))

(deftest the-referrer-policy-keeps-the-origin-of-a-form-that-goes-home
  ;; With `no-referrer` a browser sends `Origin: null` with a form it posts to its own server, and the check of the origin
  ;; of a request that changes something (ADR-0028) could not tell it from a page that a stranger made in a sandbox.
  (let [policy (get-in (security/header-options issuer) [:headers "Referrer-Policy"])]
    (is (= "same-origin" policy))
    (is (not= "no-referrer" policy))))
