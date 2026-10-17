(ns pulse.design.keycloak-render-test
  "The templates of the login theme, filled in by FreeMarker as Keycloak 26.0.0 does it, with a model of what Keycloak
  gives a page, and read back as HTML (ADR-0026): the structure of each page in each of its branches, what it says, that
  it passes the checks of accessibility, that it escapes what a person typed, and that it is the page of the review. The
  engine is the real one; the model is a stand-in, and `bb test:it` has the same pages from the real Keycloak
  (`keycloak-stack-test`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.keycloak :as kc]
            [pulse.design.signin :as signin]
            [pulse.design.test.freemarker :as fm]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as html]))

;; ---------------------------------------------------------------------------
;; What Keycloak gives a page

(def ^:private login-action
  "http://localhost:8080/realms/pulse/login-actions/authenticate?session_code=abc&execution=1&client_id=pulse-docs&tab_id=xyz")

(def ^:private addresses
  "The addresses of a page of the realm of Pulse, with the ampersands that make a template escape them."
  {"loginAction" login-action
   "loginRestartFlowUrl" "http://localhost:8080/realms/pulse/login-actions/restart?client_id=pulse-docs&tab_id=xyz"
   "loginResetCredentialsUrl" "http://localhost:8080/realms/pulse/login-actions/reset-credentials?client_id=pulse-docs&tab_id=xyz"
   "registrationUrl" "http://localhost:8080/realms/pulse/login-actions/registration?client_id=pulse-docs&tab_id=xyz"
   "logoutConfirmAction" "http://localhost:8080/realms/pulse/protocol/openid-connect/logout/confirm?client_id=pulse-docs"
   "resourcesPath" "/resources/26.0.0/login/pulse"
   "resourcesCommonPath" "/resources/26.0.0/common/keycloak"
   "ssoLoginInOtherTabsUrl" "http://localhost:8080/realms/pulse/login-actions/startup?client_id=pulse-docs&tab_id=xyz"})

(def ^:private realm
  "The realm `pulse`, as `tools/realm.clj` sets it: it has no registration, no password by email and no choice to be
  remembered, and an email can be used to sign in."
  {"displayName" "Pulse"
   "internationalizationEnabled" false
   "loginWithEmailAllowed" true
   "registrationEmailAsUsername" false
   "rememberMe" false
   "resetPasswordAllowed" false
   "password" true
   "registrationAllowed" false})

(def ^:private client
  {"name" "Pulse web portal" "clientId" "pulse-web" "baseUrl" "http://localhost:3000/"})

(defn- authentication
  "The `auth` of a page: whether it shows the name that was typed, and whether it offers another way to sign in."
  [& {:keys [username? another-way?] :or {username? false another-way? false}}]
  {"showUsername" (fn [] username?)
   "showResetCredentials" (fn [] false)
   "showTryAnotherWayLink" (fn [] another-way?)
   "attemptedUsername" "ana.garcia@pulse.test"
   "selectedCredential" ""})

(defn- no-field-errors []
  {"existsError" (fn [& _] false) "getFirstError" (fn [& _] "") "exists" (fn [& _] false)})

(defn- field-errors
  "What `messagesPerField` says when the name or the password are wrong."
  [text]
  {"existsError" (fn [& _] true) "getFirstError" (fn [& _] text) "exists" (fn [& _] true)})

(defn- model
  "What Keycloak gives a page of the sign-in of the realm of Pulse, with `changes` on top: a map from the name of a
  variable to what Keycloak would give it. A nil takes the variable away, as Keycloak leaves out the ones it has no
  value for."
  [changes]
  (into {} (remove (comp nil? val))
        (merge {"realm" realm
                "url" addresses
                "locale" {"currentLanguageTag" "en" "rtl" false}
                "client" client
                "auth" (authentication)
                "login" {"username" ""}
                "messagesPerField" (no-field-errors)
                "properties" kc/class-properties}
               changes)))

(defn- page
  "The page `template` as Keycloak would make it, with what the model gives it: `{:html :asked :missing :doc}`, with the
  HTML read back as hiccup. Whatever is wrong with the page fails here: a template that does not render, an element that
  is not closed, a directive that FreeMarker left in the text and a message that nobody has written."
  [template changes & options]
  (let [{page-html :html :keys [missing] :as result} (apply fm/render template (model changes) options)]
    (is (= [] (fm/leftovers page-html)) (str template " has nothing of FreeMarker left in it"))
    (is (= [] missing) (str template " asks only for messages that exist"))
    (assoc result :doc (html/document page-html))))

(defn- accessible? [doc]
  (is (= [] (a11y/problems doc {:page? true}))))

