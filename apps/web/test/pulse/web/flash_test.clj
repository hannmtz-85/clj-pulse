(ns pulse.web.flash-test
  "What a request leaves for the next one to say (ADR-0028): a message, kept in the session of the person, that the next page tells once
  and takes away. A request that changes something answers with a redirect, and this is how the page it redirects to knows what
  happened."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.platform.test.clock :as clock]
            [pulse.web.session :as session]
            [ring.middleware.session.store :as store]))

(def ^:private person-session {:person {:id "u" :name "Ana"} :tokens :the-keeper :csrf "the-token"})

(defn- fresh [& {:as options}]
  (session/memory-store (assoc options :clock (:clock (clock/clock "2026-10-19T10:00:00Z")))))

(defn- cookie-of [key] {"cookie" (str session/cookie-name "=" key)})

(deftest a-request-leaves-a-message-for-the-next-in-the-session-and-the-next-tells-it-once
  (let [s (fresh)
        k (store/write-session s nil person-session)
        leave (session/wrap-session (fn [request] (session/with-flash {:status 303 :body ""} request {:text "saved" :n 1})) s {})
        tell (session/wrap-session (fn [request]
                                     (let [flash (session/flash-of request)]
                                       (cond-> {:status 200 :body (pr-str flash)}
                                         flash (session/clear-flash request))))
                                   s {})
        left (leave {:request-method :post :uri "/x" :headers (cookie-of k)})]
    (testing "the session is the same, with the message in it, and the browser is told nothing new"
      (is (= (assoc person-session :flash {:text "saved" :n 1}) (store/read-session s k)))
      (is (nil? (get-in left [:headers "Set-Cookie"])))
      (is (nil? (:cookies left))))
    (testing "the next request tells it, and it is gone"
      (is (= (pr-str {:text "saved" :n 1}) (:body (tell {:request-method :get :uri "/x" :headers (cookie-of k)}))))
      (is (= person-session (store/read-session s k)) "what was in the session before is still there")
      (is (= "nil" (:body (tell {:request-method :get :uri "/x" :headers (cookie-of k)}))) "and it is told once"))))

(deftest the-message-is-left-to-the-session-that-was-asking-and-to-no-other
  (let [s (fresh)
        mine (store/write-session s nil person-session)
        other (store/write-session s nil (assoc person-session :person {:id "v" :name "Noah"}))
        leave (session/wrap-session (fn [request] (session/with-flash {:status 303 :body ""} request {:text "mine"})) s {})]
    (leave {:request-method :post :uri "/x" :headers (cookie-of mine)})
    (is (= {:text "mine"} (:flash (store/read-session s mine))))
    (is (not (contains? (store/read-session s other) :flash)))))

(deftest a-message-that-is-not-there-is-nothing-and-clearing-it-changes-nothing
  (is (nil? (session/flash-of {:session {}})))
  (is (nil? (session/flash-of {})))
  (is (nil? (session/flash-of {:session {:person {:id "u"}}})))
  (is (= {:status 200 :session {:person {:id "u"}}}
         (session/clear-flash {:status 200} {:session {:person {:id "u"} :flash {:text "x"}}})))
  (is (= {:status 200 :session {:person {:id "u"}}}
         (session/clear-flash {:status 200} {:session {:person {:id "u"}}}))))

(deftest a-message-does-not-make-a-session-a-new-one-or-make-room-for-itself
  (let [s (fresh :max-sessions 2)
        k1 (store/write-session s nil person-session)
        k2 (store/write-session s nil person-session)
        leave (session/wrap-session (fn [request] (session/with-flash {:status 303 :body ""} request {:text "x"})) s {})]
    (leave {:request-method :post :uri "/x" :headers (cookie-of k1)})
    (leave {:request-method :post :uri "/x" :headers (cookie-of k2)})
    (is (= {:sessions 2 :pending 0} (session/counts s)) "it is a rewrite of what is there: no one is pushed out")
    (is (some? (store/read-session s k1)))
    (is (some? (store/read-session s k2)))))
