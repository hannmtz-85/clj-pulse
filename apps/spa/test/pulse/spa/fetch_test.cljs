(ns pulse.spa.fetch-test
  "The effect that calls the api (ADR-0029, point 4), against a real HTTP server of its own on Node: what it sends (the address, the
  headers, the body), what it makes of each answer, the renewal of the token and the repeat of a call that was refused for its
  token, the time a call has, and the calls that stop one another. The token is the test's, not Keycloak's: it is a function of the
  environment of the call. Everything that leaves the effect is an event, which the test writes down.

  A test has ONE `async` block, and a table of cases is a chain of promises (`sequentially`): a `doseq` of `async` blocks would run
  none of them, and the test would pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.settings :as settings]
            [pulse.spa.test.stand-in :refer [json pause reply! serve! stop! until]]))

;; ---------------------------------------------------------------------------
;; A server, and what a call is made in

(defn- world
  "What a call is made in, with what it did written down: `:events` (what it dispatched, in order), `:tokens`, `:renewals`, `:logins`
  and `:unauthorized` (how often each was asked for or done), `:inflight` and `:env`, the environment of the call. `overrides` replace
  parts of the environment."
  [port overrides]
  (let [w {:events (atom []) :times (atom []) :tokens (atom 0) :renewals (atom 0) :logins (atom 0) :unauthorized (atom 0) :inflight (atom {})}]
    (assoc w :env (merge {:base-url (str "http://127.0.0.1:" port)
                          :token! (fn [] (swap! (:tokens w) inc) (js/Promise.resolve "tok-1"))
                          :renew! (fn [] (swap! (:renewals w) inc) (js/Promise.resolve "tok-2"))
                          :login! (fn [] (swap! (:logins w) inc))
                          :dispatch (fn [event] (swap! (:times w) conj (js/Date.now)) (swap! (:events w) conj event))
                          :fetch js/fetch
                          :timeout-ms 2000
                          :inflight (:inflight w)
                          :on-unauthorized (fn [] (swap! (:unauthorized w) inc))}
                         overrides))))

(defn- with-world
  "Starts a server that answers with `answer`, and calls `(f world seen)` with a world whose environment has `overrides`: `f` gives a
  promise, and the server is stopped when it is done, whatever happened. A promise of what `f` gave. With `:closed? true` the server
  is stopped first, so that its port is one nobody listens on."
  [answer overrides closed? f]
  (.then (serve! answer)
         (fn [{:keys [server port seen aborted]}]
           (let [w (assoc (world port overrides) :aborted aborted)]
             (-> (if closed? (stop! server) (js/Promise.resolve nil))
                 (.then (fn [_] (f w seen)))
                 (.finally (fn [] (stop! server))))))))

(defn- run-calls!
  "Makes each of `specs` (a call, as `request!` takes it) against a server that answers with `answer`, waits until the page was told
  as many times as there are calls (or until `:ready?` is true of the world) and then `:settle-ms` more (20), so that anything else
  that was on its way arrives and can be counted. A promise of `{:w :seen :started}`: the world, the requests the server was sent and the
  moment the calls were made (the world's `:times` has the moment of each event)."
  ([answer specs] (run-calls! answer specs nil))
  ([answer specs {:keys [overrides closed? ready? settle-ms] :or {settle-ms 20}}]
   (with-world answer overrides closed?
     (fn [w seen]
       (let [started (js/Date.now)]
         (doseq [spec specs] (fetch/request! (:env w) spec))
         (-> (until #((or ready? (fn [world] (= (count specs) (count @(:events world))))) w))
             (.then (fn [_] (pause settle-ms)))
             (.then (fn [_] {:w w :seen @seen :started started}))))))))

(defn- finish [done promise]
  (-> promise
      (.catch (fn [e] (is false (str "the test did not finish: " (or (some-> ^js e .-message) e)))))
      (.finally done)))

(defn- sequentially
  "A promise of running `(f x)`, which gives a promise, for each `x` of `xs`, one after the other."
  [xs f]
  (reduce (fn [previous x] (.then previous (fn [_] (f x)))) (js/Promise.resolve nil) xs))

(defn- only-event [w]
  (first @(:events w)))

(defn- counts
  "How often each of the things the environment gives was asked for or done: tokens, renewals, logins and 401s told."
  [w]
  (mapv (fn [k] @(get w k)) [:tokens :renewals :logins :unauthorized]))

(defn- authorizations [seen]
  (map #(get-in % [:headers "authorization"]) seen))

(def ^:private request-id-pattern #"[A-Za-z0-9._-]{1,64}")

;; ---------------------------------------------------------------------------
;; What is sent

(deftest a-call-is-a-request-with-the-token-and-a-request-id-and-nothing-else-that-identifies-the-person
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 200 (json {:subject "s-1" :roles ["practitioner"] :fhirUser "Practitioner/pr-rivera"})))
                                    [{:path ["api" "me"] :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w seen]}]
                          (let [[request] seen
                                headers (:headers request)
                                [event-name answer] (only-event w)]
                            (is (= 1 (count seen)))
                            (is (= "GET" (:method request)))
                            (is (= "/api/me" (:url request)))
                            (is (= "Bearer tok-1" (get headers "authorization")))
                            (is (= "application/json" (get headers "accept")))
                            (is (re-matches request-id-pattern (get headers "x-request-id")) "an id the api takes and writes in its log")
                            (is (not (contains? headers "cookie")) "the api is told who the person is by the token and by nothing else")
                            (is (not (contains? headers "content-type")) "a call with no body says nothing of one")
                            (is (= "" (:body request)))
                            (is (= ::ok event-name))
                            (is (= {:status 200
                                    :body {:subject "s-1" :roles ["practitioner"] :fhirUser "Practitioner/pr-rivera"}
                                    :request-id (get headers "x-request-id")}
                                   answer)
                                "the body is data with keyword keys, and the id is the one that was sent")
                            (is (= 1 (count @(:events w))) "and the page is told once")
                            (is (= [1 0 0 0] (counts w)))))))))

