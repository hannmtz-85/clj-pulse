(ns pulse.spa.events-test
  "The events of the workspace, as the state they leave and the effects they ask for (ADR-0029, point 3). The effect that asks the
  identity provider is replaced by one that writes down what it was asked, so nothing leaves Node."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.subs :as subs]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]
            [re-frame.registrar :as registrar]))

(def ^:private asked (atom nil))

(defn- with-fake-identity-provider [f]
  (let [real (registrar/get-handler :fx ::auth/init)
        loggers (loggers/get-loggers)]
    (reset! asked nil)
    (reset! db/app-db {})
    ;; replacing the effect is what re-frame warns about, and it is meant
    (loggers/set-loggers! {:warn (fn [& args] (when-not (str/starts-with? (str (first args)) "re-frame: overwriting") (apply (:warn loggers) args)))})
    (rf/reg-fx ::auth/init (fn [request] (reset! asked request)))
    (try
      (f)
      (finally
        (rf/reg-fx ::auth/init real)
        (loggers/set-loggers! loggers)))))

(use-fixtures :each with-fake-identity-provider)

(def ^:private claims
  {"sub" "6f2c-rivera" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
   "realm_access" {"roles" ["default-roles-pulse" "practitioner"]}})

(deftest starting-asks-the-identity-provider-who-the-person-is
  (rf/dispatch-sync [::events/start])
  (is (= :starting (subs/status @db/app-db nil)))
  (is (nil? (subs/person @db/app-db nil)))
  (is (= {:on-success [::events/signed-in] :on-failure [::events/sign-in-failed]} @asked)
      "and says which events the answer is given to"))

(deftest starting-again-forgets-who-was-there
  (rf/dispatch-sync [::events/start])
  (rf/dispatch-sync [::events/signed-in claims])
  (rf/dispatch-sync [::events/start])
  (is (= :starting (subs/status @db/app-db nil)))
  (is (nil? (subs/person @db/app-db nil))))

(deftest a-person-who-signed-in-is-who-the-claims-say
  (rf/dispatch-sync [::events/start])
  (rf/dispatch-sync [::events/signed-in claims])
  (is (= :signed-in (subs/status @db/app-db nil)))
  (is (= {:id "6f2c-rivera" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}}
         (subs/person @db/app-db nil))))

(deftest what-the-state-holds-of-the-person-is-who-they-are-and-no-token
  (rf/dispatch-sync [::events/start])
  (rf/dispatch-sync [::events/signed-in (assoc claims "access_token" "eyJhbGciOi.abc.def" "refresh_token" "eyJhbGciOi.ghi.jkl")])
  (is (= #{:status :person} (set (keys @db/app-db))))
  (is (= #{:id :username :name :roles} (set (keys (:person @db/app-db)))))
  (is (not (re-find #"eyJ" (pr-str @db/app-db)))))

(deftest nobody-in-the-answer-is-a-sign-in-that-failed
  (doseq [[label answer] {"no claims: the provider did not sign them in" nil
                          "claims of nobody" {}
                          "claims with no subject" (dissoc claims "sub")}]
    (testing label
      (rf/dispatch-sync [::events/start])
      (rf/dispatch-sync [::events/signed-in answer])
      (is (= :failed (subs/status @db/app-db nil)))
      (is (nil? (subs/person @db/app-db nil))))))

(deftest a-provider-that-fails-is-a-sign-in-that-failed
  (rf/dispatch-sync [::events/start])
  (rf/dispatch-sync [::events/sign-in-failed])
  (is (= :failed (subs/status @db/app-db nil)))
  (is (nil? (subs/person @db/app-db nil))))
