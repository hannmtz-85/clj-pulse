(ns pulse.platform.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [pulse.platform.config :as config]))

(def ^:private schema
  [:map
   [:sample/server [:map {:closed true}
                    [:port [:int {:min 0 :max 65535}]]
                    [:host :string]
                    [:limit pos-int?]
                    [:level [:enum :debug :info]]]]
   [:sample/client [:map {:closed true}
                    [:server config/ref-schema]]]])

(def ^:private resource "pulse/platform/test/sample_config.edn")

(deftest reads-every-tag-the-apps-use
  (let [cfg (config/read-config resource {:profile :dev :schema schema})]
    (testing "#profile falls back to :default, #or to its default, #long and #keyword convert"
      (is (= 8080 (get-in cfg [:sample/server :port])))
      (is (= "localhost" (get-in cfg [:sample/server :host])))
      (is (= 25 (get-in cfg [:sample/server :limit])))
      (is (= :info (get-in cfg [:sample/server :level]))))
    (testing "#ig/ref gives an Integrant reference"
      (is (ig/ref? (get-in cfg [:sample/client :server])))
      (is (= :sample/server (:key (get-in cfg [:sample/client :server])))))))

(deftest profile-selects-the-value
  (is (= 0 (get-in (config/read-config resource {:profile :test :schema schema})
                   [:sample/server :port]))))

(deftest a-missing-resource-is-reported
  (let [e (try (config/read-config "pulse/does/not/exist.edn" {:profile :dev :schema schema})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::config/missing-resource (:type (ex-data e))))
    (is (str/includes? (ex-message e) "pulse/does/not/exist.edn"))))

(deftest an-invalid-configuration-says-what-is-wrong
  (let [cfg (config/read-config resource {:profile :dev :schema schema})
        e (try (config/validate! schema (-> cfg
                                            (assoc-in [:sample/server :port] 70000)
                                            (assoc-in [:sample/server :level] :loud)
                                            (assoc-in [:sample/server :colour] "red")
                                            (update :sample/client dissoc :server)))
               (catch clojure.lang.ExceptionInfo e e))
        message (ex-message e)]
    (is (config/invalid-config? e))
    (testing "one line per problem, each with its path"
      (is (str/includes? message ":sample/server :port: "))
      (is (str/includes? message ":sample/server :level: "))
      (is (str/includes? message ":sample/server :colour: ") "a misspelt or unknown key is an error")
      (is (str/includes? message ":sample/client :server: ") "a missing key is an error"))
    (testing "the exception data lists the same lines"
      (is (= 4 (count (:problems (ex-data e))))))))

(deftest the-offending-value-is-never-printed
  (let [secret "hunter2-the-secret"
        e (try (config/validate! [:map [:password [:string {:min 40}]]] {:password secret})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (config/invalid-config? e))
    (is (str/includes? (ex-message e) ":password"))
    (is (not (str/includes? (ex-message e) secret)))))

(deftest a-valid-configuration-is-returned-unchanged
  (let [cfg {:a 1}]
    (is (identical? cfg (config/validate! [:map [:a :int]] cfg)))))