(deftest every-part-of-the-address-reaches-the-server-as-it-was-meant
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 200 "[]"))
                                    [{:path ["api" "patients" "a/b c" "observations"]
                                      :query {:from "2026-10-13T12:00:00+02:00" :limit 5 :measurement :heart-rate}
                                      :on-success [::ok]}])
                        (fn [{:keys [w seen]}]
                          (is (= ["/api/patients/a%2Fb%20c/observations?from=2026-10-13T12%3A00%3A00%2B02%3A00&limit=5&measurement=heart-rate"]
                                 (map :url seen)))
                          (is (= [] (:body (second (only-event w))))))))))

(deftest a-call-that-changes-something-sends-its-body-as-json
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 201 (json {:id "obs-1"})))
                                    [{:method :post :path ["api" "patients" "pt-ana" "observations"]
                                      :body {:measurement "heart-rate" :value 70 :takenAt "2026-10-13T12:00:00Z"}
                                      :on-success [::ok]}
                                     {:method :patch :path ["api" "appointments" "ap-1"]
                                      :body {:action "cancel" :reason "García"}
                                      :on-success [::ok]}])
                        (fn [{:keys [seen]}]
                          (let [by-method (into {} (map (juxt :method identity)) seen)
                                post (get by-method "POST")
                                patch (get by-method "PATCH")]
                            (is (= "/api/patients/pt-ana/observations" (:url post)))
                            (is (= "application/json" (get-in post [:headers "content-type"])))
                            (is (= {"measurement" "heart-rate" "value" 70 "takenAt" "2026-10-13T12:00:00Z"}
                                   (js->clj (js/JSON.parse (:body post)))))
                            (is (= "/api/appointments/ap-1" (:url patch)))
                            (is (= {"action" "cancel" "reason" "García"} (js->clj (js/JSON.parse (:body patch)))))
                            (is (every? #(= "Bearer tok-1" %) (authorizations seen)))))))))

(deftest a-body-that-cannot-be-written-is-a-failure-and-nothing-is-sent
  (let [circular (js-obj)]
    (set! (.-self circular) circular)
    (async done
           (finish done
                   (.then (run-calls! (fn [_ _ res] (reply! res 200 "{}"))
                                      [{:method :post :path ["api" "x"] :body {:a circular} :on-success [::ok] :on-failure [::failed]}])
                          (fn [{:keys [w seen]}]
                            (is (= [] seen))
                            (is (= ::failed (first (only-event w))))
                            (is (= :unexpected (:kind (second (only-event w)))))
                            (is (= 1 (count @(:events w))))))))))

;; ---------------------------------------------------------------------------
;; What each answer is

(deftest a-success-with-no-body-is-a-success-with-nothing
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ ^js res] (.writeHead res 204) (.end res))
                                    [{:method :patch :path ["api" "alerts" "al-1" "ack"] :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w]}]
                          (let [[event-name answer] (only-event w)]
                            (is (= ::ok event-name))
                            (is (= 204 (:status answer)))
                            (is (nil? (:body answer)))))))))

