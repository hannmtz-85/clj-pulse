(ns pulse.platform.token-test
  "The keeper of a service's token (ADR-0021) with the clock and the token endpoint in the hands of
  the test: a clock that moves when told to, and a `fetch` that counts, fails or waits as the test
  says. Threads are real where the point is that two callers meet. What goes over HTTP is in
  `token-http-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.token :as token]
            [taoensso.telemere :as t])
  (:import (java.time Instant)
           (java.util.concurrent Callable Executors Future TimeUnit)))

(def ^:private start "2026-10-15T10:00:00Z")

(defn- grant
  "What the token endpoint gives for call number `n`."
  [n expires-in]
  {:access-token (str "token-" n) :expires-in expires-in})

(defn- counting
  "A fetch function that counts its calls in `calls` and gives `token-1`, `token-2`..., each good for
  `expires-in` seconds. `fails` is the set of call numbers that fail instead."
  [calls & {:keys [expires-in fails] :or {expires-in 300 fails #{}}}]
  (fn []
    (let [n (swap! calls inc)]
      (when (contains? fails n)
        (throw (ex-info "the endpoint failed" {:type ::token/unavailable :reason :server :status 503})))
      (grant n expires-in))))

(defn- keeper
  "A keeper on a clock of its own: `[source clock]`."
  [fetch & {:as options}]
  (let [c (clock/clock start)]
    [(token/source (merge {:fetch fetch :clock (:clock c) :client-id "pulse-test"} options)) c]))

(defn- unavailable
  "The ex-data of what `f` throws when it is an `::unavailable`; fails the test when it is anything
  else or nothing."
  [f]
  (try (f)
       (is false "nothing was thrown")
       (catch clojure.lang.ExceptionInfo e
         (is (= ::token/unavailable (:type (ex-data e))))
         (ex-data e))))

(defn- events [signals id]
  (filter #(= id (:id %)) signals))

;; ---------------------------------------------------------------------------
;; Asking, keeping, renewing early

(deftest nothing-is-asked-until-a-token-is-needed
  (let [calls (atom 0)
        [s _] (keeper (counting calls))]
    (is (zero? @calls) "starting a service does not call Keycloak")
    (is (= "token-1" (token/access-token s)))
    (is (= 1 @calls))))

(deftest a-token-is-kept-while-it-is-fresh
  (let [calls (atom 0)
        [s c] (keeper (counting calls))]
    (is (= "token-1" (token/access-token s)))
    (clock/advance! c 100000)
    (is (= "token-1" (token/access-token s)))
    (is (= "token-1" (token/access-token s)))
    (is (= 1 @calls) "no further request")))

(deftest a-token-is-renewed-when-the-margin-begins
  ;; 300 s of life and a margin of 60 s: the token is near its end from 240 s
  (let [calls (atom 0)
        [s c] (keeper (counting calls))]
    (is (= "token-1" (token/access-token s)))
    (clock/advance! c 239999)
    (is (= "token-1" (token/access-token s)) "a millisecond before")
    (is (= 1 @calls))
    (clock/advance! c 1)
    (is (= "token-2" (token/access-token s)) "at the moment")
    (is (= 2 @calls))
    (is (= "token-2" (token/access-token s)))
    (is (= 2 @calls))))

(deftest the-margin-is-at-most-half-the-lifetime
  ;; a token of 30 s with a margin of 60 s would be renewed on every call
  (let [calls (atom 0)
        [s c] (keeper (counting calls :expires-in 30) :renew-before-ms 60000)]
    (token/access-token s)
    (clock/advance! c 14999)
    (token/access-token s)
    (is (= 1 @calls))
    (clock/advance! c 1)
    (token/access-token s)
    (is (= 2 @calls) "after half of its life")))

(deftest the-margin-can-be-chosen
  (let [calls (atom 0)
        [s c] (keeper (counting calls) :renew-before-ms 10000)]
    (token/access-token s)
    (clock/advance! c 289999)
    (token/access-token s)
    (is (= 1 @calls))
    (clock/advance! c 1)
    (token/access-token s)
    (is (= 2 @calls))))

(deftest the-lifetime-is-counted-from-when-the-request-was-sent
  ;; the endpoint takes two seconds to answer: the token is that much older than it seems
  (let [calls (atom 0)
        c (clock/clock start)
        slow (fn [] (clock/advance! c 2000) (grant (swap! calls inc) 300))
        s (token/source {:fetch slow :clock (:clock c)})]
    (token/access-token s)
    (clock/advance! c 237999)
    (token/access-token s)
    (is (= 1 @calls) "239.999 s after the request was sent")
    (clock/advance! c 1)
    (token/access-token s)
    (is (= 2 @calls) "240 s after it")))

(deftest a-keeper-prints-whose-it-is-and-not-what-it-holds
  ;; a keeper in the data of an exception or of a log event must not print a bearer token
  (let [calls (atom 0)
        [s _] (keeper (counting calls))]
    (is (= "token-1" (token/access-token s)))
    (is (= "#token-source[pulse-test]" (pr-str s)))
    (is (nil? (re-find #"token-1" (pr-str {:session {:tokens s}}))) "wherever it is")
    (is (nil? (re-find #"token-1" (pr-str (ex-info "x" {:keeper s})))))
    (is (= "token-1" (:token @(:state s))) "it is still a map to the code that knows it")))

(deftest a-renewal-is-logged-without-the-token
  (let [calls (atom 0)
        [s _] (keeper (counting calls))
        {:keys [signals]} (t/with-signals true (token/access-token s))
        [renewed] (events signals :pulse.token/renewed)]
    (is (= :info (:level renewed)))
    (is (= {:client "pulse-test" :expires-in-s 300 :renew-in-s 240} (:data renewed)))
    (is (not (re-find #"token-1" (pr-str signals))))))

;; ---------------------------------------------------------------------------
;; A grant in hand: the first token of a person, from the exchange of a code (ADR-0028)

(defn- starting-with
  "A keeper that begins with `initial` on a clock of its own: `[source clock calls]`."
  [initial & {:as options}]
  (let [calls (atom 0)
        c (clock/clock start)]
    [(token/source (merge {:fetch (counting calls) :clock (:clock c) :client-id "pulse-test" :initial initial} options))
     c
     calls]))

(deftest a-keeper-that-starts-with-a-grant-asks-for-nothing-until-it-is-near-its-end
  (let [[s c calls] (starting-with {:access-token "first" :expires-in 300})]
    (is (= "first" (token/access-token s)))
    (is (zero? @calls) "the first token is the one in hand")
    (clock/advance! c 239999)
    (is (= "first" (token/access-token s)))
    (is (zero? @calls) "a millisecond before the margin of 60 s begins")
    (clock/advance! c 1)
    (is (= "token-1" (token/access-token s)) "and then it is renewed by the fetch, as any token is")
    (is (= 1 @calls))
    (is (= "token-1" (token/access-token s)))
    (is (= 1 @calls))))

(deftest the-life-of-the-grant-is-counted-from-when-it-was-asked-for
  (testing "by default, from the moment the keeper is made"
    (let [calls (atom 0)
          c (clock/clock start)]
      (clock/advance! c 1000000)
      (let [s (token/source {:fetch (counting calls) :clock (:clock c) :initial {:access-token "first" :expires-in 300}})]
        (clock/advance! c 239999)
        (is (= "first" (token/access-token s)))
        (is (zero? @calls))
        (clock/advance! c 1)
        (is (= "token-1" (token/access-token s))))))
  (testing "or from the moment the request was sent, which is earlier than the moment the keeper was made"
    (let [asked-at (.minusSeconds (java.time.Instant/parse start) 100)
          [s c calls] (starting-with {:access-token "first" :expires-in 300 :asked-at asked-at})]
      (is (= "first" (token/access-token s)))
      (is (zero? @calls) "it has 200 s left, and its margin begins in 140")
      (clock/advance! c 139999)
      (is (= "first" (token/access-token s)))
      (is (zero? @calls))
      (clock/advance! c 1)
      (is (= "token-1" (token/access-token s)))
      (is (= 1 @calls)))))

(deftest a-grant-that-has-expired-already-is-not-used
  (let [asked-at (.minusSeconds (java.time.Instant/parse start) 300)
        [s _ calls] (starting-with {:access-token "stale" :expires-in 300 :asked-at asked-at})]
    (is (= "token-1" (token/access-token s)) "300 s after it was asked for, with a life of 300 s: it has expired")
    (is (= 1 @calls))))

(deftest what-else-the-grant-carries-is-not-the-keepers-business
  ;; the refresh token and the id token of a person are held by whoever renews with them
  (let [[s _ calls] (starting-with {:access-token "first" :expires-in 300 :refresh-token "r" :id-token "i"})]
    (is (= "first" (token/access-token s)))
    (is (zero? @calls))
    (is (= #{:token :expires-at :renew-at} (set (keys @(:state s)))) "the keeper keeps the token and its times, and nothing else")))

(deftest a-grant-that-is-not-one-cannot-start-a-keeper
  (doseq [bad [{} {:access-token "" :expires-in 300} {:access-token "  " :expires-in 300} {:access-token nil :expires-in 300}
               {:access-token "t"} {:access-token "t" :expires-in 0} {:access-token "t" :expires-in -5}
               {:access-token "t" :expires-in "300"} {:access-token 42 :expires-in 300}]]
    (let [e (try (starting-with bad) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (pr-str bad))
      (is (= {:type ::token/invalid-initial-grant} (ex-data e)) "and the grant, which may be a token, is not in it"))))

(deftest a-renewal-that-fails-leaves-a-caller-the-token-in-hand-while-it-is-valid
  (let [calls (atom 0)
        [s c] (starting-with {:access-token "first" :expires-in 300} :fetch (counting calls :fails #{1}))]
    (clock/advance! c 250000)
    (is (= "first" (token/access-token s)))
    (is (= 1 @calls))))

;; ---------------------------------------------------------------------------
;; When Keycloak cannot be asked

(deftest an-expired-token-is-never-returned
  (let [calls (atom 0)
        [s c] (keeper (counting calls :fails #{2}))]
    (is (= "token-1" (token/access-token s)))
    (clock/advance! c 301000)
    (is (= {:type ::token/unavailable :reason :server :status 503}
           (unavailable #(token/access-token s)))
        "it expired and the endpoint failed: an error, not the old token")
    (is (= 2 @calls))))

(deftest nobody-asks-again-until-the-retry-delay-is-over
  (let [calls (atom 0)
        [s c] (keeper (counting calls :fails #{1 2}) :retry-delay-ms 5000)]
    (is (= :server (:reason (unavailable #(token/access-token s)))))
    (is (= 1 @calls))
    (testing "within the delay the answer is immediate and the endpoint is not asked"
      (clock/advance! c 4999)
      (is (= {:type ::token/unavailable :reason :backing-off :last-reason :server}
             (unavailable #(token/access-token s))))
      (is (= 1 @calls)))
    (testing "after it, a new try; and a failure starts the delay again"
      (clock/advance! c 1)
      (is (= :server (:reason (unavailable #(token/access-token s)))))
      (is (= 2 @calls))
      (clock/advance! c 4999)
      (is (= :backing-off (:reason (unavailable #(token/access-token s)))))
      (is (= 2 @calls)))
    (testing "and when the endpoint is back, a token"
      (clock/advance! c 1)
      (is (= "token-3" (token/access-token s)))
      (is (= 3 @calls))
      (is (= "token-3" (token/access-token s)) "the delay is gone with the failure"))))

(deftest while-the-token-is-valid-a-failed-renewal-is-not-the-callers-problem
  (let [calls (atom 0)
        [s c] (keeper (counting calls :fails #{2}) :retry-delay-ms 5000)]
    (is (= "token-1" (token/access-token s)))
    (clock/advance! c 240000)
    (let [{:keys [signals value]} (t/with-signals true (token/access-token s))]
      (is (= "token-1" value) "near its end and the endpoint failing: the caller has the token it had")
      (is (= 2 @calls))
      (is (= 1 (count (events signals :pulse.token/failed))) "and the failure is in the log")
      (is (= :warn (:level (first (events signals :pulse.token/failed))))))
    (testing "nobody tries again for the delay"
      (clock/advance! c 4999)
      (is (= "token-1" (token/access-token s)))
      (is (= 2 @calls)))
    (testing "then someone does, and the new token is kept"
      (clock/advance! c 1)
      (is (= "token-3" (token/access-token s)))
      (is (= 3 @calls)))))

(deftest a-token-that-expires-while-a-renewal-is-failing-is-not-returned
  ;; the renewal is attempted with the token near its end, takes longer than the token has left,
  ;; and fails: what the caller must get is the failure, not a token that is now dead
  (let [calls (atom 0)
        c (clock/clock start)
        slow-and-failing (fn []
                           (if (= 1 (swap! calls inc))
                             (grant 1 300)
                             (do (clock/advance! c 70000)
                                 (throw (ex-info "timed out" {:type ::token/unavailable :reason :timeout})))))
        s (token/source {:fetch slow-and-failing :clock (:clock c)})]
    (token/access-token s)
    (clock/advance! c 250000)
    (is (= {:reason :backing-off :last-reason :timeout}
           (select-keys (unavailable #(token/access-token s)) [:reason :last-reason]))
        "the attempt failed, so the endpoint is not asked again at once; the reason it failed is kept")))

(deftest what-went-wrong-is-said-by-its-reason
  (doseq [reason [:rejected :server :timeout :connect :bad-response]]
    (let [[s _] (keeper (fn [] (throw (ex-info "x" {:type ::token/unavailable :reason reason}))))]
      (is (= reason (:reason (unavailable #(token/access-token s)))) (str reason))
      (is (false? (token/available? s))))))

(deftest anything-else-a-fetch-throws-is-wrapped
  (let [[s _] (keeper (fn [] (throw (IllegalStateException. "boom"))))
        {:keys [signals value]} (t/with-signals true
                                  (try (token/access-token s)
                                       (catch clojure.lang.ExceptionInfo e e)))]
    (is (= {:type ::token/unavailable :reason :unexpected} (select-keys (ex-data value) [:type :reason])))
    (is (instance? IllegalStateException (ex-cause value)))
    (is (= "boom" (ex-message (:error (first (events signals :pulse.token/failed))))) "with its cause in the log")))

(deftest an-interruption-during-a-request-is-not-a-failure-of-the-endpoint
  ;; a job interrupted at shutdown is not a reason to leave the endpoint alone for the retry delay
  (let [calls (atom 0)
        fetch (fn []
                (if (= 1 (swap! calls inc))
                  (throw (InterruptedException. "the job was interrupted"))
                  (grant 2 300)))
        [s _] (keeper fetch)]
    (is (thrown? InterruptedException (token/access-token s)))
    (is (= "token-2" (token/access-token s)) "and the next caller asks at once")
    (is (= 2 @calls))))

(deftest a-grant-that-is-not-one-is-refused
  (doseq [bad [{} {:access-token "" :expires-in 300} {:access-token "  " :expires-in 300}
               {:access-token nil :expires-in 300} {:access-token "t"} {:access-token "t" :expires-in 0}
               {:access-token "t" :expires-in -5} {:access-token "t" :expires-in "300"}
               {:access-token 42 :expires-in 300}]]
    (let [[s _] (keeper (constantly bad))]
      (is (= :bad-response (:reason (unavailable #(token/access-token s)))) (pr-str bad)))))

(deftest available-says-whether-a-token-can-be-had
  (let [calls (atom 0)
        [s c] (keeper (counting calls :fails #{2}) :retry-delay-ms 5000)]
    (is (true? (token/available? s)))
    (clock/advance! c 301000)
    (is (false? (token/available? s)) "expired, and the endpoint failed")
    (clock/advance! c 5000)
    (is (true? (token/available? s)))))

;; ---------------------------------------------------------------------------
;; Callers that meet

(defn- in-threads
  "Runs `n` calls of `f`, each on a virtual thread of its own, and returns their futures."
  [n f]
  (let [pool (Executors/newVirtualThreadPerTaskExecutor)]
    (doall (repeatedly n #(.submit pool ^Callable f)))))

(defn- results [futures]
  (mapv #(.get ^Future % 5 TimeUnit/SECONDS) futures))

(deftest many-callers-with-no-token-ask-once-and-share-the-answer
  (let [calls (atom 0)
        gate (promise)
        inside (promise)
        c (clock/clock start)
        fetch (fn []
                (let [n (swap! calls inc)]
                  (deliver inside true)
                  @gate
                  (grant n 300)))
        s (token/source {:fetch fetch :clock (:clock c)})
        futures (in-threads 30 #(token/access-token s))]
    (is (deref inside 5000 nil) "one of them is asking")
    (deliver gate :go)
    (is (= ["token-1"] (distinct (results futures))))
    (is (= 1 @calls))))

(deftest while-one-caller-renews-the-others-go-on-with-the-token-they-have
  (let [calls (atom 0)
        gate (promise)
        inside (promise)
        c (clock/clock start)
        fetch (fn []
                (let [n (swap! calls inc)]
                  (when (= 2 n)
                    (deliver inside true)
                    @gate)
                  (grant n 300)))
        s (token/source {:fetch fetch :clock (:clock c)})]
    (token/access-token s)
    (clock/advance! c 250000)
    (let [renewer (future (token/access-token s))]
      (is (deref inside 5000 nil) "the renewal is under way and has not finished")
      (testing "a caller in the meantime is not kept waiting, and gets the token that is still good"
        (is (= "token-1" (deref (future (token/access-token s)) 2000 :it-waited)))
        (is (= ["token-1" "token-1" "token-1"] (results (in-threads 3 #(token/access-token s))))))
      (is (= 2 @calls) "and nobody else asked")
      (deliver gate :go)
      (is (= "token-2" (deref renewer 5000 nil)))
      (is (= "token-2" (token/access-token s))))))

(deftest a-caller-waiting-for-a-token-can-be-interrupted
  (let [gate (promise)
        inside (promise)
        c (clock/clock start)
        fetch (fn [] (deliver inside true) @gate (grant 1 300))
        s (token/source {:fetch fetch :clock (:clock c)})
        renewer (future (token/access-token s))
        outcome (promise)
        waiter (Thread. ^Runnable (fn []
                                    (try (token/access-token s)
                                         (deliver outcome :got-a-token)
                                         (catch InterruptedException _ (deliver outcome :interrupted)))))]
    (is (deref inside 5000 nil))
    (.start waiter)
    (.interrupt waiter)
    (is (= :interrupted (deref outcome 5000 nil)) "it leaves the queue and is not taken for a failure of the endpoint")
    (deliver gate :go)
    (is (= "token-1" (deref renewer 5000 nil)))
    (is (= "token-1" (token/access-token s)) "and nothing was recorded against the endpoint")))

(deftest callers-that-find-a-token-in-hand-near-its-end-share-one-renewal
  ;; the keeper of a person's session: twenty requests of the same person meet a token that is near its end
  (let [calls (atom 0)
        gate (promise)
        inside (promise)
        c (clock/clock start)
        fetch (fn []
                (let [n (swap! calls inc)]
                  (deliver inside true)
                  @gate
                  (grant n 300)))
        s (token/source {:fetch fetch :clock (:clock c) :initial {:access-token "first" :expires-in 300}})]
    (clock/advance! c 250000)
    (let [renewer (future (token/access-token s))]
      (is (deref inside 5000 nil) "one of them is renewing")
      (is (= ["first"] (distinct (results (in-threads 19 #(token/access-token s)))))
          "and the others go on with the token they have, which is still good")
      (deliver gate :go)
      (is (= "token-1" (deref renewer 5000 nil)))
      (is (= 1 @calls)))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private gen-step
  "One step of a day in the life of a service: time passes, or something asks for the token."
  (gen/frequency [[3 (gen/fmap (fn [ms] [:wait ms]) (gen/choose 0 400000))]
                  [1 (gen/fmap (fn [ms] [:wait ms]) (gen/choose 0 3000))]
                  [4 (gen/return [:call])]]))

(def ^:private gen-outcome
  "What the endpoint does when it is asked: fails, or gives a token of this many seconds."
  (gen/frequency [[1 (gen/return :fail)]
                  [3 (gen/choose 10 600)]]))

(defn- play
  "Plays `steps` on a keeper whose endpoint behaves as `outcomes` says, one for each time it is
  asked (the last repeats). Returns what happened: a vector of `:call`s with what each returned
  and when, and of the `:fetch`es with when and how it went."
  [steps outcomes retry-delay-ms]
  (let [c (clock/clock start)
        asked (atom [])
        expiries (atom {})
        fetch (fn []
                (let [n (count @asked)
                      outcome (nth outcomes (min n (dec (count outcomes))))
                      now (clock/instant c)]
                  (swap! asked conj {:at now :outcome outcome})
                  (if (= :fail outcome)
                    (throw (ex-info "failed" {:type ::token/unavailable :reason :server}))
                    (do (swap! expiries assoc (str "t" n) (.plusSeconds now outcome))
                        {:access-token (str "t" n) :expires-in outcome}))))
        s (token/source {:fetch fetch :clock (:clock c) :retry-delay-ms retry-delay-ms})
        calls (reduce (fn [calls [step ms]]
                        (case step
                          :wait (do (clock/advance! c ms) calls)
                          :call (let [now (clock/instant c)
                                      result (try {:token (token/access-token s)}
                                                  (catch clojure.lang.ExceptionInfo e {:error (ex-data e)}))]
                                  (conj calls (assoc result :at now)))))
                      []
                      steps)]
    {:calls calls :asked @asked :expiries @expiries}))

(defspec a-token-is-never-returned-after-it-expired-and-the-endpoint-is-not-asked-too-soon 200
  (prop/for-all [steps (gen/vector gen-step 1 60)
                 outcomes (gen/vector gen-outcome 1 12)]
    (let [{:keys [calls asked expiries]} (play steps outcomes 5000)]
      (and
       ;; what is returned is good at the moment it is returned; what is thrown is an ::unavailable
       (every? (fn [{:keys [token error at]}]
                 (if token
                   (.isBefore ^Instant at ^Instant (get expiries token))
                   (= ::token/unavailable (:type error))))
               calls)
       ;; after a failure the endpoint is left alone for the retry delay
       (every? (fn [[before after]]
                 (or (not= :fail (:outcome before))
                     (not (.isBefore ^Instant (:at after) (.plusMillis ^Instant (:at before) 5000)))))
               (partition 2 1 asked))))))

(defspec with-an-endpoint-that-works-a-token-has-more-than-its-margin-left-when-it-is-returned 200
  ;; This is what "renewed early" means: whoever gets a token has time to use it
  (prop/for-all [steps (gen/vector gen-step 1 60)
                 lifetimes (gen/vector (gen/choose 10 600) 1 12)]
    (let [{:keys [calls asked expiries]} (play steps lifetimes 5000)]
      (every? (fn [{:keys [token at]}]
                (let [n (parse-long (subs token 1))
                      lifetime-ms (* 1000 (:outcome (nth asked n)))
                      margin-ms (min 60000 (quot lifetime-ms 2))
                      left-ms (- (.toEpochMilli ^Instant (get expiries token)) (.toEpochMilli ^Instant at))]
                  (> left-ms margin-ms)))
              calls))))
