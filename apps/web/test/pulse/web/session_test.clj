(ns pulse.web.session-test
  "The portal's sessions (ADR-0028): the store that implements Ring's protocol with what Ring's own lacks (entries that end, and
  limits), then what a person sees of it through the whole handler: a session that goes idle, one that reaches its maximum, a
  cookie of a session that is not there, and the tokens of a session that cannot be renewed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.token :as token]
            [pulse.web.session :as session]
            [pulse.web.test.harness :as h :refer [header with-web]]
            [ring.middleware.session :as ring-session]
            [ring.middleware.session.store :as store])
  (:import (java.util.concurrent CountDownLatch)))

(def ^:private start "2026-10-19T10:00:00Z")

(def ^:private minute 60000)
(def ^:private hour (* 60 minute))

(defn- fresh
  "A store on a clock of its own: `[store clock]`."
  [& {:as options}]
  (let [c (clock/clock start)]
    [(session/memory-store (assoc options :clock (:clock c))) c]))

(def ^:private person {:person {:id "u-1" :name "Ana"}})
(def ^:private pending {:sign-in {:state "s" :verifier "v" :nonce "n" :return-to "/"}})

;; ---------------------------------------------------------------------------
;; Keys

(deftest a-new-entry-gets-a-key-that-cannot-be-guessed
  (let [[s _] (fresh)
        keys (repeatedly 200 #(store/write-session s nil person))]
    (is (every? #(re-matches #"[A-Za-z0-9_-]{43}" %) keys) "256 bits, written to be safe in a cookie")
    (is (= 200 (count (set keys))))))

(deftest what-is-written-is-read
  (let [[s _] (fresh)
        k (store/write-session s nil person)]
    (is (= person (store/read-session s k)))
    (testing "and what is not there is nothing"
      (is (nil? (store/read-session s "nobody")))
      (is (nil? (store/read-session s nil)))
      (is (nil? (store/read-session s ""))))))

(deftest a-write-to-a-key-that-is-there-changes-the-entry-under-the-same-key
  (let [[s _] (fresh)
        k (store/write-session s nil person)
        k2 (store/write-session s k (assoc person :csrf "t"))]
    (is (= k k2))
    (is (= (assoc person :csrf "t") (store/read-session s k)))
    (is (= {:sessions 1 :pending 0} (session/counts s)))))

(deftest a-write-to-a-key-that-is-not-there-is-a-write-under-a-new-key
  ;; session fixation: the browser must not be able to choose the key of a session
  (let [[s _] (fresh)
        k (store/write-session s "chosen-by-the-browser" person)]
    (is (not= "chosen-by-the-browser" k))
    (is (nil? (store/read-session s "chosen-by-the-browser")))
    (is (= person (store/read-session s k)))))

(deftest deleting-takes-the-entry-away-and-says-there-is-no-key-to-keep
  (let [[s _] (fresh)
        k (store/write-session s nil person)]
    (is (nil? (store/delete-session s k)))
    (is (nil? (store/read-session s k)))
    (is (nil? (store/delete-session s k)) "twice is fine")
    (is (nil? (store/delete-session s nil)))
    (is (nil? (store/delete-session s "nobody")))))

(deftest a-store-prints-how-many-it-holds-and-not-what
  (let [[s _] (fresh)]
    (store/write-session s nil {:person {:id "u-1" :name "Ana"} :tokens {:token "secret-token"} :csrf "secret-csrf"})
    (store/write-session s nil pending)
    (let [text (pr-str s)]
      (is (= "#session-store[2 entries]" text))
      (is (not (str/includes? text "secret"))))
    (is (not (str/includes? (str (list s)) "secret-token")))))

;; ---------------------------------------------------------------------------
;; Ending

(deftest a-signed-in-session-ends-after-30-minutes-without-a-request
  (let [[s c] (fresh)
        k (store/write-session s nil person)]
    (clock/advance! c (dec (* 30 minute)))
    (is (some? (store/read-session s k)) "a millisecond before the limit")
    (clock/advance! c (dec (* 30 minute)))
    (is (some? (store/read-session s k)) "the read at the last moment kept it alive: the limit counts from the last request")
    (clock/advance! c (* 30 minute))
    (is (nil? (store/read-session s k)) "at the limit")
    (is (= {:sessions 0 :pending 0} (session/counts s)))))

(deftest a-signed-in-session-ends-10-hours-after-the-sign-in-whatever-the-requests
  (let [[s c] (fresh)
        k (store/write-session s nil person)]
    (dotimes [_ 23]
      (clock/advance! c (* 25 minute))
      (is (some? (store/read-session s k)) "kept alive by a request every 25 minutes"))
    (clock/advance! c (dec (* 25 minute)))
    (is (some? (store/read-session s k)) "9 hours 59 minutes 59.999 seconds after the sign-in")
    (clock/advance! c 1)
    (is (nil? (store/read-session s k)) "10 hours after it, activity or none")))

(deftest the-maximum-counts-from-the-sign-in-and-not-from-the-last-write
  (let [[s c] (fresh)
        k (store/write-session s nil person)]
    (dotimes [_ 21]
      (clock/advance! c (* 25 minute))
      (is (some? (store/read-session s k)) "kept alive by a request every 25 minutes"))
    (clock/advance! c (* 25 minute))
    (is (= k (store/write-session s k (assoc person :csrf "t"))) "9 hours 10 minutes after the sign-in: it is the same entry")
    (clock/advance! c (* 25 minute))
    (is (some? (store/read-session s k)) "9 hours 35 minutes")
    (clock/advance! c (* 25 minute))
    (is (nil? (store/read-session s k)) "10 hours after the sign-in: writing again did not give it ten hours more")))

(deftest a-write-that-changes-the-kind-of-an-entry-is-a-new-entry-that-makes-room
  ;; the limit of each kind holds whatever is written: this is not what the portal does (it recreates), it is what a store owes
  (let [[s c] (fresh :max-sessions 2 :max-pending 2)
        a (store/write-session s nil person)
        _ (clock/advance! c 1000)
        b (store/write-session s nil person)
        p (store/write-session s nil pending)]
    (clock/advance! c 1000)
    (let [turned (store/write-session s p person)]
      (is (not= p turned) "a pending sign-in written as a session is a new entry, under a new key")
      (is (nil? (store/read-session s p)))
      (is (= person (store/read-session s turned)))
      (is (= {:sessions 2 :pending 0} (session/counts s)))
      (is (nil? (store/read-session s a)) "and the session least recently used went to make room for it")
      (is (some? (store/read-session s b))))
    (testing "the other way round too"
      (let [[s _] (fresh :max-sessions 2 :max-pending 1)
            session-key (store/write-session s nil person)
            pending-key (store/write-session s nil pending)
            turned (store/write-session s session-key pending)]
        (is (not= session-key turned))
        (is (= {:sessions 0 :pending 1} (session/counts s)))
        (is (nil? (store/read-session s pending-key)) "the pending one that was there was the oldest")))))

(deftest a-pending-sign-in-ends-after-10-minutes-whatever-the-requests
  (let [[s c] (fresh)
        k (store/write-session s nil pending)]
    (clock/advance! c (* 9 minute))
    (is (some? (store/read-session s k)))
    (clock/advance! c (dec minute))
    (is (some? (store/read-session s k)) "a millisecond before: a read does not keep a sign-in alive")
    (clock/advance! c 1)
    (is (nil? (store/read-session s k)) "ten minutes after it began")))

(deftest what-has-ended-is-not-counted-and-is-gone-at-the-next-write
  (let [[s c] (fresh)]
    (store/write-session s nil person)
    (store/write-session s nil pending)
    (clock/advance! c (* 11 minute))
    (is (= {:sessions 1 :pending 0} (session/counts s)) "the sign-in has ended, the session has not")
    (clock/advance! c (* 20 minute))
    (is (= {:sessions 0 :pending 0} (session/counts s)))
    (store/write-session s nil person)
    (is (= {:sessions 1 :pending 0} (session/counts s)))))

(deftest the-limits-can-be-chosen
  (let [[s c] (fresh :idle-ms 1000 :absolute-ms 5000 :pending-ms 200)
        a (store/write-session s nil person)
        b (store/write-session s nil pending)]
    (clock/advance! c 200)
    (is (some? (store/read-session s a)))
    (is (nil? (store/read-session s b)))
    (clock/advance! c 999)
    (is (some? (store/read-session s a)))
    (clock/advance! c 1000)
    (is (nil? (store/read-session s a)))))

;; ---------------------------------------------------------------------------
;; Limits

(deftest when-pending-sign-ins-are-full-the-oldest-one-goes-and-never-a-session
  (let [[s c] (fresh :max-pending 3)
        session-key (store/write-session s nil person)
        pendings (doall (for [i (range 3)]
                          (do (clock/advance! c 1000)
                              (store/write-session s nil (assoc-in pending [:sign-in :state] (str "s" i))))))]
    (is (= {:sessions 1 :pending 3} (session/counts s)))
    (clock/advance! c 1000)
    (let [newest (store/write-session s nil (assoc-in pending [:sign-in :state] "s3"))]
      (is (= {:sessions 1 :pending 3} (session/counts s)))
      (is (nil? (store/read-session s (first pendings))) "the oldest")
      (is (every? #(some? (store/read-session s %)) (rest pendings)))
      (is (some? (store/read-session s newest)))
      (is (some? (store/read-session s session-key)) "and the session was not touched"))))

(deftest the-pending-sign-in-that-goes-is-the-oldest-by-when-it-began-and-not-by-its-last-request
  ;; a browser that asks again and again about its sign-in must not keep it from being the oldest
  (let [[s c] (fresh :max-pending 3)
        [a b d] (doall (for [i (range 3)]
                         (do (clock/advance! c 1000)
                             (store/write-session s nil (assoc-in pending [:sign-in :state] (str "s" i))))))]
    (clock/advance! c 1000)
    (store/read-session s a)
    (clock/advance! c 1000)
    (store/write-session s nil pending)
    (is (nil? (store/read-session s a)) "a was the first, and has been asked about since")
    (is (every? #(some? (store/read-session s %)) [b d]))))

(deftest a-flood-of-pending-sign-ins-pushes-out-no-session
  (let [[s c] (fresh :max-pending 5 :max-sessions 3)
        keys (doall (for [_ (range 3)] (store/write-session s nil person)))]
    (dotimes [_ 200]
      (clock/advance! c 1)
      (store/write-session s nil pending))
    (is (= {:sessions 3 :pending 5} (session/counts s)))
    (is (every? #(some? (store/read-session s %)) keys))))

(deftest when-sessions-are-full-the-one-least-recently-used-goes-and-never-a-pending-sign-in
  (let [[s c] (fresh :max-sessions 3 :max-pending 2)
        pending-key (store/write-session s nil pending)
        [a b d] (doall (for [_ (range 3)] (do (clock/advance! c 1000) (store/write-session s nil person))))]
    (clock/advance! c 1000)
    (store/read-session s a)
    (clock/advance! c 1000)
    (let [e (store/write-session s nil person)]
      (is (= {:sessions 3 :pending 1} (session/counts s)))
      (is (nil? (store/read-session s b)) "b was the one nobody had asked for the longest")
      (is (every? #(some? (store/read-session s %)) [a d e]))
      (is (some? (store/read-session s pending-key))))))

(deftest rewriting-an-entry-takes-no-room
  (let [[s _] (fresh :max-sessions 2 :max-pending 2)
        a (store/write-session s nil person)
        b (store/write-session s nil person)]
    (dotimes [_ 10] (store/write-session s a (assoc person :csrf "x")))
    (is (= {:sessions 2 :pending 0} (session/counts s)))
    (is (every? #(some? (store/read-session s %)) [a b]))))

;; ---------------------------------------------------------------------------
;; Taking a pending sign-in, once

(deftest a-pending-sign-in-can-be-taken-once
  (let [[s _] (fresh)
        k (store/write-session s nil pending)]
    (is (= pending (session/consume! s k)))
    (is (nil? (session/consume! s k)))
    (is (nil? (store/read-session s k)))
    (is (= {:sessions 0 :pending 0} (session/counts s)))))

(deftest only-a-pending-sign-in-that-has-not-ended-can-be-taken
  (let [[s c] (fresh)
        signed-in (store/write-session s nil person)
        old (store/write-session s nil pending)]
    (is (nil? (session/consume! s signed-in)) "a session is never taken this way")
    (is (some? (store/read-session s signed-in)) "and it is still there")
    (clock/advance! c (* 10 minute))
    (is (nil? (session/consume! s old)) "ended")
    (is (nil? (session/consume! s nil)))
    (is (nil? (session/consume! s "nobody")))))

(deftest of-requests-that-take-the-same-sign-in-at-once-one-gets-it
  (dotimes [_ 20]
    (let [[s _] (fresh)
          k (store/write-session s nil pending)
          n 12
          go (CountDownLatch. 1)
          futures (doall (for [_ (range n)] (future (.await go) (session/consume! s k))))]
      (.countDown go)
      (let [results (mapv deref futures)]
        (is (= 1 (count (remove nil? results))))
        (is (= [pending] (distinct (remove nil? results))))))))

;; ---------------------------------------------------------------------------
;; A model of the store, to compare it with

(defn- model-expired? [{:keys [data created touched]} now {:keys [idle absolute pending]}]
  (if (:person data)
    (or (>= (- now touched) idle) (>= (- now created) absolute))
    (>= (- now created) pending)))

(defn- model-write
  "The model of a write of `data` under `k` (a key that may not be there) at `now`, as a pure function of a map from key to
  entry: expired entries are dropped, an entry that is there is changed, and a new one makes room first. The new key is `new-k`."
  [m k new-k data now limits {:keys [max-sessions max-pending]}]
  (let [live (into {} (remove (fn [[_ e]] (model-expired? e now limits))) m)
        signed? (boolean (:person data))]
    (if (and k (contains? live k) (= signed? (boolean (:person (:data (live k))))))
      (assoc live k (assoc (live k) :data data :touched now))
      (let [live (dissoc live k)
            of-kind (filter (fn [[_ e]] (= signed? (boolean (:person (:data e))))) live)
            limit (if signed? max-sessions max-pending)
            order (if signed? :touched :created)
            room (if (< (count of-kind) limit)
                   live
                   (apply dissoc live (map key (take (inc (- (count of-kind) limit)) (sort-by (comp order val) of-kind)))))]
        (assoc room new-k {:data data :created now :touched now})))))

(def ^:private gen-operation
  (gen/frequency [[3 (gen/return [:write-pending])]
                  [3 (gen/return [:write-session])]
                  [3 (gen/fmap (fn [i] [:read i]) (gen/choose 0 20))]
                  [2 (gen/fmap (fn [i] [:rewrite i]) (gen/choose 0 20))]
                  [1 (gen/fmap (fn [i] [:delete i]) (gen/choose 0 20))]
                  [1 (gen/fmap (fn [i] [:take i]) (gen/choose 0 20))]
                  [4 (gen/fmap (fn [ms] [:advance ms]) (gen/one-of [(gen/choose 0 1000) (gen/choose 0 (* 12 minute))
                                                                    (gen/choose 0 (* 3 hour))]))]]))

(defspec the-store-does-what-the-model-does 150
  (prop/for-all [operations (gen/vector gen-operation 1 80)]
    (let [limits {:idle (* 30 minute) :absolute (* 10 hour) :pending (* 10 minute) :max-sessions 4 :max-pending 3}
          [s c] (fresh :max-sessions 4 :max-pending 3)
          now (atom 0)
          known (atom [])
          model (atom {})
          nth-key (fn [i] (when (seq @known) (nth @known (mod i (count @known)))))
          ok? (atom true)
          same? (fn [a b] (when-not (= a b) (reset! ok? false)))]
      (doseq [[op arg] operations]
        (case op
          (:write-pending :write-session)
          (let [data (if (= op :write-pending) pending person)
                k (store/write-session s nil data)]
            (swap! model model-write nil k data @now limits {:max-sessions 4 :max-pending 3})
            (swap! known conj k))

          :rewrite
          (when-let [k (nth-key arg)]
            (let [data (assoc person :csrf (str @now))
                  k2 (store/write-session s k data)]
              (swap! model model-write k k2 data @now limits {:max-sessions 4 :max-pending 3})
              (swap! known conj k2)))

          :read
          (when-let [k (nth-key arg)]
            (let [expected (let [e (get @model k)]
                             (when (and e (not (model-expired? e @now limits))) e))]
              (same? (:data expected) (store/read-session s k))
              (swap! model (fn [m] (if expected
                                     (assoc-in m [k :touched] @now)
                                     (dissoc m k))))))

          :delete
          (when-let [k (nth-key arg)]
            (store/delete-session s k)
            (swap! model dissoc k))

          :take
          (when-let [k (nth-key arg)]
            (let [e (get @model k)
                  takeable? (and e (not (:person (:data e))) (not (model-expired? e @now limits)))]
              (same? (when takeable? (:data e)) (session/consume! s k))
              (when takeable? (swap! model dissoc k))))

          :advance
          (do (swap! now + arg)
              (clock/advance! c arg))))
      (let [live (into {} (remove (fn [[_ e]] (model-expired? e @now limits))) @model)
            counts {:sessions (count (filter (comp :person :data val) live))
                    :pending (count (remove (comp :person :data val) live))}]
        (same? counts (session/counts s))
        (same? true (and (<= (:sessions counts) 4) (<= (:pending counts) 3))))
      @ok?)))

;; ---------------------------------------------------------------------------
;; The cookie

(deftest the-cookie-has-the-name-the-portal-documents
  (is (= "pulse-session" session/cookie-name)))

(deftest the-sessions-a-handler-makes-ask-for-a-new-key
  ;; a pending sign-in and a person who has just signed in are new entries whatever the browser came with (session fixation)
  (is (true? (:recreate (meta (session/pending-sign-in {:state "s"})))))
  (is (true? (:recreate (meta (session/signed-in {:person {:id "u"} :tokens :t :csrf "c"}))))))

(deftest forgetting-a-cookie-says-the-same-cookie-is-empty-and-has-gone
  (let [response (session/forget-cookie {:status 401 :body "x"})]
    (is (= {:value "" :path "/" :max-age 0} (get-in response [:cookies session/cookie-name])) "the same name and path, or it would not be it")
    (is (= {:status 401 :body "x"} (dissoc response :cookies)) "and nothing else of the response changes")))

(defn- cookie-of [response]
  (first (h/set-cookies response)))

(deftest the-cookie-is-secure-when-the-portal-is-served-over-https
  (doseq [[secure? expected] [[true true] [false false] [nil false]]]
    (let [[s _] (fresh)
          handler (session/wrap-session (fn [_] {:status 200 :body "" :session pending}) s {:secure? secure?})
          cookie (cookie-of (handler {:request-method :get :uri "/" :headers {}}))
          attributes (set (map str/lower-case (rest (str/split cookie #";\s*"))))]
      (is (= expected (contains? attributes "secure")) (str secure?))
      (is (every? attributes ["httponly" "samesite=lax" "path=/"])))))

(deftest ending-a-session-deletes-the-entry-and-tells-the-browser-to-drop-the-cookie
  (let [[s _] (fresh)
        k (store/write-session s nil person)
        handler (session/wrap-session (fn [_] (session/end {:status 200 :body ""})) s {})
        response (handler {:request-method :get :uri "/" :headers {"cookie" (str session/cookie-name "=" k)}})]
    (is (nil? (store/read-session s k)))
    (is (re-find #"(?i)max-age=0" (cookie-of response)))
    (is (str/starts-with? (cookie-of response) (str session/cookie-name "=" k)) "the same cookie, told to go")))

(deftest the-cookie-of-a-session-that-is-not-there-is-stale-and-one-that-never-was-is-not
  (let [seen (atom nil)
        [s _] (fresh)
        handler (session/wrap-session (fn [request] (reset! seen request) {:status 200 :body ""}) s {})
        cookie-header #(str session/cookie-name "=" %)]
    (testing "a cookie that leads nowhere"
      (handler {:request-method :get :uri "/" :headers {"cookie" (cookie-header "gone")}})
      (is (true? (session/stale-cookie? @seen))))
    (testing "no cookie"
      (handler {:request-method :get :uri "/" :headers {}})
      (is (false? (session/stale-cookie? @seen))))
    (testing "another cookie, which is none of the portal's"
      (handler {:request-method :get :uri "/" :headers {"cookie" "other=1"}})
      (is (false? (session/stale-cookie? @seen))))
    (testing "a cookie of a session that is there"
      (let [k (store/write-session s nil person)]
        (handler {:request-method :get :uri "/" :headers {"cookie" (cookie-header k)}})
        (is (false? (session/stale-cookie? @seen)))))
    (testing "a cookie of a pending sign-in is a cookie of a session too"
      (let [k (store/write-session s nil pending)]
        (handler {:request-method :get :uri "/" :headers {"cookie" (cookie-header k)}})
        (is (false? (session/stale-cookie? @seen)))))))

(deftest a-session-that-recreates-itself-has-a-new-key-and-the-old-one-is-gone
  (let [[s _] (fresh)
        old (store/write-session s nil pending)
        handler (session/wrap-session (fn [_] {:status 200 :body "" :session (session/signed-in {:person {:id "u"} :tokens :t :csrf "c"})}) s {})
        response (handler {:request-method :get :uri "/" :headers {"cookie" (str session/cookie-name "=" old)}})
        new-key (second (re-find (re-pattern (str session/cookie-name "=([^;]+)")) (cookie-of response)))]
    (is (not= old new-key))
    (is (nil? (store/read-session s old)))
    (is (= {:person {:id "u"} :tokens :t :csrf "c"} (store/read-session s new-key)))
    (testing "and the marker does not stay in what is stored"
      (is (not (contains? (meta (store/read-session s new-key)) :recreate))))))

(deftest ring-itself-gives-a-session-its-key-the-way-the-store-says
  (let [[s _] (fresh)
        handler (ring-session/wrap-session (fn [request] {:status 200 :body (pr-str (:session request))}) {:store s :cookie-name session/cookie-name})]
    (is (some? (handler {:request-method :get :uri "/" :headers {}})))))

;; ---------------------------------------------------------------------------
;; What a person sees: through the whole handler

(defn- page [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "the page"})

(defn- token-of-the-session [request]
  {:status 200 :headers {"Content-Type" "text/plain"} :body (token/access-token (session/tokens-of request))})

(def ^:private test-routes
  [["/test/page" {:get {:authz :patient :handler page}}]
   ["/test/token" {:get {:authz :patient :handler token-of-the-session}}]
   ["/test/boom" {:get {:authz :patient :handler (fn [_] (throw (ex-info "something else" {:type :somebody-elses :k 1})))}}]])

(defn- events-of [response id]
  (filter #(= id (:id %)) (:signals response)))

(defn- signed-in-browser [web]
  (let [b (h/browser web)]
    (h/sign-in! b)
    b))

(defn- move! [web ms]
  (clock/advance! (:clock web) ms))

(deftest a-session-that-is-used-goes-on-and-one-that-is-not-ends
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)]
        (is (= 200 (:status (h/visit! b :get "/test/page"))))
        (move! web (* 29 minute))
        (is (= 200 (:status (h/visit! b :get "/test/page"))) "29 minutes later")
        (move! web (* 29 minute))
        (is (= 200 (:status (h/visit! b :get "/test/page"))) "and 29 minutes after that: a request keeps it")
        (move! web (* 31 minute))
        (let [r (h/visit! b :get "/test/page" :query "period=30")]
          (is (= 401 (:status r)))
          (is (str/includes? (:text r) "Your session has ended"))
          (is (str/includes? (:text r) "href=\"/sign-in?return-to=%2Ftest%2Fpage%3Fperiod%3D30\"")
              "the button signs in and comes back to the page that was asked for")
          (is (nil? (h/cookie b session/cookie-name)) "the cookie is dropped")
          (is (= {:sessions 0 :pending 0} (session/counts (:sessions web)))))
        (testing "and once the page has said so, the next request is just a visitor's"
          (let [r (h/visit! b :get "/test/page")]
            (is (= 303 (:status r)))
            (is (= "/sign-in?return-to=%2Ftest%2Fpage" (header r "Location")))))))))

(deftest a-session-ends-ten-hours-after-the-sign-in-however-busy-it-is
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)]
        (dotimes [_ 23]
          (move! web (* 25 minute))
          (is (= 200 (:status (h/visit! b :get "/test/page")))))
        (move! web (dec (* 25 minute)))
        (is (= 200 (:status (h/visit! b :get "/test/page"))))
        (move! web 1)
        (is (= 401 (:status (h/visit! b :get "/test/page"))))))))

(deftest a-cookie-of-a-session-the-portal-does-not-have-says-the-session-has-ended
  (with-web {:routes test-routes}
    (fn [web]
      (testing "after a restart, say: the sessions were in memory"
        (let [b (signed-in-browser web)]
          (store/delete-session (:sessions web) (h/cookie b session/cookie-name))
          (let [r (h/visit! b :get "/test/page")]
            (is (= 401 (:status r)))
            (is (str/includes? (:text r) "Your session has ended"))
            (is (str/includes? (:text r) "Sign in again"))
            (is (some #(re-find #"(?i)max-age=0" %) (h/set-cookies r))))))
      (testing "a visitor who has no cookie is sent to sign in, without a word"
        (let [r (h/call web :get "/test/page")]
          (is (= 303 (:status r)))
          (is (empty? (h/set-cookies r)))))
      (testing "a cookie that was never one of the portal's is stale all the same"
        (let [r (h/call web :get "/test/page" :headers {"cookie" (str session/cookie-name "=made-up")})]
          (is (= 401 (:status r)))
          (is (empty? (events-of r :pulse.web/session-ended)) "it is not a session that ended: there was none"))))))

(deftest htmx-is-sent-to-sign-in-and-not-given-the-page-that-says-the-session-ended
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)]
        (move! web (* 31 minute))
        (let [r (h/visit! b :get "/test/page" :headers {"hx-request" "true" "hx-current-url" "http://localhost:3300/test/page?period=30"})]
          (is (= 401 (:status r)))
          (is (= "/sign-in?return-to=%2Ftest%2Fpage%3Fperiod%3D30" (header r "HX-Redirect")))
          (is (= "" (:text r)))
          (is (some #(re-find #"(?i)max-age=0" %) (h/set-cookies r)) "the stale cookie is dropped here too"))))))

(deftest signing-in-again-gives-a-new-session-and-the-old-cookie-does-not-bring-the-old-one-back
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)
            old (h/cookie b session/cookie-name)]
        (move! web (* 31 minute))
        (h/sign-in! b)
        (is (not= old (h/cookie b session/cookie-name)))
        (is (= 200 (:status (h/visit! b :get "/test/page"))))))))

;; ---------------------------------------------------------------------------
;; The tokens of a session

(defn- providers-sessions-end! [web]
  (doseq [sid (keys (oidc-fake/sessions (:idp web)))]
    (oidc-fake/end-session! (:idp web) sid)))

(deftest a-token-that-cannot-be-renewed-because-the-provider-refuses-ends-the-session
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)]
        (testing "the token is good for five minutes, and a person keeps working while it is"
          (providers-sessions-end! web)
          (move! web (* 250 1000))
          (is (= 200 (:status (h/visit! b :get "/test/token"))) "near its end, the renewal is refused, and the caller has the token he has"))
        (testing "when it has expired there is none to be had, and the session is over"
          (move! web (* 51 1000))
          (let [r (h/visit! b :get "/test/token" :query "x=1")]
            (is (= 401 (:status r)))
            (is (str/includes? (:text r) "Your session has ended"))
            (is (str/includes? (:text r) "href=\"/sign-in?return-to=%2Ftest%2Ftoken%3Fx%3D1\""))
            (is (= {:sessions 0 :pending 0} (session/counts (:sessions web))))
            (is (nil? (h/cookie b session/cookie-name)) "and the cookie goes")
            (let [ended (first (events-of r :pulse.web/session-ended))]
              (is (= :info (:level ended)))
              (is (= {:reason :refresh-rejected} (:data ended))))))))))

(deftest a-refused-renewal-ends-the-session-the-same-while-nobody-asks-again
  ;; the second request finds the keeper backing off from the refusal, which says the same
  (with-web {:routes test-routes}
    (fn [web]
      (let [one (signed-in-browser web)
            two (h/browser web)]
        (swap! (:cookies two) assoc session/cookie-name (h/cookie one session/cookie-name))
        (providers-sessions-end! web)
        (move! web (* 301 1000))
        (is (= 401 (:status (h/visit! one :get "/test/token"))))
        (testing "a second tab with the same cookie: the session is gone already"
          (is (= 401 (:status (h/visit! two :get "/test/token")))))))))

(deftest htmx-whose-session-ended-at-the-provider-is-sent-to-sign-in
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)]
        (providers-sessions-end! web)
        (move! web (* 301 1000))
        (let [r (h/visit! b :get "/test/token" :headers {"hx-request" "true" "hx-current-url" "http://localhost:3300/test/token"})]
          (is (= 401 (:status r)))
          (is (= "/sign-in?return-to=%2Ftest%2Ftoken" (header r "HX-Redirect")))
          (is (= {:sessions 0 :pending 0} (session/counts (:sessions web)))))))))

(deftest a-provider-that-cannot-be-reached-is-a-page-and-the-session-stays
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)
            cookie (h/cookie b session/cookie-name)]
        (idp/serve-token! (:idp web) {:status 503 :body "<html>down</html>"})
        (move! web (* 301 1000))
        (let [r (h/visit! b :get "/test/token")]
          (is (= 503 (:status r)))
          (is (str/includes? (:text r) "Sign-in is not available right now"))
          (is (not (str/includes? (:text r) "down")))
          (is (= {:sessions 1 :pending 0} (session/counts (:sessions web))) "the next try may work")
          (is (= cookie (h/cookie b session/cookie-name)))
          (is (empty? (events-of r :pulse.web/session-ended))))
        (testing "and when the provider is back, the person goes on where they were"
          (idp/serve-token! (:idp web) {:status 200})
          (move! web 5000)
          (let [r (h/visit! b :get "/test/token")]
            (is (= 200 (:status r)))
            (is (string? (:text r)))))))))

(deftest a-provider-that-is-too-slow-is-the-same-and-does-not-hold-the-person-for-long
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)]
        (idp/serve-token! (:idp web) {:delay-ms 2600})
        (move! web (* 301 1000))
        (let [started (System/nanoTime)
              r (h/visit! b :get "/test/token")]
          (is (= 503 (:status r)))
          (is (< (/ (- (System/nanoTime) started) 1e6) 2500))
          (is (= {:sessions 1 :pending 0} (session/counts (:sessions web)))))))))

(deftest a-failure-that-is-not-a-token-is-not-taken-for-one
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)
            r (h/visit! b :get "/test/boom")]
        (is (= 500 (:status r)))
        (is (str/includes? (:text r) "Something went wrong on our side"))
        (is (= {:sessions 1 :pending 0} (session/counts (:sessions web))) "and the session is not touched by it")))))