(deftest a-call-that-asks-to-be-told-of-nothing-is-not-an-error
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 200 "{}"))
                                    [{:path ["api" "me"]}]
                                    {:ready? (fn [_] true) :settle-ms 100})
                        (fn [{:keys [w]}]
                          (is (= [] @(:events w)) "nobody was asked to be told"))))))

(deftest the-api-saying-no-and-why-is-a-refusal-with-its-problem
  (let [problem {:type "about:blank" :title "Unprocessable Content" :status 422 :detail "Check what you entered."
                 :errors {:diastolic "Must be between 20 and 200 mmHg."}}]
    (async done
           (finish done
                   (.then (run-calls! (fn [_ _ res] (reply! res 422 (json problem) "application/problem+json"))
                                      [{:method :post :path ["api" "x"] :body {} :on-success [::ok] :on-refusal [::refused] :on-failure [::failed]}
                                       {:method :post :path ["api" "y"] :body {} :on-success [::ok] :on-failure [::failed]}])
                          (fn [{:keys [w]}]
                            (let [by-name (group-by first @(:events w))
                                  refusal (second (first (::refused by-name)))
                                  fallback (second (first (::failed by-name)))]
                              (testing "it goes to the handler of refusals, with the api's own problem"
                                (is (= :refused (:kind refusal)))
                                (is (= 422 (:status refusal)))
                                (is (= problem (:problem refusal))))
                              (testing "and, when there is none, to the one of failures, as a refusal"
                                (is (= :refused (:kind fallback)))
                                (is (= 422 (:status fallback)))
                                (is (= problem (:problem fallback))))
                              (is (nil? (::ok by-name))))))))))

(deftest a-409-and-a-400-are-refusals-too
  (async done
         (finish done
                 (.then (run-calls! (fn [_ request res]
                                      (reply! res (if (= "/api/a" (:url request)) 409 400) (json {:title "No" :detail "Because."}) "application/problem+json"))
                                    [{:path ["api" "a"] :on-refusal [::refused] :on-failure [::failed]}
                                     {:path ["api" "b"] :on-refusal [::refused] :on-failure [::failed]}])
                        (fn [{:keys [w]}]
                          (is (= #{[::refused 409] [::refused 400]}
                                 (set (map (fn [[event-name event]] [event-name (:status event)]) @(:events w))))))))))

(deftest a-refusal-that-says-nothing-is-a-refusal-with-no-problem
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 409 "conflict" "text/plain"))
                                    [{:path ["api" "a"] :on-refusal [::refused]}])
                        (fn [{:keys [w]}]
                          (let [[_ refusal] (only-event w)]
                            (is (= :refused (:kind refusal)))
                            (is (= 409 (:status refusal)))
                            (is (nil? (:problem refusal)))))))))

(deftest every-other-answer-is-a-failure-of-a-kind
  (async done
         (finish done
                 (sequentially
                  [[403 :forbidden] [404 :not-found] [500 :server] [502 :server] [503 :server] [504 :server] [405 :unexpected] [429 :unexpected]]
                  (fn [[status kind]]
                    (.then (run-calls! (fn [_ _ res] (reply! res status (json {:title "x" :status status}) "application/problem+json"))
                                       [{:path ["api" "a"] :on-success [::ok] :on-failure [::failed]}])
                           (fn [{:keys [w seen]}]
                             (let [[event-name failure] (only-event w)]
                               (is (= ::failed event-name) (str status))
                               (is (= kind (:kind failure)) (str status))
                               (is (= status (:status failure)) (str status))
                               (is (re-matches request-id-pattern (:request-id failure)))
                               (is (nil? (:problem failure)) "what the api says of its own failures is not passed on")
                               (is (= 1 (count seen)) "only a 401 is repeated")
                               (is (= 1 (count @(:events w))))
                               (is (= [1 0 0 0] (counts w)))))))))))

(deftest a-server-that-fails-with-a-page-is-a-failure-of-the-server
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 502 "<html><body>Bad gateway</body></html>" "text/html"))
                                    [{:path ["api" "a"] :on-failure [::failed]}])
                        (fn [{:keys [w]}]
                          (is (= :server (:kind (second (only-event w))))))))))

