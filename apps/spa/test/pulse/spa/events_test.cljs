(ns pulse.spa.events-test
  "The events of the workspace, as the state they leave and the effects they ask for (ADR-0029, point 3). The effects that reach out of
  the page (the identity provider, the api) are replaced by ones that write down what they were asked, so nothing leaves Node; and
  three flows go the whole way, from the event through the effect of the api to a server of the test and back, to see that the state
  has what the api answered. Each test runs in `day8.re-frame/test`'s own re-frame state, which is thrown away after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.stand-in :as stand-in]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]))

(defn- replace-effect!
  "Makes `f` the effect `effect`, in place of the real one. re-frame says so in the console when an effect is registered twice, and
  here it is meant."
  [effect f]
  (let [loggers (loggers/get-loggers)]
    (loggers/set-loggers! {:warn (fn [& args] (when-not (str/starts-with? (str (first args)) "re-frame: overwriting") (apply (:warn loggers) args)))})
    (try
      (rf/reg-fx effect f)
      (finally
        (loggers/set-loggers! loggers)))))

(def ^:private asked
  "What the effects were asked, in order: `[effect value]`."
  (atom []))

(defn- note-the-effects!
  "Replaces the effects that reach out of the page by ones that write down what they were asked: the identity provider, the api, the
  history of the browser and the clock."
  []
  (reset! asked [])
  (doseq [effect [::auth/init ::auth/logout ::fetch/request ::fetch/cancel ::router/start ::router/replace ::router/arrived :dispatch-later]]
    (replace-effect! effect (fn [value] (swap! asked conj [effect value])))))

(def ^:private router-started
  "What the router is asked when the person has been let in."
  [::router/start nil])

(defn- state [] @db/app-db)

(def ^:private claims
  {"sub" "6f2c-rivera" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
   "realm_access" {"roles" ["default-roles-pulse" "practitioner"]}})

(defn- claims-with-roles [& roles]
  (assoc claims "realm_access" {"roles" (into ["default-roles-pulse" "offline_access"] roles)}))

(def ^:private who-is-asked
  "The call that asks the api who the person is."
  [::fetch/request {:method :get :path ["api" "me"] :id ::events/me :on-success [::events/me-loaded] :on-failure [::events/me-failed]}])

;; ---------------------------------------------------------------------------
;; Signing in

(deftest starting-asks-the-identity-provider-who-the-person-is
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (is (= :starting (subs/status (state) nil)))
   (is (nil? (subs/person (state) nil)))
   (is (= [[::auth/init {:on-success [::events/signed-in] :on-failure [::events/sign-in-failed]}]] @asked)
       "and says which events the answer is given to, and asks nothing else")))

(deftest starting-again-forgets-who-was-there
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/signed-in claims])
   (rf/dispatch [::events/start])
   (is (= {:status :starting} (state)) "not the person, and not what the api said of them")))

(deftest a-practitioner-who-signed-in-is-who-the-claims-say-and-the-api-is-asked-who-they-are
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (reset! asked [])
   (rf/dispatch [::events/signed-in claims])
   (is (= :signed-in (subs/status (state) nil)))
   (is (= {:id "6f2c-rivera" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}}
          (subs/person (state) nil)))
   (is (= {:state :loading} (subs/me (state) nil)))
   (is (= #{who-is-asked router-started} (set @asked)) "who they are is asked of the api, the history begins, and nothing else")
   (is (= 2 (count @asked)))))

