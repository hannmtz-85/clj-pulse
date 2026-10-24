(ns pulse.web.errors-test
  "What the portal does with a failure of the api that it cannot go on without and one that it can (ADR-0028): a call that a page can
  go on after, the status that tells what happened between the portal and the api, what is put in the log (and never in the page), and the
  page for a failure that the page cannot go on after."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.platform.http.client :as http]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.errors :as errors]
            [taoensso.telemere :as t]))

(defn- failed [status & {:as more}]
  (ex-info "failed" (merge {:type ::http/failed :status status :path "/api/me"} more)))

(defn- unreachable [reason]
  (ex-info "unreachable" {:type ::http/unreachable :reason reason :path "/api/me"}))

(defn- trapped
  "`{:value :signals}` of `(f)`, the log trapped so that nothing is printed; a throw is the `:error`."
  [f]
  (t/with-signals true (f)))

(deftest a-call-that-works-is-what-it-gave
  (let [{:keys [value signals]} (trapped #(errors/attempt (fn [] {:id "x"})))]
    (is (= {:done {:id "x"}} value))
    (is (empty? signals) "and nothing is logged")))

(deftest a-call-that-gives-nothing-is-done-with-nothing
  (is (= {:done nil} (:value (trapped #(errors/attempt (constantly nil)))))))

(deftest a-failure-a-page-can-go-on-after-is-the-failure-and-is-logged-as-a-warning
  (doseq [e [(unreachable :connect) (unreachable :timeout) (failed 500) (failed 503 :problem {:title "Down"}) (failed 200 :reason :bad-response)]]
    (let [{:keys [value signals]} (trapped #(errors/attempt (fn [] (throw e))))]
      (is (= {:failed e} value) (pr-str (ex-data e)))
      (is (= [:pulse.web/api-failed] (map :id signals)))
      (is (= :warn (:level (first signals))) "it is a part that is lost, and not a failure of the whole page"))))

(deftest a-failure-a-page-cannot-go-on-after-is-thrown
  (doseq [e [(failed 400) (failed 401) (failed 403) (failed 404) (failed 409) (failed 422)
             (ex-info "no token" {:type :pulse.platform.token/unavailable :reason :rejected})
             (ex-info "something else" {})
             (ex-info "x" {:type ::http/failed})]]
    (let [{:keys [error signals]} (trapped #(errors/attempt (fn [] (throw e))))]
      (is (identical? e error) (pr-str (ex-data e)))
      (is (empty? signals) "it is for the page that answers it to log")))
  (testing "and what is not even an exception with data"
    (let [e (RuntimeException. "boom")
          {:keys [error]} (trapped #(errors/attempt (fn [] (throw e))))]
      (is (identical? e error)))))

(deftest what-the-api-said-of-a-failure-is-in-the-log-and-nowhere-else
  (let [{:keys [signals]} (trapped #(errors/log-api-failure! (failed 503 :reason :bad-response :problem {:title "Down" :detail "Try later"})))
        [signal] signals]
    (is (= :pulse.web/api-failed (:id signal)))
    (is (= {:failure "failed" :path "/api/me" :status 503 :reason :bad-response :problem {:title "Down" :detail "Try later"}}
           (:data signal)))
    (is (= :warn (:level signal))))
  (testing "a failure that is not a part's is an error"
    (let [signal (first (:signals (trapped #(errors/log-api-failure! (failed 403)))))]
      (is (= :error (:level signal)))
      (is (= {:failure "failed" :path "/api/me" :status 403} (:data signal)) "with only what there is")))
  (testing "a call that did not reach it has no status"
    (is (= {:failure "unreachable" :path "/api/me" :reason :timeout}
           (:data (first (:signals (trapped #(errors/log-api-failure! (unreachable :timeout))))))))))

(deftest the-status-of-a-page-after-a-failure-tells-what-happened-between-the-portal-and-the-api
  (testing "the api was too slow, or said that something it asked was: a gateway timeout"
    (is (= 504 (errors/gateway-status (unreachable :timeout))))
    (is (= 504 (errors/gateway-status (failed 504)))))
  (testing "anything else is a bad gateway"
    (is (= 502 (errors/gateway-status (unreachable :connect))))
    (doseq [status [500 502 503]]
      (is (= 502 (errors/gateway-status (failed status))) (str status)))
    (is (= 502 (errors/gateway-status (failed 200 :reason :bad-response))))))

(def ^:private request {:request-method :get :uri "/appointments" :headers {}})

(deftest a-failure-the-page-cannot-go-on-after-is-a-page-for-it-and-the-failure-is-in-the-log
  (let [page-of (fn [response] (parsed/document (:body response)))
        answer (fn [e] (:value (trapped #(errors/api-failure request e))))]
    (testing "the api said that the person may not see it"
      (let [response (answer (failed 403))]
        (is (= 403 (:status response)))
        (is (= ["You cannot see this"] (map hx/words (hx/by-tag (page-of response) :h1))))))
    (testing "anything else is on our side, and says so"
      (doseq [[e status] [[(failed 401) 502] [(failed 404) 502] [(failed 500) 502] [(unreachable :connect) 502]
                          [(unreachable :timeout) 504] [(failed 504) 504]]]
        (let [response (answer e)]
          (is (= status (:status response)) (pr-str (ex-data e)))
          (is (= ["Something went wrong on our side"] (map hx/words (hx/by-tag (page-of response) :h1)))))))
    (testing "and a failure that is not the api's is nobody's page"
      (is (nil? (answer (ex-info "no token" {:type :pulse.platform.token/unavailable}))))
      (is (nil? (answer (RuntimeException. "boom"))))
      (is (empty? (:signals (trapped #(errors/api-failure request (RuntimeException. "boom")))))))
    (testing "what the api said is in the log, and not in the page"
      (let [{:keys [value signals]} (trapped #(errors/api-failure request (failed 500 :problem {:title "A secret of the api"})))]
        (is (not (str/includes? (str (:body value)) "A secret of the api")))
        (is (= {:title "A secret of the api"} (get-in (first signals) [:data :problem])))))))
