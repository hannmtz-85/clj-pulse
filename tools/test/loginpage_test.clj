(ns loginpage-test
  "Finding the address of the sign-in form in the login page of Keycloak, in the theme of Keycloak and in the theme of
  Pulse, which write the attributes of the tag in another order."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loginpage]))

(def ^:private address
  "http://localhost:8080/realms/pulse/login-actions/authenticate?session_code=abc&execution=1&client_id=pulse-docs&tab_id=xyz")

(def ^:private escaped (str/replace address "&" "&amp;"))

(deftest the-form-of-keycloak-has-its-id-before-its-address
  (is (= address
         (loginpage/form-action (str "<div><form id=\"kc-form-login\" onsubmit=\"login.disabled = true; return true;\" "
                                     "action=\"" escaped "\" method=\"post\"><input name=\"username\"></form></div>")))))

(deftest the-form-of-pulse-has-its-attributes-in-alphabetical-order-and-its-address-first
  (is (= address
         (loginpage/form-action (str "<form action=\"" escaped "\" class=\"flex flex-col gap-5\" id=\"kc-form-login\" "
                                     "method=\"post\" onsubmit=\"login.disabled = true; return true;\">")))))

(deftest a-form-that-is-not-the-sign-in-is-not-the-one
  (let [other "<form id=\"kc-select-try-another-way-form\" action=\"http://other/\" method=\"post\">"
        sign-in (str "<form id=\"kc-form-login\" action=\"" escaped "\" method=\"post\">")]
    (is (nil? (loginpage/form-action other)))
    (is (= address (loginpage/form-action (str other sign-in))) "the sign-in is found after another form")
    (is (= address (loginpage/form-action (str sign-in other))) "and before it")
    (is (nil? (loginpage/form-action "<p>No form here</p>")))
    (is (nil? (loginpage/form-action nil)))
    (is (nil? (loginpage/form-action "<form id=\"kc-form-login\" method=\"post\">")) "a form with no address")))

(deftest an-id-that-only-ends-in-the-name-is-not-the-name
  (is (nil? (loginpage/form-action "<form id=\"not-kc-form-login\" action=\"http://x/\">")))
  (is (nil? (loginpage/form-action "<form data-id=\"kc-form-login\" action=\"http://x/\">")) "nor an attribute that ends in id"))

(deftest the-entities-of-an-address-are-put-back
  (is (= "http://x/?a=1&b=\"2\"&c==" (loginpage/html-unescape "http://x/?a&#x3D;1&amp;b=&quot;2&quot;&amp;c&#x3D;&#x3D;"))))

(deftest the-sign-in-of-the-theme-of-pulse-can-be-read
  (testing "its template has the form, and the address is the one Keycloak puts in"
    (let [template (io/file "infra/keycloak/themes/pulse/login/login.ftl")]
      (is (.exists template))
      (is (= "${url.loginAction}" (loginpage/form-action (slurp template :encoding "UTF-8")))))))
