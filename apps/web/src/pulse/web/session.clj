(ns pulse.web.session
  "The portal's sessions (ADR-0028): on the server, in memory, behind Ring's own `SessionStore` protocol so that another store
  can replace this one. The browser has one opaque cookie and nothing else; the tokens never leave the server.

  There are two kinds of entry in the store, told apart by what they hold:

    a signed-in session  has the `:person`, the `:tokens` (the keeper of the person's tokens), the id token and the CSRF
                         token. It ends after 30 minutes without a request (the idle limit of the realm's SSO session) or
                         10 hours after the sign-in (its maximum), whichever comes first
    a pending sign-in    has the `:sign-in` that a browser began and has not finished: the `state`, the PKCE verifier, the
                         `nonce` and the place to go back to. It ends 10 minutes after it began

  What Ring's own memory store lacks, this one has: entries that expire, and limits. A browser that only asks for the sign-in
  makes pending sign-ins, of which there are at most 1 000: when they are full the oldest pending one goes, and never a
  signed-in session, so an anonymous visitor cannot fill the store or push a person out. Signed-in sessions have a limit too
  (10 000), and the one least recently used goes when it is reached. The clock is a parameter, which is how the tests move time.

  A store never makes a session under a key the browser chose: a write for a key it does not have gives a new one."
  (:require [pulse.web.values :as values]
            [ring.middleware.session :as ring-session]
            [ring.middleware.session.store :as store])
  (:import (java.time Clock)))

(def defaults
  "The limits of a store, in milliseconds and entries."
  {:idle-ms (* 30 60 1000)
   :absolute-ms (* 10 60 60 1000)
   :pending-ms (* 10 60 1000)
   :max-sessions 10000
   :max-pending 1000})

(def cookie-name "pulse-session")

;; ---------------------------------------------------------------------------
;; The kinds of entry

(defn signed-in?
  "Whether `data`, what a session holds, is a signed-in person's."
  [data]
  (some? (:person data)))

(defn- expired?
  "Whether the entry has ended at `now` (milliseconds): a signed-in session by its idle or its absolute limit, a pending
  sign-in by its own."
  [{:keys [data created touched]} now {:keys [idle-ms absolute-ms pending-ms]}]
  (if (signed-in? data)
    (or (>= (- now touched) idle-ms) (>= (- now created) absolute-ms))
    (>= (- now created) pending-ms)))

(defn- rewrite?
  "Whether a write of `data` under `key` changes the entry that is there, and is not a new entry: there is a live entry under
  the key, of the same kind. A write that would turn a pending sign-in into a session, or the other way, is a new entry, which
  makes room as any other does, so that the limits of each kind hold whatever is written."
  [entries key data now limits]
  (let [entry (get entries key)]
    (boolean (and entry
                  (not (expired? entry now limits))
                  (= (signed-in? data) (signed-in? (:data entry)))))))

(defn- live-key
  "`key` when a write of `data` under it changes an entry (see `rewrite?`), otherwise `fresh`: the key the write ends up
  under."
  [entries key fresh data now limits]
  (if (rewrite? entries key data now limits) key fresh))

(defn- without-expired [entries now limits]
  (into {} (remove (fn [[_ entry]] (expired? entry now limits))) entries))

(defn- make-room
  "`entries` with room for one more entry of the kind of `data`: when its kind is full, the oldest pending sign-in (by when
  it began) or the signed-in session least recently used goes."
  [entries data {:keys [max-sessions max-pending]}]
  (let [signed? (signed-in? data)
        limit (if signed? max-sessions max-pending)
        of-kind (filter (fn [[_ entry]] (= signed? (signed-in? (:data entry)))) entries)
        order (if signed? :touched :created)]
    (if (< (count of-kind) limit)
      entries
      (let [surplus (inc (- (count of-kind) limit))]
        (reduce dissoc entries (map key (take surplus (sort-by (fn [[_ entry]] (get entry order)) of-kind))))))))

(defn- write-entry [entries key fresh data now limits]
  (let [live (without-expired entries now limits)]
    (if (rewrite? live key data now limits)
      (assoc live key (assoc (get live key) :data data :touched now))
      (assoc (make-room (dissoc live key) data limits) fresh {:data data :created now :touched now}))))

(deftype MemoryStore [^Clock clock limits entries]
  store/SessionStore
  ;; A read is a request, and a request keeps a signed-in session from going idle
  (read-session [_ key]
    (when key
      (let [now (.millis clock)
            [_ after] (swap-vals! entries (fn [m]
                                            (let [entry (get m key)]
                                              (cond
                                                (nil? entry) m
                                                (expired? entry now limits) (dissoc m key)
                                                :else (assoc-in m [key :touched] now)))))]
        (:data (get after key)))))
  (write-session [_ key data]
    (let [now (.millis clock)
          fresh (values/random-value 32)
          [before _] (swap-vals! entries write-entry key fresh data now limits)]
      (live-key before key fresh data now limits)))
  (delete-session [_ key]
    (when key (swap! entries dissoc key))
    nil))

;; A store holds people's tokens: printing it must say how many, and not what
(defmethod print-method MemoryStore [^MemoryStore store ^java.io.Writer w]
  (.write w (str "#session-store[" (count @(.-entries store)) " entries]")))

(defn memory-store
  "A store in memory. Options are those of `defaults`, and `:clock`, a `java.time.Clock` (the system clock)."
  ([] (memory-store {}))
  ([{:keys [clock] :as options}]
   (->MemoryStore (or clock (Clock/systemUTC)) (merge defaults (dissoc options :clock)) (atom {}))))

(defn- pending-at?
  "Whether the entry is a pending sign-in that has not ended."
  [entry now limits]
  (and entry
       (not (signed-in? (:data entry)))
       (not (expired? entry now limits))))

(defn consume!
  "Takes the pending sign-in under `key` out of `store`, atomically, and returns what it held: nil when there is none, when it
  has ended, or when the entry is a signed-in session, which is never taken this way. Of two requests that come with the same
  key at the same time, only one gets it: that is what makes a sign-in a thing that can happen once (ADR-0028)."
  [^MemoryStore store key]
  (when key
    (let [now (.millis ^Clock (.-clock store))
          limits (.-limits store)
          [before _] (swap-vals! (.-entries store)
                                 (fn [entries]
                                   (if (pending-at? (get entries key) now limits) (dissoc entries key) entries)))
          entry (get before key)]
      (when (pending-at? entry now limits)
        (:data entry)))))

(defn counts
  "How many entries `store` holds that have not ended, as `{:sessions n :pending m}`."
  [^MemoryStore store]
  (let [now (.millis ^Clock (.-clock store))
        live (vals (without-expired @(.-entries store) now (.-limits store)))]
    {:sessions (count (filter #(signed-in? (:data %)) live))
     :pending (count (remove #(signed-in? (:data %)) live))}))

;; ---------------------------------------------------------------------------
;; What a handler does with a session

(defn pending-sign-in
  "The session that a browser begins by asking to sign in: the pending `sign-in` (`:state`, `:verifier`, `:nonce`,
  `:return-to`). It is a new entry, under a new key, whatever the browser came with."
  [sign-in]
  (with-meta {:sign-in sign-in} {:recreate true}))

(defn signed-in
  "The session of a person who has just signed in: `:person`, `:tokens` (the keeper of their tokens) and `:csrf`. It is a new
  entry under a new key, so nothing that the browser had before the sign-in survives it (session fixation)."
  [{:keys [person tokens csrf]}]
  (with-meta {:person person :tokens tokens :csrf csrf} {:recreate true}))

(defn end
  "`response` that ends the session of the request: the entry is deleted and the cookie is told to go. A browser that comes
  back with the old cookie is not signed in."
  [response]
  (assoc response :session nil :session-cookie-attrs {:max-age 0}))

(defn forget-cookie
  "`response` that tells the browser to drop its session cookie, for a cookie of a session that is not there any more (the
  session middleware has nothing to end, and so does not say it)."
  [response]
  (assoc-in response [:cookies cookie-name] {:value "" :path "/" :max-age 0}))

(defn sign-in-of
  "The pending sign-in of the request's session, or nil."
  [request]
  (get-in request [:session :sign-in]))

(defn tokens-of
  "The keeper of the signed-in person's tokens, or nil."
  [request]
  (get-in request [:session :tokens]))

(defn csrf-token-of
  "The CSRF token of the signed-in person's session, or nil."
  [request]
  (get-in request [:session :csrf]))

(defn stale-cookie?
  "Whether the browser came with the cookie of a session that is not there any more: it ended, or the portal was restarted.
  It is not the case for a browser that has no cookie."
  [request]
  (boolean (and (get-in request [:cookies cookie-name :value])
                (empty? (:session request)))))

(defn wrap-session
  "Ring's session middleware over `store`, with the cookie the portal wants: its own name, `HttpOnly`, `SameSite=Lax` (it
  has to come with the top-level redirect back from the identity provider), the whole site, no expiry (it goes with the
  browser) and `Secure` when the portal is served over https (`:secure?`)."
  [handler store {:keys [secure?]}]
  (ring-session/wrap-session handler {:store store
                                      :cookie-name cookie-name
                                      :cookie-attrs (cond-> {:http-only true :same-site :lax :path "/"}
                                                      secure? (assoc :secure true))}))