(deftest a-success-that-is-not-json-is-not-one-the-workspace-can-read
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 200 "<html>login</html>" "text/html"))
                                    [{:path ["api" "a"] :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w]}]
                          (is (= ::failed (first (only-event w))))
                          (is (= :unexpected (:kind (second (only-event w)))))
                          (is (= 200 (:status (second (only-event w))))))))))

(deftest a-server-that-cannot-be-reached-is-a-failure-and-says-no-status
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 200 "{}"))
                                    [{:path ["api" "a"] :on-success [::ok] :on-failure [::failed]}]
                                    {:closed? true})
                        (fn [{:keys [w]}]
                          (let [[event-name failure] (only-event w)]
                            (is (= ::failed event-name))
                            (is (= :unreachable (:kind failure)))
                            (is (not (contains? failure :status)))
                            (is (= 1 (count @(:events w))))))))))

(deftest a-redirect-is-not-followed-and-the-token-goes-nowhere-else
  (async done
         (finish done
                 (.then (run-calls! (fn [_ request ^js res]
                                      (if (= "/api/a" (:url request))
                                        (do (.writeHead res 302 #js {"Location" "/elsewhere"}) (.end res))
                                        (reply! res 200 "{}")))
                                    [{:path ["api" "a"] :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w seen]}]
                          (is (= ::failed (first (only-event w))))
                          (is (= ["/api/a"] (map :url seen)) "the place it pointed to was never asked"))))))

;; ---------------------------------------------------------------------------
;; The token

(deftest a-call-that-is-refused-for-its-token-is-repeated-once-with-a-new-one
  (async done
         (finish done
                 (.then (run-calls! (fn [n _ res]
                                      (if (= 1 n)
                                        (reply! res 401 (json {:title "Unauthorized"}) "application/problem+json")
                                        (reply! res 200 (json {:ok true}))))
                                    [{:path ["api" "me"] :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w seen]}]
                          (is (= ["Bearer tok-1" "Bearer tok-2"] (authorizations seen)) "the second is the renewed token")
                          (is (= 2 (count (distinct (map #(get-in % [:headers "x-request-id"]) seen)))) "each request has its own id")
                          (is (= ::ok (first (only-event w))))
                          (is (= {:ok true} (:body (second (only-event w)))))
                          (is (= (get-in (second seen) [:headers "x-request-id"]) (:request-id (second (only-event w))))
                              "and the id told is that of the request that was answered")
                          (is (= [1 1 0 0] (counts w)))
                          (is (= 1 (count @(:events w)))))))))

(deftest a-call-that-changes-something-is-repeated-with-the-same-body
  (async done
         (finish done
                 (.then (run-calls! (fn [n _ res] (if (= 1 n) (reply! res 401 "{}") (reply! res 201 (json {:id "x"}))))
                                    [{:method :post :path ["api" "x"] :body {:a 1} :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w seen]}]
                          (is (= ["POST" "POST"] (map :method seen)))
                          (is (= ["{\"a\":1}" "{\"a\":1}"] (map :body seen)))
                          (is (= ::ok (first (only-event w)))))))))

(deftest a-token-the-api-refuses-twice-is-a-failure-and-the-person-decides
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 401 (json {:title "Unauthorized"}) "application/problem+json"))
                                    [{:path ["api" "me"] :on-success [::ok] :on-failure [::failed]}])
                        (fn [{:keys [w seen]}]
                          (is (= 2 (count seen)) "one repeat, and no more")
                          (is (= ["Bearer tok-1" "Bearer tok-2"] (authorizations seen)))
                          (let [[event-name failure] (only-event w)]
                            (is (= ::failed event-name))
                            (is (= :unauthorized (:kind failure)))
                            (is (= 401 (:status failure))))
                          (is (= 1 (count @(:events w))))
                          (is (= [1 1 0 1] (counts w)) "the page that says so is told once, and nobody is sent to sign in"))))))

(deftest the-page-that-says-the-sign-in-was-not-accepted-is-told-after-the-part-that-asked
  (let [order (atom [])]
    (async done
           (finish done
                   (.then (run-calls! (fn [_ _ res] (reply! res 401 "{}"))
                                      [{:path ["api" "me"] :on-failure [::failed]}]
                                      {:overrides {:dispatch (fn [event] (swap! order conj (first event)))
                                                   :on-unauthorized (fn [] (swap! order conj :unauthorized))}
                                       :ready? (fn [_] (= 2 (count @order)))})
                          (fn [_]
                            (is (= [::failed :unauthorized] @order))))))))