(defn- card
  "The card of a page: the box with the title."
  [doc]
  (first (hx/select doc #(hx/has-class? % "shadow-raised"))))

(defn- field [doc id] (hx/by-id doc id))

(defn- alerts [node] (hx/select node #(= "alert" (:role (hx/attributes %)))))

(defn- buttons [node] (hx/select node #(hx/has-class? % "btn")))

(defn- href [el] (:href (hx/attributes el)))

(defn- words-of [els] (map hx/words els))

(defn- subtitle
  "The line under the title of the card, or nil."
  [doc]
  (some->> (hx/child-nodes (card doc))
           (filter #(and (hx/element? %) (= :p (hx/tag %)) (hx/has-class? % "text-base")))
           first
           hx/words))

(defn- position
  "Where in the page `el` is, as the order of the elements in it."
  [doc el]
  (.indexOf ^java.util.List (vec (hx/elements doc)) el))

;; ---------------------------------------------------------------------------
;; The sign-in

(deftest the-sign-in-is-a-whole-page-made-of-the-parts-of-the-review
  (let [{:keys [doc html]} (page "login.ftl" {})
        form (field doc "kc-form-login")]
    (accessible? doc)
    (testing "the document"
      (is (= "en" (:lang (hx/attributes doc))))
      (is (= "Sign in to Pulse" (hx/words (first (hx/by-tag doc :title)))))
      (is (= ["/resources/26.0.0/login/pulse/pulse.css"]
             (map href (hx/select doc #(= "stylesheet" (:rel (hx/attributes %)))))))
      (is (= ["/resources/26.0.0/login/pulse/img/favicon.svg"]
             (map href (hx/select doc #(= "icon" (:rel (hx/attributes %)))))))
      (is (str/includes? html "\"/resources/26.0.0/login/pulse/js/authChecker.js\"")
          "the script of Keycloak that watches the other tabs")
      (is (str/includes? html "checkCookiesAndSetTimer(\"http://localhost:8080/realms/pulse/login-actions/startup?client_id=pulse-docs&tab_id=xyz\")")
          "and its address is written for a script, with no entity in it")
      (is (str/includes? html "/resources/26.0.0/common/keycloak/vendor/rfc4648/rfc4648.js")))
    (testing "the card"
      (is (= ["Sign in"] (words-of (hx/by-tag doc :h1))))
      (is (= "kc-page-title" (:id (hx/attributes (first (hx/by-tag doc :h1))))))
      (is (= "to continue to Pulse web portal" (subtitle doc))))
    (testing "the form"
      (is (= "post" (:method (hx/attributes form))))
      (is (= login-action (:action (hx/attributes form))) "the address Keycloak gives, with its ampersands")
      (is (= ["Email" "Password"] (words-of (hx/by-tag form :label))))
      (is (= {:type "text" :name "username" :autocomplete "username" :dir "ltr" :value "" :autofocus true}
             (select-keys (hx/attributes (field form "username")) [:type :name :autocomplete :dir :value :autofocus])))
      (is (= {:type "password" :name "password" :autocomplete "current-password"}
             (select-keys (hx/attributes (field form "password")) [:type :name :autocomplete])))
      (is (not (contains? (hx/attributes (field form "password")) :autofocus)))
      (is (= "hidden" (:type (hx/attributes (field form "id-hidden-input")))))
      (is (not (contains? (hx/attributes (field form "id-hidden-input")) :value)) "no credential is selected")
      (let [submit (field form "kc-login")]
        (is (= ["Sign in" "submit" "login"] [(hx/words submit) (:type (hx/attributes submit)) (:name (hx/attributes submit))]))
        (is (every? (hx/classes submit) ["btn" "btn-primary" "btn-lg" "btn-block"]))))
    (testing "what is under it"
      (is (= "No account yet? Your clinic gives you one." (hx/words (field doc "kc-info"))))
      (is (= (kc/message :demoNotice) (hx/words (last (hx/by-tag (first (hx/by-tag doc :main)) :p))))))
    (testing "this realm offers neither to be remembered nor a new password, nor to register"
      (is (empty? (alerts doc)))
      (is (empty? (hx/select doc #(= "checkbox" (:type (hx/attributes %))))))
      (is (empty? (hx/by-tag form :a)))
      (is (empty? (hx/select doc #(str/includes? (str (href %)) "registration")))))))

(deftest a-wrong-name-or-password-is-said-once-in-words-and-marks-both-fields
  (let [{:keys [doc]} (page "login.ftl" {"messagesPerField" (field-errors (kc/message :invalidUserMessage))
                                         "login" {"username" "ana.garcia@pulse.test"}})
        form (field doc "kc-form-login")
        [alert & more] (alerts doc)]
    (accessible? doc)
    (is (empty? more) "only one alert")
    (is (= (kc/message :invalidUserMessage) (hx/words (first (hx/by-tag alert :p)))))
    (is (str/includes? (hx/words alert) (kc/message :loginErrorHint)))
    (is (hx/has-class? alert "bg-tone-critical-soft"))
    (is (= 1 (count (hx/by-tag alert :svg))))
    (is (= [alert] (alerts form)) "the alert is in the form, above the fields")
    (is (< (position doc alert) (position doc (field doc "username"))))
    (is (= ["true" "true"] (map #(:aria-invalid (hx/attributes (field form %))) ["username" "password"])))
    (is (every? #(hx/has-class? (field form %) "input-error") ["username" "password"]))
    (is (= "ana.garcia@pulse.test" (:value (hx/attributes (field form "username")))) "what was typed is kept")
    (is (not (contains? (hx/attributes (field form "username")) :autofocus)))
    (is (true? (:autofocus (hx/attributes (field form "password")))) "the cursor goes to the password")
    (testing "and a message of Keycloak's for the same page is not shown a second time above the form"
      (let [{:keys [doc]} (page "login.ftl" {"messagesPerField" (field-errors (kc/message :invalidUserMessage))
                                             "message" {"type" "error" "summary" (kc/message :invalidUserMessage)}})]
        (is (= 1 (count (alerts doc))))))))

(deftest the-label-of-the-name-follows-what-the-realm-allows
  (letfn [(label [realm-changes]
            (let [{:keys [doc]} (page "login.ftl" {"realm" (merge realm realm-changes)})]
              (hx/words (first (hx/by-tag (field doc "kc-form-login") :label)))))]
    (is (= "Email" (label {})) "the name or the email: the theme words it as an email")
    (is (= "Username" (label {"loginWithEmailAllowed" false})))
    (is (= "Email" (label {"registrationEmailAsUsername" true})))))

(deftest remembering-and-a-new-password-are-offered-when-the-realm-allows-them
  (letfn [(options [realm-changes & {:as changes}]
            (let [{:keys [doc]} (page "login.ftl" (merge {"realm" (merge realm realm-changes)} changes))
                  form (field doc "kc-form-login")]
              (accessible? doc)
              {:checkboxes (hx/select form #(= "checkbox" (:type (hx/attributes %))))
               :links (hx/by-tag form :a)
               :labels (words-of (hx/by-tag form :label))}))]
    (testing "neither"
      (let [{:keys [checkboxes links]} (options {})]
        (is (empty? checkboxes))
        (is (empty? links))))
    (testing "the choice to be remembered, unchecked and checked as the last time"
      (let [{:keys [checkboxes labels]} (options {"rememberMe" true})]
        (is (= ["rememberMe"] (map #(:name (hx/attributes %)) checkboxes)))
        (is (= ["Email" "Password" "Keep me signed in"] labels))
        (is (not (contains? (hx/attributes (first checkboxes)) :checked))))
      (let [{:keys [checkboxes]} (options {"rememberMe" true} "login" {"username" "" "rememberMe" "on"})]
        (is (true? (:checked (hx/attributes (first checkboxes)))))))
    (testing "the way to a new password"
      (let [{:keys [links checkboxes]} (options {"resetPasswordAllowed" true})]
        (is (empty? checkboxes))
        (is (= ["Forgot your password?"] (words-of links)))
        (is (= (addresses "loginResetCredentialsUrl") (href (first links))))))
    (testing "both, on one line"
      (let [{:keys [links checkboxes]} (options {"rememberMe" true "resetPasswordAllowed" true})]
        (is (= 1 (count checkboxes)))
        (is (= 1 (count links)))))
    (testing "but not to be remembered when the name is not asked: there is nothing to remember it for"
      (let [{:keys [checkboxes links]} (options {"rememberMe" true "resetPasswordAllowed" true} "usernameHidden" true)]
        (is (empty? checkboxes))
        (is (= 1 (count links)))))))

(deftest a-realm-that-allows-to-register-says-so-instead-of-the-hint
  (let [{:keys [doc]} (page "login.ftl" {"realm" (assoc realm "registrationAllowed" true)})
        info (field doc "kc-info")]
    (accessible? doc)
    (is (= "New user? Register" (hx/words info)))
    (is (= [(addresses "registrationUrl")] (map href (hx/by-tag info :a))))
    (is (not (str/includes? (hx/words doc) "No account yet")))
    (testing "unless the flow says that registering is not for this step"
      (let [{:keys [doc]} (page "login.ftl" {"realm" (assoc realm "registrationAllowed" true) "registrationDisabled" true})]
        (is (str/includes? (hx/words (field doc "kc-info")) "No account yet?"))
        (is (empty? (hx/by-tag (field doc "kc-info") :a)))))))

(deftest when-the-name-is-already-known-the-form-does-not-ask-for-it
  (let [{:keys [doc]} (page "login.ftl" {"usernameHidden" true})
        form (field doc "kc-form-login")]
    (accessible? doc)
    (is (nil? (field form "username")))
    (is (= ["Password"] (words-of (hx/by-tag form :label))))
    (is (true? (:autofocus (hx/attributes (field form "password")))) "so the password has the cursor")))

(deftest the-line-under-the-title-is-there-only-when-the-client-has-a-name
  (let [{:keys [doc]} (page "login.ftl" {"client" nil})]
    (accessible? doc)
    (is (nil? (subtitle doc)) "no empty line")
    (is (= ["Sign in"] (words-of (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words doc) "No account yet?") "and the rest of the page is as ever"))
  (testing "a client with no name has none either"
    (is (nil? (subtitle (:doc (page "login.ftl" {"client" (dissoc client "name")}))))))
  (testing "nor has one whose name is empty"
    (is (nil? (subtitle (:doc (page "login.ftl" {"client" (assoc client "name" "")})))))))

(deftest what-keycloak-says-about-the-page-is-an-alert-of-the-tone-it-has
  (doseq [[kind tone-class] [["error" "bg-tone-critical-soft"]
                             ["warning" "bg-tone-high-soft"]
                             ["success" "bg-tone-normal-soft"]
                             ["info" "bg-tone-info-soft"]]]
    (testing kind
      (let [{:keys [doc]} (page "login.ftl" {"message" {"type" kind "summary" (str "A message of " kind)}})
            [alert & more] (alerts doc)]
        (accessible? doc)
        (is (empty? more))
        (is (hx/has-class? alert tone-class))
        (is (= (str "A message of " kind) (hx/words alert)))
        (is (= 1 (count (hx/by-tag alert :svg))) "with an icon, so that it is not told by colour alone")
        (is (< (position doc alert) (position doc (field doc "kc-form-login"))) "above the form"))))
  (testing "a warning about an action that the application asked for is not shown, as in the page of Keycloak"
    (let [{:keys [doc]} (page "login.ftl" {"message" {"type" "warning" "summary" "Not for you"} "isAppInitiatedAction" true})]
      (is (empty? (alerts doc)))))
  (testing "but an error about it is"
    (let [{:keys [doc]} (page "login.ftl" {"message" {"type" "error" "summary" "For you"} "isAppInitiatedAction" true})]
      (is (= 1 (count (alerts doc)))))))

(deftest the-text-of-a-message-goes-through-the-sanitizer-and-nothing-else-that-is-typed-does-not
  (let [sanitized (fn [text] (str "[" text "]"))
        {:keys [doc]} (page "login.ftl" {"message" {"type" "error" "summary" "Be <b>careful</b>"}} :sanitize sanitized)]
    (testing "the summary of a message is passed to kcSanitize, and what it answers is written as markup"
      (is (= "[Be careful]" (hx/words (first (alerts doc)))))
      (is (= 1 (count (hx/by-tag (first (alerts doc)) :b))) "its markup is markup, since that is what the sanitizer is for"))
    (testing "the error of the fields is too"
      (let [{:keys [doc]} (page "login.ftl" {"messagesPerField" (field-errors "Wrong")} :sanitize sanitized)]
        (is (= "[Wrong]" (hx/words (first (hx/by-tag (first (alerts doc)) :p)))))))))

(deftest what-a-person-typed-and-what-an-administrator-named-are-text-and-never-markup
  (let [hostile "\"><script>alert(1)</script><b onmouseover=\"x\">"
        {:keys [doc html]} (page "login.ftl" {"login" {"username" hostile}
                                              "client" (assoc client "name" "Mine & <i>yours</i>")
                                              "realm" (assoc realm "displayName" "<u>Pulse</u>")})]
    (accessible? doc)
    (testing "the name typed comes back as the value of the field, intact"
      (is (= hostile (:value (hx/attributes (field doc "username"))))))
    (testing "and nothing it holds became an element"
      (is (empty? (hx/by-tag doc :b)))
      (is (empty? (hx/select doc #(and (= :script (hx/tag %)) (str/includes? (hx/words %) "alert")))))
      (is (not (str/includes? html "<script>alert"))))
    (testing "the name of the client is text"
      (is (= "to continue to Mine & <i>yours</i>" (subtitle doc)))
      (is (empty? (hx/by-tag (card doc) :i))))
    (testing "so is the name of the realm in the title"
      (is (= "Sign in to <u>Pulse</u>" (hx/words (first (hx/by-tag doc :title))))))))

(deftest the-social-providers-are-buttons-under-their-own-heading
  (let [providers [{"alias" "github" "displayName" "GitHub" "loginUrl" "http://localhost:8080/broker/github/login?client_id=pulse-docs&tab_id=xyz"}
                   {"alias" "google" "displayName" "Google" "loginUrl" "http://localhost:8080/broker/google/login?client_id=pulse-docs&tab_id=xyz"}]
        {:keys [doc]} (page "login.ftl" {"social" {"providers" providers}})
        section (field doc "kc-social-providers")]
    (accessible? doc)
    (is (= ["Or sign in with"] (words-of (hx/by-tag section :h2))))
    (is (= ["GitHub" "Google"] (words-of (buttons section))))
    (is (= (map #(get % "loginUrl") providers) (map href (buttons section))))
    (is (= ["social-github" "social-google"] (map #(:id (hx/attributes %)) (buttons section))))
    (testing "and none when there are no providers, or when the realm has no password"
      (is (nil? (field (:doc (page "login.ftl" {"social" {"providers" []}})) "kc-social-providers")))
      (is (nil? (field (:doc (page "login.ftl" {})) "kc-social-providers")))
      (let [{:keys [doc]} (page "login.ftl" {"social" {"providers" providers} "realm" (assoc realm "password" false)})]
        (is (nil? (field doc "kc-social-providers")))
        (is (nil? (field doc "kc-form-login")) "a realm with no password has no form to sign in with")))))

(deftest the-language-of-the-page-is-the-one-keycloak-says-when-the-realm-has-several
  (let [{:keys [doc]} (page "login.ftl" {"realm" (assoc realm "internationalizationEnabled" true)
                                         "locale" {"currentLanguageTag" "ar" "rtl" true}})]
    (is (= {:lang "ar" :dir "rtl"} (select-keys (hx/attributes doc) [:lang :dir]))))
  (let [{:keys [doc]} (page "login.ftl" {"realm" (assoc realm "internationalizationEnabled" true)
                                         "locale" {"currentLanguageTag" "es" "rtl" false}})]
    (is (= {:lang "es" :dir "ltr"} (select-keys (hx/attributes doc) [:lang :dir]))))
  (testing "with one language the page is English and has no direction of its own"
    (let [{:keys [doc]} (page "login.ftl" {"locale" nil})]
      (is (= {:lang "en"} (hx/attributes doc))))))

(deftest a-page-of-the-second-step-shows-the-name-and-the-way-to-start-again
  (let [{:keys [doc]} (page "login.ftl" {"auth" (authentication :username? true :another-way? true)})
        username (field doc "kc-username")]
    (accessible? doc)
    (is (= "ana.garcia@pulse.test" (hx/words (field doc "kc-attempted-username"))))
    (is (= [(addresses "loginRestartFlowUrl")] (map href (hx/by-tag username :a))))
    (is (= ["Start again"] (words-of (hx/by-tag username :a))))
    (testing "and the way to try another way to sign in, as a form that is sent from a link"
      (let [another (field doc "kc-select-try-another-way-form")]
        (is (= login-action (:action (hx/attributes another))))
        (is (= "post" (:method (hx/attributes another))))
        (is (= ["Try Another Way"] (words-of (hx/by-tag another :a))))
        (is (= "on" (:value (hx/attributes (first (hx/select another #(= "tryAnotherWay" (:name (hx/attributes %))))))))))))
  (testing "a page of the first step shows neither"
    (let [{:keys [doc]} (page "login.ftl" {})]
      (is (nil? (field doc "kc-username")))
      (is (nil? (field doc "kc-select-try-another-way-form"))))))

;; ---------------------------------------------------------------------------
;; The other pages

(def ^:private minimal
  "What Keycloak gives a page that has no session to speak of, such as the error of a request that has no client: no
  client, no authentication, no login, no fields."
  {"client" nil "auth" nil "login" nil "messagesPerField" nil
   "url" (select-keys addresses ["resourcesPath" "resourcesCommonPath" "ssoLoginInOtherTabsUrl"])})

(deftest the-error-says-what-keycloak-says-and-leads-back-to-the-application
  (let [summary "Invalid parameter: redirect_uri"
        {:keys [doc]} (page "error.ftl" {"message" {"type" "error" "summary" summary}})
        [alert & more] (alerts doc)]
    (accessible? doc)
    (is (= ["Something went wrong"] (words-of (hx/by-tag doc :h1))))
    (is (empty? more) "the message once, in the card and not above it as well")
    (is (= summary (hx/words alert)))
    (is (hx/has-class? alert "bg-tone-critical-soft"))
    (is (= ["Back to the application"] (words-of (buttons (card doc)))))
    (is (= ["http://localhost:3000/"] (map href (buttons (card doc)))))
    (is (= "backToApplication" (:id (hx/attributes (first (buttons (card doc))))))))
  (testing "there is no way back when Keycloak says there is none, or does not know where"
    (is (empty? (buttons (card (:doc (page "error.ftl" {"message" {"type" "error" "summary" "x"} "skipLink" true}))))))
    (is (empty? (buttons (card (:doc (page "error.ftl" {"message" {"type" "error" "summary" "x"} "client" (dissoc client "baseUrl")}))))))
    (is (empty? (buttons (card (:doc (page "error.ftl" (assoc minimal "message" {"type" "error" "summary" "x"}))))))))
  (testing "the summary goes through the sanitizer, and so does the title"
    (let [{:keys [doc]} (page "error.ftl" {"message" {"type" "error" "summary" "Oops"}} :sanitize #(str "[" % "]"))]
      (is (= ["[Something went wrong]"] (words-of (hx/by-tag doc :h1))))
      (is (= "[Oops]" (hx/words (first (alerts doc))))))))

(deftest a-page-that-has-no-session-is-still-a-page
  (doseq [[template changes] [["error.ftl" {"message" {"type" "error" "summary" "Invalid parameter: redirect_uri"}}]
                              ["info.ftl" {"message" {"type" "success" "summary" "You have been signed out"}}]]]
    (testing template
      (let [{:keys [doc]} (page template (merge minimal changes))]
        (accessible? doc)
        (is (= 1 (count (hx/by-tag doc :h1))))
        (is (seq (hx/by-tag doc :main)))))))

(deftest the-message-after-signing-out-is-its-own-title-and-leads-back
  (let [{:keys [doc]} (page "info.ftl" {"message" {"type" "success" "summary" (kc/message :successLogout)}})]
    (accessible? doc)
    (is (= [(kc/message :successLogout)] (words-of (hx/by-tag doc :h1))))
    (is (empty? (alerts doc)) "nothing went wrong, so nothing is announced")
    (is (= ["Back to the application"] (words-of (buttons (card doc)))))
    (is (= ["http://localhost:3000/"] (map href (buttons (card doc)))))
    (is (empty? (hx/by-tag (card doc) :p)) "the title says it all")))

(deftest a-message-with-nowhere-to-go-is-a-card-with-only-a-title
  (let [{:keys [doc]} (page "info.ftl" (assoc minimal "message" {"type" "success" "summary" "You have been signed out"}))]
    (accessible? doc)
    (is (= ["You have been signed out"] (words-of (hx/by-tag doc :h1))))
    (is (nil? (field doc "kc-info-message")))
    (is (empty? (buttons (card doc))))
    (testing "and the card has no content, which the stylesheet takes the room of away"
      (is (= [] (hx/child-nodes (field doc "kc-content")))))))

(deftest a-message-can-have-a-title-of-its-own-and-name-what-is-left-to-do
  (let [changes {"message" {"type" "success" "summary" "Your account is almost ready"}
                 "messageHeader" "successLogout"
                 "requiredActions" ["UPDATE_PASSWORD" "VERIFY_EMAIL"]
                 "actionUri" "http://localhost:8080/realms/pulse/login-actions/required-action?execution=update-password&client_id=pulse-docs"}
        {:keys [doc]} (page "info.ftl" changes)
        message (field doc "kc-info-message")]
    (accessible? doc)
    (is (= [(kc/message :successLogout)] (words-of (hx/by-tag doc :h1))) "the title is the message that the header names")
    (is (= "Your account is almost ready: Update Password, Verify Email" (hx/words (first (hx/by-tag message :p)))))
    (is (= 1 (count (hx/by-tag (first (hx/by-tag message :p)) :b))) "the actions are in bold")
    (is (= ["Continue"] (words-of (buttons message))))
    (is (= [(changes "actionUri")] (map href (buttons message))))
    (testing "the way back to the application comes before the way on"
      (let [{:keys [doc]} (page "info.ftl" (assoc changes "pageRedirectUri" "http://localhost:3000/after"))]
        (is (= ["Back to the application"] (words-of (buttons (field doc "kc-info-message")))))
        (is (= ["http://localhost:3000/after"] (map href (buttons (field doc "kc-info-message")))))))
    (testing "and the client's own page, after both"
      (let [{:keys [doc]} (page "info.ftl" (dissoc changes "actionUri"))]
        (is (= ["http://localhost:3000/"] (map href (buttons (field doc "kc-info-message")))))))
    (testing "and nowhere when Keycloak says the page is not to lead anywhere"
      (let [{:keys [doc]} (page "info.ftl" (assoc changes "skipLink" true))]
        (is (empty? (buttons (card doc))))
        (is (= 1 (count (hx/by-tag (field doc "kc-info-message") :p))) "though it still says what is left")))))

(deftest signing-out-is-a-question-with-two-ways-on
  (let [{:keys [doc]} (page "logout-confirm.ftl" {"logoutConfirm" {"code" "code&123" "skipLink" false}})
        form (first (hx/by-tag doc :form))]
    (accessible? doc)
    (is (= ["Sign out?"] (words-of (hx/by-tag doc :h1))))
    (is (= "Do you want to sign out of Pulse?" (subtitle doc)))
    (is (= (addresses "logoutConfirmAction") (:action (hx/attributes form))))
    (is (= "POST" (:method (hx/attributes form))))
    (is (= ["session_code" "code&123"]
           ((juxt :name :value) (hx/attributes (first (hx/select form #(= "hidden" (:type (hx/attributes %)))))))) "the code Keycloak gave, as it was")
    (let [[yes stay] (buttons (card doc))]
      (is (= ["Yes, sign me out" "No, stay signed in"] [(hx/words yes) (hx/words stay)]))
      (is (= ["kc-logout" "confirmLogout" "submit"] ((juxt :id :name :type) (hx/attributes yes))))
      (is (= "http://localhost:3000/" (href stay)))
      (is (= :button (hx/tag yes)) "the first sends the form")
      (is (= :a (hx/tag stay)) "the second is a link back")
      (is (< (position doc yes) (position doc stay))))
    (testing "the question that has no way back asks only the question"
      (is (= ["Yes, sign me out"] (words-of (buttons (card (:doc (page "logout-confirm.ftl" {"logoutConfirm" {"code" "x" "skipLink" true}})))))))
      (is (= ["Yes, sign me out"] (words-of (buttons (card (:doc (page "logout-confirm.ftl" {"logoutConfirm" {"code" "x" "skipLink" false}
                                                                                             "client" nil}))))))))))

(deftest an-expired-page-offers-to-start-again-or-to-go-on
  (let [{:keys [doc]} (page "login-page-expired.ftl" {})
        [restart go-on] (buttons (card doc))]
    (accessible? doc)
    (is (= ["Your login page has expired"] (words-of (hx/by-tag doc :h1))))
    (is (= "It stayed open for too long." (subtitle doc)))
    (is (= ["Back to sign in" "Continue where I was"] [(hx/words restart) (hx/words go-on)]))
    (is (= [(addresses "loginRestartFlowUrl") login-action] [(href restart) (href go-on)]))
    (is (= ["loginRestartLink" "loginContinueLink"] [(:id (hx/attributes restart)) (:id (hx/attributes go-on))]))
    (is (not= (hx/classes restart) (hx/classes go-on)) "the main way and the quieter one")))

;; ---------------------------------------------------------------------------
;; The pages of Keycloak that the theme does not rewrite

(def ^:private base-page
  "A page as the theme `base` of Keycloak writes them (this one is the form to choose a new password, abridged): it
  imports the layout of the theme, asks for what a layout can be asked, and gives each section."
  (str "<#import \"template.ftl\" as layout>\n"
       "<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password') displayInfo=true displayRequiredFields=true bodyClass=\"x\"; section>\n"
       "<#if section = \"header\">${msg(\"doTryAnotherWay\")}\n"
       "<#elseif section = \"show-username\"><span id=\"nested-username\">shown</span>\n"
       "<#elseif section = \"form\"><form id=\"base-form\" action=\"${url.loginAction}\" method=\"post\">"
       "<div class=\"${properties.kcFormGroupClass!}\"><label for=\"password-new\" class=\"${properties.kcLabelClass!}\">${msg(\"password\")}</label>"
       "<input type=\"password\" id=\"password-new\" name=\"password-new\" class=\"${properties.kcInputClass!}\"></div>"
       "<input class=\"${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}\" type=\"submit\" value=\"${msg(\"doLogIn\")}\"/></form>\n"
       "<#elseif section = \"socialProviders\"><div id=\"nested-social\">social</div>\n"
       "<#elseif section = \"info\"><span id=\"nested-info\">info</span>\n"
       "</#if>\n"
       "</@layout.registrationLayout>\n"))

(deftest a-page-of-the-theme-base-is-given-the-frame-of-the-theme
  (let [{:keys [doc]} (page "base-page.ftl" {"auth" (authentication :username? true)}
                            :templates {"base-page.ftl" base-page})
        card (card doc)]
    (accessible? doc)
    (testing "the layout is the theme's, with the sections of the page in the places the base layout has them"
      (is (= ["Try Another Way"] (words-of (hx/by-tag doc :h1))))
      (is (= "kc-page-title" (:id (hx/attributes (first (hx/by-tag doc :h1))))))
      (is (= "shown" (hx/words (field doc "nested-username"))) "next to the name that was typed")
      (is (= "social" (hx/words (field doc "nested-social"))))
      (is (= "info" (hx/words (field doc "kc-info"))))
      (is (< (position doc (field doc "base-form")) (position doc (field doc "nested-social")) (position doc (field doc "kc-info")))))
    (testing "the line that says which fields are required is there when the page asks for it"
      (is (= "* Required fields" (hx/words (first (hx/select card #(and (= :p (hx/tag %)) (str/includes? (hx/words %) "Required"))))))))
    (testing "the classes the base page asks for are the ones of the design"
      (let [form (field doc "base-form")]
        (is (every? (hx/classes (field form "password-new")) (str/split (kc/class-properties "kcInputClass") #" ")))
        (is (every? (hx/classes (first (hx/by-tag form :label))) (str/split (kc/class-properties "kcLabelClass") #" ")))
        (let [submit (last (hx/by-tag form :input))]
          (is (every? (hx/classes submit) ["btn" "btn-primary" "btn-block" "btn-lg"])))))))

(deftest a-page-of-the-theme-base-that-asks-for-nothing-gets-the-defaults
  (let [{:keys [doc]} (page "plain.ftl" {} :templates {"plain.ftl" (str "<#import \"template.ftl\" as layout>\n"
                                                                        "<@layout.registrationLayout; section>"
                                                                        "<#if section = \"header\">Title<#elseif section = \"form\"><p id=\"body\">Body</p></#if>"
                                                                        "</@layout.registrationLayout>\n")})]
    (accessible? doc)
    (is (= ["Title"] (words-of (hx/by-tag doc :h1))))
    (is (= "Body" (hx/words (field doc "body"))))
    (is (nil? (field doc "kc-info")) "no information, unless the page asks for it")
    (is (not (str/includes? (hx/words doc) "Required fields")))
    (testing "and with no message to show, the content has no alert, and the demo notice is still there"
      (is (empty? (alerts doc)))
      (is (str/includes? (hx/words doc) (kc/message :demoNotice))))))

;; ---------------------------------------------------------------------------
;; The pages of the review are these pages

(defn- parsed
  "The hiccup of a page of the review, read back from the HTML that is built: the same path as the pages above."
  [hiccup]
  (html/document (build/render hiccup)))

(defn- texts-in-order
  "The text a person reads in a node, in the order they read it."
  [node]
  (cond (string? node) (remove str/blank? [(str/trim node)])
        (hx/element? node) (mapcat texts-in-order (hx/child-nodes node))
        :else []))

(def ^:private parts-of-a-page
  "The elements whose look and whose words are the design: headings, labels, fields, buttons and links, and icons."
  #{:h1 :h2 :label :input :button :a :svg})

(defn- parts
  "The parts of a page that a person meets, in order, each with its classes and the attributes that decide how it
  works. What differs by nature is left out: the addresses, the ids that tie a label to its field, and the hidden
  inputs, which nobody meets. A heading is a heading whatever its level: the review puts several cards on one page."
  [node]
  (for [el (hx/elements node)
        :let [attrs (hx/attributes el)]
        :when (and (parts-of-a-page (hx/tag el)) (not= "hidden" (:type attrs)))]
    [(if (#{:h1 :h2} (hx/tag el)) :heading (hx/tag el))
     (hx/classes el)
     (cond-> (dissoc attrs :class :href :action :onsubmit :id :for)
       (str/blank? (:value attrs)) (dissoc :value))]))

(deftest the-sign-in-is-the-page-that-was-reviewed
  (let [real (:doc (page "login.ftl" {}))
        mock-up (parsed (signin/sign-in (build/context)))
        body #(first (hx/by-tag % :body))]
    (is (seq (parts (body real))) "the comparison has something to compare")
    (is (= (parts (body mock-up)) (parts (body real))))
    (is (= (texts-in-order (body mock-up)) (texts-in-order (body real))))
    (is (= (hx/classes (body mock-up)) (hx/classes (body real))) "and the body is dressed the same")))

(deftest the-other-pages-are-the-cards-that-were-reviewed
  (let [mock-ups (hx/select (parsed (signin/other-states (build/context))) #(hx/has-class? % "shadow-raised"))
        real [["the sign-in after a wrong password"
               "login.ftl" {"messagesPerField" (field-errors (kc/message :invalidUserMessage))
                            "login" {"username" "ana.garcia@pulse.test"}}]
              ["the question before signing out"
               "logout-confirm.ftl" {"logoutConfirm" {"code" "x" "skipLink" false}}]
              ["the message after signing out"
               "info.ftl" {"message" {"type" "success" "summary" (kc/message :successLogout)}}]
              ["an error of the request"
               "error.ftl" {"message" {"type" "error" "summary" "Invalid parameter: redirect_uri"}}]
              ["the page that has expired"
               "login-page-expired.ftl" {}]]]
    (is (= 5 (count mock-ups) (count real)) "a card of the review for each page of the theme")
    (doseq [[mock-up [what template changes]] (map vector mock-ups real)
            :let [real-card (card (:doc (page template changes)))]]
      (testing what
        (is (seq (parts real-card)) "the comparison has something to compare")
        (is (= (parts mock-up) (parts real-card)))
        (is (= (texts-in-order mock-up) (texts-in-order real-card)))))))

;; ---------------------------------------------------------------------------
;; The checks check

(deftest a-template-that-closes-what-it-did-not-open-in-one-branch-is-caught
  (testing "the same template renders in a branch that is fine, and is caught in the one that is not"
    (let [template "<#if broken??><div><p>text</div><#else><div><p>text</p></div></#if>"
          render-with (fn [data] (:html (fm/render "broken.ftl" data :templates {"broken.ftl" template})))]
      (is (some? (html/document (render-with {}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"</div> closes <p>" (html/document (render-with {"broken" true})))))))

(deftest a-message-that-nobody-wrote-is-found
  (let [{:keys [missing asked]} (fm/render "broken.ftl" {} :templates {"broken.ftl" "<p>${msg(\"noSuchMessage\")} ${msg(\"doLogIn\")}</p>"})]
    (is (= ["noSuchMessage" "doLogIn"] asked))
    (is (= ["noSuchMessage"] missing))))

(deftest what-freemarker-leaves-in-a-page-is-found
  (is (= ["<#if" "${x}"] (fm/leftovers "<p><#if a>${x}</p>")))
  (is (= [] (fm/leftovers "<p>Sign in</p>"))))

;; ---------------------------------------------------------------------------
;; Everything the templates say was asked for

(def ^:private message-keys-of-the-templates
  "The static keys of the messages that the templates of the theme ask for with `msg`. The two that a template makes up
  from its model (`${messageHeader}` and `requiredAction.${...}`) are not among them."
  (into (sorted-set)
        (for [[path text] (kc/files)
              :when (str/ends-with? path ".ftl")
              [_ k] (re-seq #"msg\(\"([^\"$]+)\"" text)]
          k)))

(def ^:private pages-that-say-everything
  "One page for each way that a template can say something different: the labels the realm chooses between, what it
  allows, the error, the second step, the providers, and each of the other pages in each of its branches."
  [["login.ftl" {}]
   ["login.ftl" {"realm" (assoc realm "loginWithEmailAllowed" false)}]
   ["login.ftl" {"realm" (assoc realm "registrationEmailAsUsername" true)}]
   ["login.ftl" {"realm" (assoc realm "rememberMe" true "resetPasswordAllowed" true "registrationAllowed" true)}]
   ["login.ftl" {"messagesPerField" (field-errors "Wrong")}]
   ["login.ftl" {"auth" (authentication :username? true :another-way? true)}]
   ["login.ftl" {"social" {"providers" [{"alias" "a" "displayName" "A" "loginUrl" "http://localhost/a"}]}}]
   ["error.ftl" {"message" {"type" "error" "summary" "x"}}]
   ["info.ftl" {"message" {"type" "success" "summary" "x"} "actionUri" "http://localhost/a"}]
   ["info.ftl" {"message" {"type" "success" "summary" "x"} "messageHeader" "successLogout" "requiredActions" ["VERIFY_EMAIL"]}]
   ["logout-confirm.ftl" {"logoutConfirm" {"code" "c" "skipLink" false}}]
   ["login-page-expired.ftl" {}]
   ["base-page.ftl" {} :templates {"base-page.ftl" base-page}]])

(deftest every-message-the-templates-ask-for-is-asked-for-by-some-page
  (testing "so the pages above went through every place that a template says something"
    (let [asked (into #{} (mapcat #(:asked (apply page %))) pages-that-say-everything)]
      (is (seq message-keys-of-the-templates))
      (is (= [] (sort (remove asked message-keys-of-the-templates))))
      (is (= [] (sort (remove (set (keys fm/properties)) asked))) "and every one of them is a message"))))
