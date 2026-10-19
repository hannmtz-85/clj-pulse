(ns pulse.web.return-to-test
  "Where a person goes back to (ADR-0028): a place in the portal, and never anything a stranger could make an address of."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.web.return-to :as return-to]))

(deftest a-path-of-the-portal-is-one
  (doseq [path ["/" "/readings" "/readings/blood-pressure" "/readings?period=30" "/appointments?a=1&b=2#ask"
                "/a-b_c.d/e~f" "/%2F" "/%2e%2e/x" "/x?next=https://other.example/"]]
    (is (return-to/path? path) path)))

(deftest anything-else-is-not
  (doseq [bad [nil "" "readings" "//evil.example" "//evil.example/x" "/\\evil.example" "/\\\\evil.example" "\\\\evil.example"
               "http://evil.example/" "https://evil.example/x" "javascript:alert(1)" "data:text/html,x" "mailto:a@b.c"
               "/a b" "/a\tb" "/a\nb" "/a\rb" "/a\u0000b" (str "/" (char 0xE9)) (str "/" (char 0x202E)) " /x" "/x " "/a\\b"
               :keyword 42 ["/x"] {:path "/x"}]]
    (is (not (return-to/path? bad)) (pr-str bad)))
  (testing "a path is not as long as a header may be"
    (is (return-to/path? (apply str "/" (repeat 1999 "a"))))
    (is (not (return-to/path? (apply str "/" (repeat 2000 "a")))))))

(deftest what-is-not-a-path-is-the-home-page
  (is (= "/" return-to/home))
  (doseq [bad [nil "" "//evil.example" "http://evil.example" "/\\evil.example"]]
    (is (= "/" (return-to/safe bad)) (pr-str bad)))
  (is (= "/readings?period=30" (return-to/safe "/readings?period=30"))))

(defspec whatever-is-given-the-place-to-go-back-to-is-a-path-of-the-portal 1000
  (prop/for-all [prefix (gen/elements ["" "/" "//" "/\\" "\\" "http:" "https://" "javascript:" "/x" "/x/" "/%2f" "///" "/./" "/../"])
                 tail (gen/one-of [gen/string gen/string-ascii gen/string-alphanumeric
                                   (gen/elements ["" "/" "\\" "evil.example" "//evil.example" "\n" "\t" " " "\u0000" "?" "#" "%2f" "@"])])]
    (let [s (str prefix tail)
          back (return-to/safe s)]
      (and (return-to/path? back)
           (str/starts-with? back "/")
           (not (str/starts-with? back "//"))
           (not (str/starts-with? back "/\\"))
           (not (str/includes? back "\\"))))))

(defspec a-path-stays-as-it-is-and-nothing-else-does 500
  (prop/for-all [s (gen/one-of [gen/string gen/string-ascii (gen/fmap #(str "/" %) gen/string-alphanumeric)])]
    (let [back (return-to/safe s)]
      (if (return-to/path? s) (= s back) (= "/" back)))))

;; ---------------------------------------------------------------------------
;; The place a request comes from

(defn- request [& {:as options}]
  (merge {:request-method :get :uri "/readings" :headers {}} options))

(deftest a-get-comes-back-to-the-page-it-asked-for-with-its-query
  (is (= "/readings" (return-to/of-request (request))))
  (is (= "/readings?period=30&measurement=heart-rate"
         (return-to/of-request (request :query-string "period=30&measurement=heart-rate"))))
  (is (= "/readings" (return-to/of-request (request :query-string ""))) "an empty query is none")
  (is (= "/readings" (return-to/of-request (request :query-string nil)))))

(deftest a-request-that-changes-something-comes-back-to-the-home-page
  ;; a POST sent again as a GET would ask a place that takes no GET for something it cannot give
  (doseq [method [:post :put :patch :delete]]
    (is (= "/" (return-to/of-request (request :request-method method :uri "/appointments/ap-1/cancel"))) (str method))))

(deftest a-request-of-htmx-comes-back-to-the-page-the-person-was-on-and-not-to-the-fragment
  (let [htmx (fn [current & {:as more}]
               (request :uri "/fragments/latest-readings"
                        :headers (merge {"hx-request" "true" "hx-current-url" current} (:headers more))))]
    (is (= "/readings?period=30" (return-to/of-request (htmx "http://localhost:3300/readings?period=30"))))
    (is (= "/" (return-to/of-request (htmx "http://localhost:3300"))) "the home page has no path")
    (is (= "/" (return-to/of-request (htmx "http://localhost:3300/"))))
    (testing "the path and the query come back as the browser wrote them, encoded, and not as they would be read"
      (is (= "/patients/a%20b" (return-to/of-request (htmx "http://localhost:3300/patients/a%20b")))
          "decoded, a space would make it no path at all")
      (is (= "/search?q=a%20b" (return-to/of-request (htmx "http://localhost:3300/search?q=a%20b"))))
      (is (= "/search?q=a%26b%2Bc" (return-to/of-request (htmx "http://localhost:3300/search?q=a%26b%2Bc")))
          "an ampersand and a plus that were part of a value stay so"))
    (testing "only the path and the query of the address count, whatever the address is"
      (is (= "/readings" (return-to/of-request (htmx "https://evil.example/readings"))))
      (is (= "/" (return-to/of-request (htmx "https://evil.example//other.example/x"))) "a second slash is not a path"))
    (testing "an address that cannot be read, or that is not an address, is the home page"
      (is (= "/" (return-to/of-request (htmx "not a url at all"))))
      (is (= "/" (return-to/of-request (htmx "http://[::1")))))
    (testing "a POST of htmx comes back to the page too: the person was on it"
      (is (= "/readings"
             (return-to/of-request (assoc (htmx "http://localhost:3300/readings") :request-method :post)))))
    (testing "the header means nothing from a request that htmx did not make"
      (is (= "/readings" (return-to/of-request (request :headers {"hx-current-url" "http://localhost:3300/elsewhere"})))))
    (testing "and htmx without an address is a request for the fragment, which has no page of its own"
      (is (= "/fragments/latest-readings"
             (return-to/of-request (request :uri "/fragments/latest-readings" :headers {"hx-request" "true"})))))))

(deftest a-request-never-sends-a-person-anywhere-but-the-portal
  (doseq [uri ["//evil.example/x" "/\\evil.example" "http://evil.example/" "evil"]]
    (is (return-to/path? (return-to/of-request (request :uri uri))) uri)
    (is (return-to/path? (return-to/of-request (request :uri uri :headers {"hx-request" "true" "hx-current-url" uri}))) uri)))
