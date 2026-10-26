(ns pulse.spa.http
  "The pure part of the calls the workspace makes to the api (ADR-0029, point 4): where a request goes, with every part of its
  address encoded, and what an answer is for the page. The effect that sends the request and reads the answer is
  `pulse.spa.fetch`, which only the browser can run; everything that can be decided without a browser is here, so that the JVM and
  Node run the same tests over it and agree.

  An answer is one of three things: a success, a *refusal* that the page can show (the api said no and why, in its own words:
  400, 409 and 422) and a *failure* of one kind that the code decides on, and that the person is told of in words."
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt :as crypt])))

(def failure-kinds
  "What can go wrong with a call, besides a refusal: the api could not be reached (`:unreachable`, which is also what a browser
  says of a call that CORS or the policy blocked) or was too slow (`:timeout`), failed (`:server`, any 5xx), did not accept the
  sign-in (`:unauthorized`), said the person may not (`:forbidden`) or that there is no such thing (`:not-found`), the call was
  stopped (`:aborted`), or the answer is nothing that the workspace knows how to read (`:unexpected`: a status that is no success
  and no refusal and none of these, or a success that is not JSON)."
  #{:unreachable :timeout :server :unauthorized :forbidden :not-found :aborted :unexpected})

(def refusal-statuses
  "What the api answers when it refuses a request and says why: 400 for a shape that is wrong, 409 for a status that does not
  allow the action and 422 for a value that cannot be right. The page shows its words beside the fields."
  #{400 409 422})

(def not-json
  "What a body is, when there was one and it was not JSON."
  ::not-json)

;; ---------------------------------------------------------------------------
;; Where a request goes

(def ^:private hex "0123456789ABCDEF")

(defn- utf8-bytes
  "The bytes of `s` in UTF-8, as numbers from 0 to 255."
  [s]
  #?(:clj (map #(bit-and (long %) 0xff) (.getBytes ^String s "UTF-8"))
     :cljs (array-seq (crypt/stringToUtf8ByteArray s))))

(defn- unreserved?
  "Whether the byte `b` is a letter, a digit or one of `-._~`, which an address may hold as they are."
  [b]
  (or (<= 0x41 b 0x5a) (<= 0x61 b 0x7a) (<= 0x30 b 0x39) (= b 0x2d) (= b 0x2e) (= b 0x5f) (= b 0x7e)))

(defn encode
  "`x`, a text or a number, for a part of an address: every byte of its UTF-8 that is not an unreserved character is written
  `%XX`, so a plus is `%2B` (an offset such as +02:00 must not become a space), a space is `%20` and a slash `%2F` (an id with
  one stays one segment). It is written by hand from the bytes so that the JVM and the browser write the same text."
  [x]
  (when-not (or (string? x) (number? x))
    (throw (ex-info "Only a text or a number can be put in an address" {:given x})))
  (apply str (for [b (utf8-bytes (str x))]
               (if (unreserved? b)
                 (char b)
                 (str "%" (nth hex (bit-shift-right b 4)) (nth hex (bit-and b 15)))))))

(defn path
  "The path of a request from its `segments`, each encoded: `(path [\"api\" \"patients\" id \"summary\"])`. It is never a text
  written by hand with an id in it. A segment is never empty (`//` is not the path that was meant) and never `.` or `..`: a
  browser takes those out of an address, whatever way they are written (`%2e%2e` too), so an id that is one would be a request
  to another place."
  [segments]
  (when-not (and (sequential? segments) (seq segments))
    (throw (ex-info "A path is a sequence of segments, and has at least one" {:given segments})))
  (when (some #{"" "." ".."} segments)
    (throw (ex-info "A segment of a path is never empty, nor . nor .." {:given segments})))
  (str "/" (str/join "/" (map encode segments))))

(defn- query-value [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (boolean? v) (str v)
    (integer? v) (str v)
    :else (throw (ex-info "A value of a query is a text, a keyword, a boolean or a whole number" {:given v}))))

(defn query-string
  "The query of a request from a map, or nil when there is nothing to say: a value that is nil leaves its parameter out and a
  sequence repeats it. The parameters come in the order of their names, so the same request is the same address."
  [params]
  (let [pairs (for [[k v] (sort-by (comp name key) params)
                    v (if (sequential? v) v [v])
                    :when (some? v)]
                (str (encode (name k)) "=" (encode (query-value v))))]
    (when (seq pairs)
      (str/join "&" pairs))))

(defn url
  "The address of a request: `base-url` (an origin, with or without a slash at its end), the `segments` of its path and its
  `query` (a map, or nil)."
  [base-url segments query]
  (str (str/replace base-url #"/+$" "") (path segments) (some->> (query-string query) (str "?"))))

(defn request-id
  "The id of a request, which the api puts in its log and gives back in `X-Request-Id`: a UUID is 36 plain characters, and the
  api accepts a client's id when it is short and plain (`[A-Za-z0-9._-]{1,64}`)."
  []
  (str (random-uuid)))

;; ---------------------------------------------------------------------------
;; What an answer is

(defn outcome
  "What the answer to a request is, from its HTTP `status` and its `body` already read as data (nil when it had none, `not-json`
  when it was not JSON):

    `{:result :ok :status :body}` for a success (any 2xx; a success that is not JSON is `:unexpected`),
    `{:result :refused :status :problem}` for a refusal, with the problem the api gave (a map) or nil,
    `{:result :failed :kind :status}` for the rest, the kind being one of `failure-kinds`."
  [status body]
  (cond
    (and (integer? status) (<= 200 status 299)) (if (= not-json body)
                                                  {:result :failed :kind :unexpected :status status}
                                                  {:result :ok :status status :body body})
    (contains? refusal-statuses status) {:result :refused :status status :problem (when (map? body) body)}
    (= 401 status) {:result :failed :kind :unauthorized :status status}
    (= 403 status) {:result :failed :kind :forbidden :status status}
    (= 404 status) {:result :failed :kind :not-found :status status}
    (and (integer? status) (<= 500 status 599)) {:result :failed :kind :server :status status}
    :else {:result :failed :kind :unexpected :status status}))
