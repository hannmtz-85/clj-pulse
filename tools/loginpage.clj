(ns loginpage
  "Reading the login page of Keycloak without a browser, which is what `bb token` and `bb realm:smoke` do to sign
  in as a demo user. The form is found by its id and its address read from its tag, whatever order the theme writes
  the attributes of the tag in: the theme of Pulse is made with Hiccup, which writes them in alphabetical order
  (ADR-0026). The functions are pure, so the `.env` tooling and the tests of the theme can both use them."
  (:require [clojure.string :as str]))

(defn html-unescape
  "The text of an attribute with the few entities Keycloak writes in an address put back."
  [s]
  (-> s (str/replace "&amp;" "&") (str/replace "&quot;" "\"") (str/replace "&#x3D;" "=")))

(defn form-action
  "The address that the sign-in form of `html` is sent to (the `action` of the `<form>` that has the id
  `kc-form-login`), or nil when the page has no such form."
  [html]
  (when-let [tag (re-find #"<form\b[^>]*(?<![\w-])id=\"kc-form-login\"[^>]*>" (str html))]
    (some-> (re-find #"(?<![\w-])action=\"([^\"]+)\"" tag) second html-unescape)))