(deftest a-token-that-cannot-be-had-sends-the-person-to-sign-in-and-tells-the-page-nothing
  (async done
         (finish done
                 (sequentially
                  [["rejected" (fn [] (js/Promise.reject (js/Error. "the session ended")))]
                   ["thrown" (fn [] (throw (js/Error. "no token")))]
                   ["nothing" (fn [] (js/Promise.resolve nil))]
                   ["not a text" (fn [] (js/Promise.resolve 42))]]
                  (fn [[label token!]]
                    (.then (run-calls! (fn [_ _ res] (reply! res 200 "{}"))
                                       [{:path ["api" "me"] :on-success [::ok] :on-failure [::failed]}]
                                       {:overrides {:token! token! :timeout-ms 80} :ready? (fn [w] (= 1 @(:logins w))) :settle-ms 200})
                           (fn [{:keys [w seen]}]
                             (is (= [] @(:events w)) (str label ": the page that is going away is told nothing"))
                             (is (= [] seen) (str label ": nothing was sent"))
                             (is (= 1 @(:logins w)) label))))))))

(deftest a-token-that-cannot-be-renewed-after-a-refusal-sends-the-person-to-sign-in
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (reply! res 401 "{}"))
                                    [{:path ["api" "me"] :on-success [::ok] :on-failure [::failed]}]
                                    {:overrides {:renew! (fn [] (js/Promise.reject (js/Error. "the session ended"))) :timeout-ms 80}
                                     :ready? (fn [w] (= 1 @(:logins w))) :settle-ms 200})
                        (fn [{:keys [w seen]}]
                          (is (= 1 (count seen)) "the call was made once, and not repeated")
                          (is (= [] @(:events w)))
                          (is (= [1 0 1 0] (counts w))))))))

(deftest the-token-is-in-the-header-and-nowhere-else
  (let [logged (atom [])
        console-methods ["log" "info" "warn" "error" "debug"]
        originals (into {} (map (fn [k] [k (aget js/console k)])) console-methods)
        silence! (fn [] (doseq [k console-methods] (aset js/console k (fn [& args] (swap! logged conj (str/join " " (map str args)))))))
        restore! (fn [] (doseq [[k f] originals] (aset js/console k f)))
        a-requests (atom 0)
        answer (fn [_ request res]
                 (cond
                   (not= "/api/a" (:url request)) (reply! res 422 (json {:title "x" :errors {:a "b"}}) "application/problem+json")
                   (= 1 (swap! a-requests inc)) (reply! res 401 (json {:title "Unauthorized"}) "application/problem+json")
                   :else (reply! res 200 (json {:ok true}))))]
    (async done
           (finish done
                   (-> (js/Promise.resolve nil)
                       (.then (fn [_]
                                (silence!)
                                (run-calls! answer
                                            [{:path ["api" "a"] :on-success [::ok] :on-failure [::failed]}
                                             {:method :post :path ["api" "b"] :body {} :on-refusal [::refused] :on-failure [::failed]}])))
                       (.then (fn [{:keys [w seen]}]
                                (is (= 3 (count seen)) "the first was repeated after the 401")
                                (is (= 2 (count @(:events w))))
                                (is (not (str/includes? (pr-str @(:events w)) "tok-")) "no event has the token")
                                (is (= [] @logged) "and the effect says nothing to the console")))
                       (.finally restore!))))))

;; ---------------------------------------------------------------------------
;; Time, and calls that stop one another

