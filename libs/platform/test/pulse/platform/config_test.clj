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
                    [:level [:enum :debug :info]]
                    [:origins [:set :string]]
                    [:nobody [:set :string]]
                    [:same-host :string]]]
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
    (testing "#csv makes a set of the trimmed, non-empty parts, and an empty string is the empty set"
      (is (= #{"http://a.test" "http://b.test"} (get-in cfg [:sample/server :origins])))
      (is (= #{} (get-in cfg [:sample/server :nobody]))))
    (testing "#ref copies another value of the file, before anything starts"
      (is (= "localhost" (get-in cfg [:sample/server :same-host]))))
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

;; ---------------------------------------------------------------------------
;; Secrets (ADR-0021)

(deftest a-secret-is-redacted-wherever-it-is-printed
  (let [s (config/secret "hunter2-the-secret")]
    (is (config/secret? s))
    (is (= "hunter2-the-secret" (config/reveal s)) "it is opened on purpose")
    (is (= ["#secret[REDACTED]" "#secret[REDACTED]" "#secret[REDACTED]"]
           [(str s) (pr-str s) (print-str s)]))
    (testing "inside what holds it"
      (is (not (str/includes? (pr-str {:client-secret s :nested [s {:again s}]}) "hunter2")))
      (is (not (str/includes? (str {:client-secret s}) "hunter2")))
      (is (not (str/includes? (pr-str (ex-info "build failed" {:value {:client-secret s}})) "hunter2"))
          "the dump of a component that failed to start")
      (is (not (str/includes? (pr-str (ex-data (ex-info "build failed" {:reason :failed :value {:client-secret s}}))) "hunter2"))))))

(deftest secrets-with-the-same-value-are-equal
  ;; so that two readings of the same file are the same configuration
  (is (= (config/secret "a") (config/secret "a")))
  (is (= (hash (config/secret "a")) (hash (config/secret "a"))))
  (is (not= (config/secret "a") (config/secret "b")))
  (is (not= (config/secret "a") "a") "a secret is not its value")
  (is (= {:k (config/secret "a")} {:k (config/secret "a")})))

(deftest a-plain-string-is-revealed-as-it-is
  (is (= "plain" (config/reveal "plain")))
  (is (nil? (config/reveal nil))))

(deftest no-value-is-no-secret
  (is (nil? (config/secret nil)))
  (is (not (config/secret? nil)))
  (is (not (config/secret? "a string"))))

(deftest the-secret-tag-reads-a-value-as-a-secret
  (let [cfg (config/read-config resource {:profile :dev :schema schema})
        auth (:sample/auth cfg)]
    (is (config/secret? (:secret auth)))
    (is (= "s3cret-value" (config/reveal (:secret auth))))
    (is (not (str/includes? (pr-str cfg) "s3cret-value")) "the whole configuration can be printed")
    (testing "a variable that is not set gives nil, which the schema then reports"
      (is (nil? (:unset auth))))))

(defn- problem [schema value]
  (try (config/validate! schema value)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest the-secret-schema-wants-a-secret-that-is-not-empty
  (let [schema [:map [:secret (config/secret-schema "set SOME_VARIABLE, see .env")]]]
    (is (nil? (problem schema {:secret (config/secret "something")})))
    (testing "a variable that is not set, said with the name of the variable"
      (let [message (problem schema {:secret nil})]
        (is (str/includes? message ":secret"))
        (is (str/includes? message "set SOME_VARIABLE, see .env"))))
    (testing "an empty or blank one"
      (is (some? (problem schema {:secret (config/secret "")})))
      (is (some? (problem schema {:secret (config/secret "   ")}))))
    (testing "a plain string is not a secret: the file must say #secret"
      (let [message (problem schema {:secret "hunter2-the-secret"})]
        (is (some? message))
        (is (not (str/includes? message "hunter2")) "and it is not repeated")))
    (testing "the hint is optional"
      (is (str/includes? (problem [:map [:secret (config/secret-schema)]] {:secret nil}) "should be a secret that is not empty")))))

(deftest a-test-can-say-what-a-variable-is
  (let [read #(config/read-config resource {:profile :dev :schema [:map]})
        auth #(:sample/auth (read))]
    (testing "#env reads the environment of the process unless told otherwise"
      (is (nil? (config/reveal (:unset (auth))))))
    (testing "a variable given to the test is read, also inside #or, where the default would have been used"
      (binding [config/*environment* {"PULSE_SAMPLE_SECRET_THAT_IS_NEVER_SET" "given"}]
        (is (= "given" (config/reveal (:unset (auth)))))
        (is (= "given" (config/reveal (:secret (auth)))))))
    (testing "nil makes a variable that is set count as not set, and #or then gives its default"
      (binding [config/*environment* {"PULSE_SAMPLE_SECRET_THAT_IS_NEVER_SET" nil}]
        (is (= "s3cret-value" (config/reveal (:secret (auth)))))
        (is (nil? (:unset (auth))))))
    (testing "and it ends with the binding"
      (is (nil? (config/reveal (:unset (auth))))))))

(deftest a-valid-configuration-is-returned-unchanged
  (let [cfg {:a 1}]
    (is (identical? cfg (config/validate! [:map [:a :int]] cfg)))))
