(ns pulse.spa.http-test
  "The pure part of the workspace's calls to the api (ADR-0029, point 4): the address of a request and what an answer is. These run
  on the JVM and on Node, and the tables are the proof that the two write the same address."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.spa.http :as http]))

(defn- code-point-text
  "The text of the Unicode code point `cp`."
  [cp]
  #?(:clj (String. (Character/toChars (int cp)))
     :cljs (.fromCodePoint js/String cp)))

(defn- decode
  "What a server reads of `s`: the percent-encoded UTF-8 text as text."
  [s]
  #?(:clj (java.net.URLDecoder/decode ^String s "UTF-8")
     :cljs (js/decodeURIComponent s)))

(defn- thrown-ex [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

;; ---------------------------------------------------------------------------
;; Encoding

(deftest every-byte-that-is-not-unreserved-is-written-in-percent
  (doseq [[text expected] [["abcXYZ019" "abcXYZ019"]
                           ["-._~" "-._~"]
                           ["" ""]
                           [" " "%20"]
                           ["+" "%2B"]
                           ["/" "%2F"]
                           ["%" "%25"]
                           [":" "%3A"]
                           ["?" "%3F"]
                           ["#" "%23"]
                           ["&" "%26"]
                           ["=" "%3D"]
                           ["'" "%27"]
                           ["(" "%28"]
                           [")" "%29"]
                           ["*" "%2A"]
                           ["!" "%21"]
                           ["\"" "%22"]
                           ["<" "%3C"]
                           [">" "%3E"]
                           ["@" "%40"]
                           ["[" "%5B"]
                           ["\\" "%5C"]
                           ["a b" "a%20b"]
                           ["a/b" "a%2Fb"]
                           [(str (char 0)) "%00"]
                           [(str (char 127)) "%7F"]
                           ["García" "Garc%C3%ADa"]
                           ["ñ" "%C3%B1"]
                           ["€" "%E2%82%AC"]
                           [(code-point-text 0x1F600) "%F0%9F%98%80"]
                           ["2026-10-13T12:00:00+02:00" "2026-10-13T12%3A00%3A00%2B02%3A00"]]]
    (is (= expected (http/encode text)) (pr-str text))))

(deftest every-ascii-character-is-itself-or-percent-and-its-two-hex-digits
  (let [digits "0123456789ABCDEF"]
    (doseq [b (range 128)
            :let [c (str (char b))
                  as-it-is? (boolean (re-matches #"[A-Za-z0-9._~-]" c))]]
      (is (= (if as-it-is? c (str "%" (nth digits (quot b 16)) (nth digits (rem b 16))))
             (http/encode c))
          (str "byte " b)))))

(deftest a-number-is-written-as-it-is
  (is (= "42" (http/encode 42)))
  (is (= "-1" (http/encode -1)))
  (is (= "0" (http/encode 0))))

(deftest only-a-text-or-a-number-can-be-put-in-an-address
  (doseq [x [nil :keyword {} [] #{} true]]
    (is (some? (thrown-ex #(http/encode x))) (pr-str x))))

(def ^:private any-code-point
  "A code point from each range of UTF-8 (one byte to four), without the surrogates, which are no text."
  (gen/one-of [(gen/choose 0 0x7f) (gen/choose 0x80 0x7ff) (gen/choose 0x800 0xd7ff) (gen/choose 0xe000 0xffff) (gen/choose 0x10000 0x10ffff)]))

(def ^:private any-text
  (gen/fmap #(apply str (map code-point-text %)) (gen/vector any-code-point 0 12)))

(defspec what-a-server-reads-is-what-was-meant 300
  (prop/for-all [text any-text]
    (= text (decode (http/encode text)))))

(defspec what-is-written-has-nothing-a-parser-could-take-for-something-else 300
  (prop/for-all [text any-text]
    (let [written (http/encode text)]
      (and (boolean (re-matches #"[A-Za-z0-9._~%-]*" written))
           (every? #(re-matches #"%[0-9A-F]{2}" %) (re-seq #"%.{0,2}" written))))))

;; ---------------------------------------------------------------------------
;; Paths, queries and addresses

(deftest a-path-is-its-segments-each-encoded
  (is (= "/api/me" (http/path ["api" "me"])))
  (is (= "/api/patients/pt-ana/summary" (http/path ["api" "patients" "pt-ana" "summary"])))
  (testing "an id with a slash or a space in it stays one segment"
    (is (= "/api/patients/a%2Fb%20c/summary" (http/path ["api" "patients" "a/b c" "summary"])))
    (is (= "/api/patients/..%2Fadmin" (http/path ["api" "patients" "../admin"])))
    (is (= "/api/patients/%252E%252E" (http/path ["api" "patients" "%2E%2E"]))
        "a percent in an id is itself, so that what is written is not read again as an address"))
  (testing "a number is a segment"
    (is (= "/api/appointments/7" (http/path ["api" "appointments" 7])))))

(deftest a-path-is-never-one-that-was-not-meant
  (doseq [segments [nil [] "api/me" ["api" ""] ["" "api"] ["api" nil] ["api" :me] 7
                    ["api" "patients" "." "summary"] ["api" "patients" ".."] [".." "api"]]]
    (is (some? (thrown-ex #(http/path segments))) (pr-str segments)))
  (testing "a name with dots in it is not a dot segment"
    (is (= "/api/patients/..." (http/path ["api" "patients" "..."])))
    (is (= "/api/patients/a..b" (http/path ["api" "patients" "a..b"])))
    (is (= "/api/patients/.hidden" (http/path ["api" "patients" ".hidden"])))))

(deftest a-query-is-its-parameters-in-the-order-of-their-names
  (is (nil? (http/query-string nil)))
  (is (nil? (http/query-string {})))
  (is (= "limit=100&sort=-start" (http/query-string {:sort "-start" :limit 100})))
  (is (= "a=1&b=2&c=3" (http/query-string {:c 3 :a 1 :b 2}))
      "the same request is the same address, whatever the order of the map")
  (testing "a value that is nil leaves its parameter out, and a sequence repeats it"
    (is (nil? (http/query-string {:status nil})))
    (is (= "limit=5" (http/query-string {:status nil :limit 5})))
    (is (= "status=booked&status=pending" (http/query-string {:status ["booked" "pending"]})))
    (is (= "status=booked" (http/query-string {:status ["booked" nil]})))
    (is (nil? (http/query-string {:status []})))
    (is (= "status=booked&status=pending" (http/query-string {:status (list "booked" "pending")}))))
  (testing "a keyword is its name, and a boolean and a whole number are themselves"
    (is (= "limit=0&measurement=heart-rate&truncated=false" (http/query-string {:measurement :heart-rate :truncated false :limit 0})))
    (is (= "limit=-1&truncated=true" (http/query-string {:truncated true :limit -1}))))
  (testing "a plus in a value is %2B, and a space is %20"
    (is (= "from=2026-10-13T12%3A00%3A00%2B02%3A00" (http/query-string {:from "2026-10-13T12:00:00+02:00"})))
    (is (= "q=Garc%C3%ADa%20L%C3%B3pez" (http/query-string {:q "García López"}))))
  (testing "the name of a parameter is encoded as well"
    (is (= "a%20b=c" (http/query-string {"a b" "c"})))))

(deftest a-query-takes-only-what-it-can-write-faithfully
  (doseq [v [{} {:a 1} 1.5 #{} (fn [])]]
    (is (some? (thrown-ex #(http/query-string {:a v}))) (str v))))

(deftest an-address-is-the-origin-the-path-and-the-query
  (is (= "http://localhost:4000/api/me" (http/url "http://localhost:4000" ["api" "me"] nil)))
  (is (= "http://localhost:4000/api/me" (http/url "http://localhost:4000/" ["api" "me"] nil)) "a slash at the end of the origin is not a second one")
  (is (= "http://localhost:4000/api/me" (http/url "http://localhost:4000//" ["api" "me"] nil)) "nor two")
  (is (= "http://localhost:4000/api/me" (http/url "http://localhost:4000" (list "api" "me") nil)) "the segments may be any sequence")
  (is (= "https://api.pulse.example/api/appointments?limit=100&sort=-start"
         (http/url "https://api.pulse.example" ["api" "appointments"] {:sort "-start" :limit 100})))
  (is (= "http://localhost:4000/api/patients/a%2Fb/observations?from=x%2By"
         (http/url "http://localhost:4000" ["api" "patients" "a/b" "observations"] {:from "x+y"}))))

(deftest the-id-of-a-request-is-one-that-the-api-takes
  (let [ids (repeatedly 50 http/request-id)]
    (is (every? #(re-matches #"[A-Za-z0-9._-]{1,64}" %) ids) "plain and short, which is what the api keeps in its log")
    (is (= 50 (count (set ids))) "and each call has its own")))

;; ---------------------------------------------------------------------------
;; What an answer is

(deftest a-success-is-any-2xx
  (doseq [status [200 201 202 204 206 299]]
    (is (= {:result :ok :status status :body {:a 1}} (http/outcome status {:a 1})) (str status)))
  (testing "with no body, or a body of any shape that is JSON"
    (is (= {:result :ok :status 204 :body nil} (http/outcome 204 nil)))
    (is (= {:result :ok :status 200 :body [1 2]} (http/outcome 200 [1 2])))
    (is (= {:result :ok :status 200 :body "text"} (http/outcome 200 "text")))
    (is (= {:result :ok :status 200 :body false} (http/outcome 200 false)))))

(deftest a-success-that-is-not-json-is-not-one-the-workspace-can-read
  (is (= {:result :failed :kind :unexpected :status 200} (http/outcome 200 http/not-json)))
  (is (= {:result :failed :kind :unexpected :status 201} (http/outcome 201 http/not-json))))

(deftest the-api-saying-no-and-why-is-a-refusal-with-its-problem
  (let [problem {:title "Unprocessable Content" :status 422 :detail "Check what you entered." :errors {:diastolic "Must be between 20 and 200 mmHg."}}]
    (doseq [status [400 409 422]]
      (testing status
        (is (= {:result :refused :status status :problem problem} (http/outcome status problem)))
        (is (= {:result :refused :status status :problem nil} (http/outcome status nil)) "with no body there is no problem to show")
        (is (= {:result :refused :status status :problem nil} (http/outcome status http/not-json)))
        (is (= {:result :refused :status status :problem nil} (http/outcome status [1 2])) "a body that is not a problem is not shown as one")
        (is (= {:result :refused :status status :problem nil} (http/outcome status "text")))))))

(deftest every-other-status-is-a-failure-of-a-kind
  (doseq [[status kind] [[401 :unauthorized] [403 :forbidden] [404 :not-found]
                         [500 :server] [501 :server] [502 :server] [503 :server] [504 :server] [599 :server]
                         [100 :unexpected] [199 :unexpected] [300 :unexpected] [302 :unexpected] [304 :unexpected]
                         [402 :unexpected] [405 :unexpected] [406 :unexpected] [408 :unexpected] [410 :unexpected]
                         [413 :unexpected] [415 :unexpected] [429 :unexpected] [499 :unexpected] [600 :unexpected]
                         [0 :unexpected] [-1 :unexpected]]]
    (testing status
      (is (= {:result :failed :kind kind :status status} (http/outcome status {:title "x"})))
      (is (= {:result :failed :kind kind :status status} (http/outcome status nil))))))

(deftest what-is-no-number-is-no-status
  (doseq [status [nil "200" :ok 200.5 [200]]]
    (is (= :unexpected (:kind (http/outcome status {}))) (pr-str status))))

(deftest the-kinds-of-failure-are-the-ones-the-adr-names-and-no-more
  (is (= #{:unreachable :timeout :server :unauthorized :forbidden :not-found :aborted :unexpected} http/failure-kinds))
  (is (= #{400 409 422} http/refusal-statuses)))

(defspec whatever-the-status-and-the-body-the-answer-is-one-of-three-things 500
  (prop/for-all [status (gen/choose -5 700)
                 body (gen/one-of [(gen/return nil) (gen/return http/not-json) (gen/return {:title "x"}) (gen/return [1]) gen/string-alphanumeric])]
    (let [{:keys [result kind]} (http/outcome status body)]
      (case result
        :ok (<= 200 status 299)
        :refused (contains? http/refusal-statuses status)
        :failed (and (contains? http/failure-kinds kind)
                     (not (contains? http/refusal-statuses status))
                     ;; a success is a failure only when what came was not JSON
                     (or (not (<= 200 status 299)) (= body http/not-json)))
        false))))