(deftest the-administrator-is-let-in-too
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in (claims-with-roles "clinic-admin")])
   (is (= :signed-in (subs/status (state) nil)))
   (is (= #{:clinic-admin} (:roles (subs/person (state) nil))))
   (is (= #{who-is-asked router-started} (set @asked)))))

(deftest a-person-who-is-not-of-the-clinic-is-refused-and-nothing-is-asked-of-the-api
  (doseq [[label roles] {"a patient" ["patient"]
                         "someone with no role of the application" []
                         "the rules engine" ["rules-engine"]
                         "the api's own service account" ["api-service"]
                         "a patient who is also a service account" ["patient" "api-service"]}]
    (rf-test/run-test-sync
     (note-the-effects!)
     (rf/dispatch [::events/signed-in (apply claims-with-roles roles)])
     (testing label
       (is (= :refused (subs/status (state) nil)))
       (is (= "Elena Rivera" (:name (subs/person (state) nil))) "the page can say who they are")
       (is (nil? (subs/me (state) nil)))
       (is (= [] @asked) "nothing at all is asked, of the api or of anyone")))))

(deftest a-person-who-is-both-a-patient-and-of-the-clinic-is-let-in
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in (claims-with-roles "patient" "practitioner")])
   (is (= :signed-in (subs/status (state) nil)))
   (is (= #{who-is-asked router-started} (set @asked)))))

(deftest what-the-state-holds-of-the-person-is-who-they-are-and-no-token
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in (assoc claims "access_token" "eyJhbGciOi.abc.def" "refresh_token" "eyJhbGciOi.ghi.jkl" "id_token" "eyJhbGciOi.mno.pqr")])
   (is (= #{:status :person :me} (set (keys (state)))))
   (is (= #{:id :username :name :roles} (set (keys (:person (state))))))
   (is (not (re-find #"eyJ" (pr-str (state)))))
   (is (not (re-find #"eyJ" (pr-str @asked))) "and no effect is given one")))

(deftest nobody-in-the-answer-is-a-sign-in-that-failed
  (doseq [[label answer] {"no claims: the provider did not sign them in" nil
                          "claims of nobody" {}
                          "claims with no subject" (dissoc claims "sub")}]
    (rf-test/run-test-sync
     (note-the-effects!)
     (rf/dispatch [::events/start])
     (reset! asked [])
     (rf/dispatch [::events/signed-in answer])
     (testing label
       (is (= :failed (subs/status (state) nil)))
       (is (nil? (subs/person (state) nil)))
       (is (= [] @asked))))))

(deftest a-provider-that-fails-is-a-sign-in-that-failed
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/sign-in-failed])
   (is (= :failed (subs/status (state) nil)))
   (is (nil? (subs/person (state) nil)))))

;; ---------------------------------------------------------------------------
;; What the api says of the person

(deftest what-the-api-says-of-the-person-is-kept-as-it-came
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in claims])
   (let [body {:subject "6f2c-rivera" :roles ["practitioner"] :fhirUser "Practitioner/pr-rivera" :resource {:resourceType "Practitioner"}}]
     (rf/dispatch [::events/me-loaded {:status 200 :body body :request-id "r-1"}])
     (is (= {:state :loaded :data body} (subs/me (state) nil)))
     (is (= :signed-in (subs/status (state) nil))))))

(deftest what-the-api-could-not-say-is-a-part-that-failed-with-its-kind-and-the-person-stays-signed-in
  (doseq [kind [:unreachable :timeout :server :forbidden :not-found :unexpected]]
    (rf-test/run-test-sync
     (note-the-effects!)
     (rf/dispatch [::events/signed-in claims])
     (rf/dispatch [::events/me-failed {:kind kind :status 500 :request-id "r-1"}])
     (testing kind
       (is (= {:state :failed :kind kind} (subs/me (state) nil)))
       (is (= :signed-in (subs/status (state) nil)))))))

(deftest an-api-that-did-not-accept-the-sign-in-is-a-page-of-its-own-and-keeps-who-the-person-is
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in claims])
   (rf/dispatch [::events/unauthorized])
   (is (= :unauthorized (subs/status (state) nil)))
   (is (= "Elena Rivera" (:name (subs/person (state) nil))))))

;; ---------------------------------------------------------------------------
;; Signing out

(deftest signing-out-asks-the-identity-provider-and-changes-nothing-else
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in claims])
   (let [before (state)]
     (reset! asked [])
     (rf/dispatch [::events/sign-out])
     (is (= [[::auth/logout nil]] @asked))
     (is (= before (state)) "the page that is going away is left as it was"))))

(deftest the-page-of-someone-who-signed-out-asks-nobody-to-sign-in
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-out])
   (is (= {:status :signed-out} (state)))
   (is (= :signed-out (subs/status (state) nil)))
   (is (nil? (subs/person (state) nil)))
   (is (= [] @asked) "it does not send them to the identity provider, which would sign them in again")))

