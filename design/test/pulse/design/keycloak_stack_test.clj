(ns pulse.design.keycloak-stack-test
  "Integration test: the login theme as the real Keycloak of the local stack serves it (ADR-0026). The pages are read
  from the stack, read back as HTML and put through the checks of accessibility, and the stylesheet, the fonts, the icon
  and the scripts they ask for are fetched. None of it can be seen by the tests that fill the templates in with FreeMarker
  and a stand-in model (`keycloak-render-test`): that Keycloak finds the theme, that it serves its resources and the ones
  of `base` that it extends, and that the pages it makes of its own model are the pages those tests expect.

  Needs the stack (`bb up`), whose Keycloak has the theme mounted from `infra/keycloak/themes`, and runs with
  `bb test:it`. It only reads, except that it signs in once with a name that nobody has and a password that is not one, and
  confirms a sign-out that has no session to end. It changes nothing in the realm."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.keycloak :as kc]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as html])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private base "http://localhost:8080")

(def ^:private openid (str base "/realms/pulse/protocol/openid-connect"))

(defn- encode [s] (URLEncoder/encode (str s) "UTF-8"))

(def ^:private sign-in-url
  "The address that Swagger UI sends a person to, which is the sign-in of the client `pulse-docs`."
  (str openid "/auth?client_id=pulse-docs&redirect_uri=" (encode "http://localhost:4000/docs/oauth2-redirect.html")
       "&response_type=code&scope=openid&state=s&nonce=n&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"))

(defn- session
  "A browser: a client that does not follow redirects, and the cookies that Keycloak has set, which the flows of its login
  need. The cookies are kept here and not by `java.net.CookieManager`, which refuses those of a host with no dot in its
  name, such as `localhost`."
  []
  {:client (-> (HttpClient/newBuilder)
               (.followRedirects HttpClient$Redirect/NEVER)
               (.connectTimeout (Duration/ofSeconds 5))
               (.build))
   :cookies (atom {})})

(defn- request
  ^HttpRequest [url form cookies]
  (let [builder (cond-> (-> (HttpRequest/newBuilder (URI/create url)) (.timeout (Duration/ofSeconds 10)))
                  (seq cookies) (.header "Cookie" (str/join "; " (for [[k v] cookies] (str k "=" v)))))]
    (if form
      (-> builder
          (.header "Content-Type" "application/x-www-form-urlencoded")
          (.POST (HttpRequest$BodyPublishers/ofString (str/join "&" (for [[k v] form] (str (encode k) "=" (encode v))))))
          (.build))
      (-> builder (.GET) (.build)))))

(defn- fetch
  "`{:status :headers :body}` of `url`, with its headers' names in lower case, as a string; `:form` makes it a POST of
  those fields. `:bytes?` keeps the body as it is, for a font. The cookies of the answer are kept for the next request."
  [{:keys [^HttpClient client cookies]} url & {:keys [form bytes?]}]
  (let [response (.send client (request url form @cookies)
                        (if bytes? (HttpResponse$BodyHandlers/ofByteArray) (HttpResponse$BodyHandlers/ofString)))]
    (doseq [header (.allValues (.headers response) "set-cookie")
            :let [[_ cookie-name value] (re-find #"^([^=;\s]+)=([^;]*)" header)]
            :when cookie-name]
      (swap! cookies assoc cookie-name value))
    {:status (.statusCode response)
     :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)])) (.map (.headers response)))
     :body (.body response)}))

(defn- page
  "`fetch` and read as HTML: `{:status :headers :doc :html}`."
  [browser url & options]
  (let [{:keys [body] :as response} (apply fetch browser url options)]
    (assoc (dissoc response :body) :doc (html/document body) :html body)))

(defn- resolve-against [url reference]
  (str (.resolve (URI/create url) ^String reference)))

(defn- words-of [els] (map hx/words els))