(deftest a-call-has-its-time-and-an-answer-that-comes-later-is-not-heard
  (async done
         (finish done
                 (.then (run-calls! (fn [_ _ res] (js/setTimeout #(reply! res 200 (json {:late true})) 250))
                                    [{:path ["api" "slow"] :on-success [::ok] :on-failure [::failed]}]
                                    {:overrides {:timeout-ms 60} :settle-ms 350})
                        (fn [{:keys [w started]}]
                          (is (= 1 (count @(:events w))) "told once, and not again when the answer comes")
                          (let [[event-name failure] (only-event w)
                                waited (- (first @(:times w)) started)]
                            (is (= ::failed event-name))
                            (is (= :timeout (:kind failure)))
                            (is (not (contains? failure :status)))
                            (is (= {} @(:inflight w)))
                            (is (<= 50 waited 200) (str "it was told when the time was up, and not before or long after: " waited " ms"))
                            (is (= 1 @(:aborted w)) "and the request was aborted: the api is not left to answer to nobody")))))))

(deftest the-time-of-a-call-includes-the-wait-for-its-token
  (let [late-token (atom nil)]
    (async done
           (finish done
                   (.then (run-calls! (fn [_ _ res] (reply! res 200 "{}"))
                                      [{:path ["api" "me"] :on-success [::ok] :on-failure [::failed]}]
                                      {:overrides {:timeout-ms 40
                                                   :token! (fn [] (js/Promise. (fn [resolve _] (reset! late-token resolve))))}})
                          (fn [{:keys [w seen]}]
                            (is (= :timeout (:kind (second (only-event w)))))
                            (@late-token "tok-late")
                            (.then (pause 60)
                                   (fn [_]
                                     (is (= [] seen) "a token that comes after the time is up sends nothing")
                                     (is (= 1 (count @(:events w))))))))))))

(deftest a-call-that-is-answered-is-no-longer-in-flight
  (async done
         (finish done
                 (.then (run-calls! (fn [_ request res] (reply! res (if (= "/api/ok" (:url request)) 200 500) "{}"))
                                    [{:id ::one :path ["api" "ok"] :on-success [::ok]}
                                     {:id ::two :path ["api" "bad"] :on-failure [::failed]}
                                     {:id ::three :path ["api" "ok"] :on-success [::ok]}])
                        (fn [{:keys [w]}]
                          (is (= 3 (count @(:events w))))
                          (is (= {} @(:inflight w))))))))

(deftest the-latest-call-with-an-id-is-the-one-that-is-heard
  (async done
         (finish done
                 (.then (run-calls! (fn [_ request res]
                                      (if (= "/api/slow" (:url request))
                                        (js/setTimeout #(reply! res 200 (json {:which "slow"})) 150)
                                        (reply! res 200 (json {:which "fast"}))))
                                    [{:id ::search :path ["api" "slow"] :on-success [::ok] :on-failure [::failed]}
                                     {:id ::search :path ["api" "fast"] :on-success [::ok] :on-failure [::failed]}]
                                    {:ready? (fn [w] (seq @(:events w))) :settle-ms 300})
                        (fn [{:keys [w seen]}]
                          (is (= [[::ok "fast"]] (map (fn [[event-name answer]] [event-name (get-in answer [:body :which])]) @(:events w)))
                              "the slower, older call tells nobody anything, as a failure or as a success")
                          (is (= ["/api/fast"] (map :url seen)) "it was not even sent: the second call came before its token did")
                          (is (= 0 @(:aborted w)) "so there was nothing to abort")
                          (is (= {} @(:inflight w))))))))

(deftest a-call-on-the-wire-is-stopped-by-the-next-one-with-its-id
  (async done
         (finish done
                 (with-world (fn [_ request res]
                               (if (= "/api/slow" (:url request))
                                 (js/setTimeout #(reply! res 200 (json {:which "slow"})) 200)
                                 (reply! res 200 (json {:which "fast"}))))
                   nil false
                   (fn [w seen]
                     (fetch/request! (:env w) {:id ::search :path ["api" "slow"] :on-success [::ok] :on-failure [::failed]})
                     (-> (until #(seq @seen))
                         (.then (fn [_]
                                  (fetch/request! (:env w) {:id ::search :path ["api" "fast"] :on-success [::ok] :on-failure [::failed]})
                                  (until #(seq @(:events w)))))
                         (.then (fn [_] (pause 350)))
                         (.then (fn [_]
                                  (is (= ["/api/slow" "/api/fast"] (map :url @seen)) "the first was on its way")
                                  (is (= [[::ok "fast"]] (map (fn [[event-name answer]] [event-name (get-in answer [:body :which])]) @(:events w)))
                                      "and it told nobody, not even that it was aborted")
                                  (is (= 1 @(:aborted w)) "the connection of the first was closed, which is what aborting is")
                                  (is (= {} @(:inflight w)))))))))))

(deftest a-call-is-stopped-by-its-id
  (async done
         (finish done
                 (with-world (fn [_ _ res] (js/setTimeout #(reply! res 200 "{}") 150))
                   nil false
                   (fn [w seen]
                     (fetch/request! (:env w) {:id ::page :path ["api" "a"] :on-success [::ok] :on-failure [::failed]})
                     (-> (until #(seq @seen))
                         (.then (fn [_] (fetch/cancel! (:inflight w) ::page)))
                         (.then (fn [_] (pause 300)))
                         (.then (fn [_]
                                  (is (= [] @(:events w)) "it tells nobody anything")
                                  (is (= 1 @(:aborted w)) "and the request was aborted")
                                  (is (= {} @(:inflight w)))
                                  (fetch/cancel! (:inflight w) ::nothing-there)
                                  (is (= {} @(:inflight w)) "and stopping what is not there is nothing")))))))))

(deftest calls-with-other-ids-or-none-do-not-stop-one-another
  (async done
         (finish done
                 (.then (run-calls! (fn [_ request res] (js/setTimeout #(reply! res 200 (json {:url (:url request)})) 30))
                                    [{:id ::a :path ["api" "a"] :on-success [::ok]}
                                     {:id ::b :path ["api" "b"] :on-success [::ok]}
                                     {:path ["api" "c"] :on-success [::ok]}
                                     {:path ["api" "d"] :on-success [::ok]}])
                        (fn [{:keys [w]}]
                          (is (= #{"/api/a" "/api/b" "/api/c" "/api/d"} (set (map (fn [[_ answer]] (get-in answer [:body :url])) @(:events w)))))
                          (is (= 4 (count @(:events w))))
                          (is (= [4 0 0 0] (counts w))))))))

(deftest a-page-can-ask-for-many-things-at-once
  (async done
         (finish done
                 (.then (run-calls! (fn [_ request res] (js/setTimeout #(reply! res 200 (json {:url (:url request)})) 20))
                                    (for [n (range 12)] {:path ["api" "patients" (str "pt-" n) "summary"] :on-success [::ok]}))
                        (fn [{:keys [w seen]}]
                          (is (= 12 (count seen)))
                          (is (= 12 (count @(:events w))))
                          (is (= (set (map :url seen)) (set (map (fn [[_ answer]] (get-in answer [:body :url])) @(:events w)))))
                          (is (= [12 0 0 0] (counts w))))))))

;; ---------------------------------------------------------------------------
;; What the browser can do to a call

(defn- an-error-named [error-name]
  (let [e (js/Error. error-name)]
    (set! (.-name e) error-name)
    e))

(deftest a-fetch-that-fails-in-the-ways-a-browser-fails-is-told-of-as-what-it-is
  (async done
         (finish done
                 (sequentially
                  [["a call the browser aborted" (fn [_ _] (js/Promise.reject (an-error-named "AbortError"))) :aborted]
                   ["a network error" (fn [_ _] (js/Promise.reject (js/TypeError. "Failed to fetch"))) :unreachable]
                   ["a rejection with nothing in it" (fn [_ _] (js/Promise.reject nil)) :unreachable]
                   ["a rejection that is a text" (fn [_ _] (js/Promise.reject "boom")) :unreachable]
                   ["a fetch that throws" (fn [_ _] (throw (js/Error. "no fetch"))) :unexpected]
                   ["a body that cannot be read to its end" (fn [_ _] (js/Promise.resolve #js {:status 200 :text (fn [] (js/Promise.reject (js/TypeError. "terminated")))})) :unreachable]]
                  (fn [[label fetch-fn kind]]
                    (.then (run-calls! (fn [_ _ res] (reply! res 200 "{}"))
                                       [{:path ["api" "a"] :on-success [::ok] :on-failure [::failed]}]
                                       {:overrides {:fetch fetch-fn}})
                           (fn [{:keys [w]}]
                             (is (= kind (:kind (second (only-event w)))) label)
                             (is (= 1 (count @(:events w))) label))))))))

;; ---------------------------------------------------------------------------
;; The environment of the real thing

(deftest the-real-environment-gives-a-call-six-seconds-and-the-api-of-the-settings
  (is (= 6000 fetch/default-timeout-ms))
  (let [env (#'fetch/live-env)]
    (is (= 6000 (:timeout-ms env)))
    (is (= settings/api-base-url (:base-url env)))
    (is (= js/fetch (:fetch env)))
    (is (every? fn? (map env [:token! :renew! :login! :dispatch :on-unauthorized])))
    (is (instance? cljs.core/Atom (:inflight env)))))