(deftest requests-that-meet-a-token-near-its-end-make-one-renewal
  ;; twenty requests of the same person: one asks the provider, the others use the token they have
  (with-web {:routes test-routes}
    (fn [web]
      (let [b (signed-in-browser web)
            cookie (str session/cookie-name "=" (h/cookie b session/cookie-name))
            before (count (oidc-fake/grants (:idp web) "refresh_token"))]
        (idp/serve-token! (:idp web) {:delay-ms 300})
        (move! web (* 250 1000))
        (let [n 20
              go (CountDownLatch. 1)
              futures (doall (for [_ (range n)]
                               (future (.await go) (h/call web :get "/test/token" :headers {"cookie" cookie}))))]
          (.countDown go)
          (let [responses (mapv deref futures)]
            (is (every? #(= 200 (:status %)) responses))
            (is (= 1 (- (count (oidc-fake/grants (:idp web) "refresh_token")) before)) "one renewal")
            (is (<= 1 (count (distinct (map :text responses))) 2)
                "the caller that renewed got the new token, and the rest the one they had")))
        (testing "and the next request has the new token, with nothing to renew"
          (idp/serve-token! (:idp web) {:delay-ms 0})
          (is (= 200 (:status (h/visit! b :get "/test/token"))))
          (is (= 1 (- (count (oidc-fake/grants (:idp web) "refresh_token")) before))))))))