(defn- alerts [node] (hx/select node #(= "alert" (:role (hx/attributes %)))))

(defn- buttons [node] (hx/select node #(hx/has-class? % "btn")))

(defn- accessible? [doc]
  (is (= [] (a11y/problems doc {:page? true}))))

(defn- html? [headers]
  (is (str/starts-with? (str (headers "content-type")) "text/html")))

;; ---------------------------------------------------------------------------

(deftest ^:integration the-sign-in-keycloak-serves-is-the-page-of-the-theme
  (let [browser (session)
        {:keys [status headers doc]} (page browser sign-in-url)
        form (hx/by-id doc "kc-form-login")]
    (is (= 200 status))
    (html? headers)
    (accessible? doc)
    (is (= "en" (:lang (hx/attributes doc))))
    (is (= "Sign in to Pulse" (hx/words (first (hx/by-tag doc :title)))) "the name of the realm is in the title")
    (is (= ["Sign in"] (words-of (hx/by-tag doc :h1))))
    (is (= "to continue to Pulse API documentation (Swagger UI)"
           (hx/words (first (filter #(hx/has-class? % "text-base") (hx/by-tag doc :p)))))
        "the name of the client, as the realm gives it (tools/realm.clj)")
    (is (= ["Email" "Password"] (words-of (hx/by-tag form :label))))
    (is (str/starts-with? (:action (hx/attributes form)) (str base "/realms/pulse/login-actions/authenticate?")))
    (is (= "post" (:method (hx/attributes form))))
    (is (= "Sign in" (hx/words (hx/by-id doc "kc-login"))))
    (is (empty? (alerts doc)))
    (is (empty? (hx/select doc #(= "checkbox" (:type (hx/attributes %))))) "this realm does not offer to be remembered")
    (is (empty? (hx/by-tag form :a)) "nor a new password by email")
    (is (= "No account yet? Your clinic gives you one." (hx/words (hx/by-id doc "kc-info"))))
    (is (= (kc/message :demoNotice) (hx/words (last (hx/by-tag (first (hx/by-tag doc :main)) :p)))))
    (testing "Keycloak's own response headers keep the page out of a frame"
      (is (contains? headers "x-frame-options")))))

(deftest ^:integration the-resources-the-page-asks-for-are-served
  (let [browser (session)
        {:keys [doc]} (page browser sign-in-url)
        stylesheet-url (resolve-against sign-in-url (:href (hx/attributes (first (hx/select doc #(= "stylesheet" (:rel (hx/attributes %))))))))
        icon-url (resolve-against sign-in-url (:href (hx/attributes (first (hx/select doc #(= "icon" (:rel (hx/attributes %))))))))]
    (testing "the stylesheet of the theme"
      (let [{:keys [status headers body]} (fetch browser stylesheet-url)]
        (is (= 200 status))
        (is (str/starts-with? (headers "content-type") "text/css"))
        (is (= (slurp "infra/keycloak/themes/pulse/login/resources/pulse.css" :encoding "UTF-8") body)
            "the file that is committed, byte for byte: a stack that serves another one has an old theme mounted")
        (testing "and the fonts it asks for, which are the files of the design"
          (let [fonts (re-seq #"(?<=url\(fonts/)[^)]+(?=\))" body)]
            (is (= (set (kc/font-files)) (set fonts)))
            (doseq [font fonts
                    :let [served (fetch browser (resolve-against stylesheet-url (str "fonts/" font)) :bytes? true)]]
              (testing font
                (is (= 200 (:status served)))
                (is (re-find #"font/woff2|application/octet-stream|application/font-woff2" (str (get-in served [:headers "content-type"]))))
                (is (java.util.Arrays/equals ^bytes (:body served)
                                             ^bytes (java.nio.file.Files/readAllBytes (.toPath (io/file "design/fonts" font))))
                    "the font of the design, byte for byte")))))))
    (testing "the icon"
      (let [{:keys [status headers body]} (fetch browser icon-url)]
        (is (= 200 status))
        (is (str/starts-with? (headers "content-type") "image/svg+xml"))
        (is (= (slurp "infra/keycloak/themes/pulse/login/resources/img/favicon.svg" :encoding "UTF-8") body))))
    (testing "the scripts the page loads: Keycloak's own, which the theme gets from `base` and `common`"
      (let [scripts (mapcat #(re-seq #"(/resources/[^\"]+\.js)" (hx/words %)) (hx/by-tag doc :script))]
        (is (= 2 (count scripts)) "the one that watches the other tabs, and the library an import map names")
        (doseq [[_ path] scripts
                :let [{:keys [status headers]} (fetch browser (resolve-against sign-in-url path))]]
          (testing path
            (is (= 200 status))
            (is (re-find #"javascript" (str (headers "content-type"))))))))))

(deftest ^:integration a-wrong-password-is-said-in-words-and-the-page-comes-back-ready-to-try-again
  (let [browser (session)
        {:keys [doc]} (page browser sign-in-url)
        action (:action (hx/attributes (hx/by-id doc "kc-form-login")))
        {:keys [status headers doc]} (page browser action :form {"username" "nobody@pulse.test"
                                                                 "password" "this is not the password"
                                                                 "credentialId" ""})
        form (hx/by-id doc "kc-form-login")
        [alert & more] (alerts doc)]
    (is (= 200 status))
    (html? headers)
    (accessible? doc)
    (is (empty? more) "once")
    (is (= (kc/message :invalidUserMessage) (hx/words (first (hx/by-tag alert :p)))))
    (is (str/includes? (hx/words alert) (kc/message :loginErrorHint)))
    (is (hx/has-class? alert "bg-tone-critical-soft"))
    (is (= ["true" "true"] (map #(:aria-invalid (hx/attributes (hx/by-id form %))) ["username" "password"])))
    (is (= "nobody@pulse.test" (:value (hx/attributes (hx/by-id form "username")))) "what was typed is kept")
    (is (true? (:autofocus (hx/attributes (hx/by-id form "password")))) "and the cursor is on the password")
    (is (str/starts-with? (:action (hx/attributes form)) (str base "/realms/pulse/login-actions/authenticate?")))))

(deftest ^:integration a-request-that-keycloak-refuses-is-an-error-in-the-theme
  (let [browser (session)
        {:keys [status headers doc]} (page browser (str openid "/auth?client_id=there-is-no-such-client&redirect_uri="
                                                        (encode "http://localhost:4000/") "&response_type=code"))
        [alert & more] (alerts doc)]
    (is (= 400 status))
    (html? headers)
    (accessible? doc)
    (is (= ["Something went wrong"] (words-of (hx/by-tag doc :h1))))
    (is (empty? more))
    (is (seq (hx/words alert)) "Keycloak says what is wrong")
    (is (hx/has-class? alert "bg-tone-critical-soft"))
    (is (empty? (buttons doc)) "with no client there is no application to go back to")))

(deftest ^:integration signing-out-is-a-question-and-then-a-message
  (let [browser (session)
        logout-url (str openid "/logout")
        {:keys [status doc]} (page browser logout-url)
        form (first (hx/by-tag doc :form))
        code (:value (hx/attributes (first (hx/select form #(= "session_code" (:name (hx/attributes %)))))))]
    (is (= 200 status))
    (accessible? doc)
    (is (= ["Sign out?"] (words-of (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words doc) "Do you want to sign out of Pulse?"))
    (is (= ["Yes, sign me out"] (words-of (buttons doc))) "with no client to stay in, only the one way")
    (is (seq code) "Keycloak gives the code that the form sends back")
    (testing "answering yes ends the session, if there is one, and says so"
      (let [{:keys [status doc]} (page browser (resolve-against logout-url (:action (hx/attributes form)))
                                       :form {"session_code" code "confirmLogout" ""})]
        (is (= 200 status))
        (accessible? doc)
        (is (= ["You have been signed out"] (words-of (hx/by-tag doc :h1))))
        (is (empty? (buttons doc)) "and a card with only a title has nothing else in it")))))

(deftest ^:integration a-login-page-that-has-expired-offers-to-start-again
  (let [browser (session)
        {:keys [doc]} (page browser sign-in-url)
        action (:action (hx/attributes (hx/by-id doc "kc-form-login")))
        stale (str/replace action #"execution=[^&]*" "execution=00000000-0000-0000-0000-000000000000")
        {:keys [status headers doc]} (page browser stale)
        [restart go-on] (buttons doc)]
    (is (not= action stale))
    (is (= 200 status))
    (html? headers)
    (accessible? doc)
    (is (= ["Your login page has expired"] (words-of (hx/by-tag doc :h1))))
    (is (= ["Back to sign in" "Continue where I was"] (words-of [restart go-on])))
    (testing "the addresses are Keycloak's, relative to its own"
      (is (str/starts-with? (resolve-against stale (:href (hx/attributes restart))) (str base "/realms/pulse/login-actions/restart")))
      (is (str/starts-with? (resolve-against stale (:href (hx/attributes go-on))) (str base "/realms/pulse/login-actions/authenticate"))))))