(deftest nothing-that-the-person-did-before-survives-the-page-of-someone-who-signed-out
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in claims])
   (rf/dispatch [::events/signed-out])
   (is (= {:status :signed-out} (state)))))

;; ---------------------------------------------------------------------------
;; The whole way: the event, the effect that calls the api, a server, and the state

(defn- the-api-is-a-server
  "Runs `f` with a stand-in for the api that answers with `answer` (see `stand-in/serve!`): `(f server seen)`. The effect of the api is
  the real one, `fetch/request!`, with a token that is the test's and the server for an address; what it dispatches is re-frame's."
  [answer f]
  (.then (stand-in/serve! answer)
         (fn [{:keys [server port seen]}]
           (let [env {:base-url (str "http://127.0.0.1:" port)
                      :token! (fn [] (js/Promise.resolve "tok-1"))
                      :renew! (fn [] (js/Promise.resolve "tok-2"))
                      :login! (fn [] nil)
                      :dispatch rf/dispatch
                      :fetch js/fetch
                      :timeout-ms 2000
                      :inflight (atom {})
                      :on-unauthorized #(rf/dispatch [::events/unauthorized])}]
             (replace-effect! ::fetch/request (fn [spec] (fetch/request! env spec)))
             (doseq [effect [::router/start ::router/replace ::router/arrived]]
               (replace-effect! effect (fn [_] nil)))
             (f server seen)))))

(deftest the-api-is-asked-who-the-person-is-and-the-state-keeps-what-it-says
  (rf-test/run-test-async
   (the-api-is-a-server
    (fn [_ _ res] (stand-in/reply! res 200 (stand-in/json {:subject "6f2c-rivera" :roles ["practitioner"] :fhirUser "Practitioner/pr-rivera"})))
    (fn [server seen]
      (rf/dispatch [::events/signed-in claims])
      (rf-test/wait-for [::events/me-loaded ::events/me-failed]
                        (is (= {:state :loaded :data {:subject "6f2c-rivera" :roles ["practitioner"] :fhirUser "Practitioner/pr-rivera"}}
                               (subs/me (state) nil)))
                        (is (= :signed-in (subs/status (state) nil)))
                        (is (= ["/api/me"] (map :url @seen)))
                        (is (= ["Bearer tok-1"] (map #(get-in % [:headers "authorization"]) @seen)))
                        (stand-in/stop! server))))))

(deftest an-api-that-fails-leaves-the-person-signed-in-with-a-part-that-says-so
  (rf-test/run-test-async
   (the-api-is-a-server
    (fn [_ _ res] (stand-in/reply! res 503 (stand-in/json {:title "Service Unavailable"}) "application/problem+json"))
    (fn [server _]
      (rf/dispatch [::events/signed-in claims])
      (rf-test/wait-for [::events/me-failed ::events/me-loaded]
                        (is (= {:state :failed :kind :server} (subs/me (state) nil)))
                        (is (= :signed-in (subs/status (state) nil)))
                        (stand-in/stop! server))))))

(deftest an-api-that-refuses-the-token-twice-is-a-page-that-says-so
  (rf-test/run-test-async
   (the-api-is-a-server
    (fn [_ _ res] (stand-in/reply! res 401 (stand-in/json {:title "Unauthorized"}) "application/problem+json"))
    (fn [server seen]
      (rf/dispatch [::events/signed-in claims])
      (rf-test/wait-for [::events/unauthorized]
                        (is (= :unauthorized (subs/status (state) nil)))
                        (is (= {:state :failed :kind :unauthorized} (subs/me (state) nil)) "the part says it too")
                        (is (= ["Bearer tok-1" "Bearer tok-2"] (map #(get-in % [:headers "authorization"]) @seen))
                            "after one renewal and one repeat")
                        (stand-in/stop! server))))))

(deftest the-effect-of-the-api-names-the-event-that-says-the-sign-in-was-not-accepted
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/signed-in claims])
   ((:on-unauthorized (#'fetch/live-env)))
   (is (= :unauthorized (subs/status (state) nil)) "the event it names is one that exists, and is the one that says so")))
