(ns spa-requests-test
  "The table of requests that the two servers of the workspace's release must answer alike (ADR-0030, point 4), and the check of an
  answer against a row of it. The Ring server is held to the table by `pulse.spa.nginx-test` and the container by `bb smoke`; what is
  tested here is that the table says what it should and that the check finds what is wrong with an answer."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spa-requests :as r]))

(def ^:private script "main.77BB12C92D2AE8EA.js")

(def ^:private context
  {:headers {"Content-Security-Policy" "default-src 'self'" "X-Frame-Options" "DENY"}
   :page "<!DOCTYPE html><title>Pulse</title>"})

(defn- row [method path] (first (filter #(and (= method (:method %)) (= path (:path %))) (r/requests script))))

(defn- problem-body [status] (json/write-str {"type" "about:blank" "title" (get r/titles status) "status" status "detail" "x"}))

(defn- answer
  "An answer that is what `row` expects, with `changes` made to it."
  [{:keys [status type cache allow body]} & {:as changes}]
  (merge {:status status
          :headers (cond-> {"Content-Type" type "Cache-Control" cache "X-Request-Id" "abc"
                            "Content-Security-Policy" "default-src 'self'" "X-Frame-Options" "DENY"}
                     allow (assoc "Allow" allow))
          :body (case body
                  :page (:page context)
                  :healthz "{\"status\":\"up\"}"
                  :problem (problem-body status)
                  "")}
         changes))

;; ---------------------------------------------------------------------------
;; The table

(deftest every-row-says-what-it-expects
  (doseq [{:keys [method path status type cache] :as row} r/table]
    (testing (str method " " path)
      (is (contains? #{:get :head :post :put :patch :delete :options} method))
      (is (str/starts-with? path "/"))
      (is (contains? #{200 404 405} status) "the table has the answers of a static site and no others")
      (is (and (string? type) (string? cache)))
      (when (= 405 status) (is (= "GET, HEAD" (:allow row))))
      (when (= 404 status) (is (or (= type r/problem-type) (= :head method))))
      (when (= :problem (:body row)) (is (= r/problem-type type))))))

(deftest the-rows-are-different-requests
  (let [keys (map (juxt :method :path) r/table)]
    (is (= (count keys) (count (distinct keys))))))

(deftest the-table-has-what-the-two-servers-could-differ-in
  (let [paths (set (map :path r/table))]
    (is (every? paths ["/" "/patients/pt-ana" "/pulse.css" "/js/{script}" "/missing.js" "/healthz"])
        "the page, a route with an id, a file, the script, a file that is not there and the health check")
    (is (some #(= :post (:method %)) r/table) "a method that is not allowed")
    (is (some #(= :head (:method %)) r/table))
    (is (some #(= r/immutable (:cache %)) r/table) "a file that is kept for ever")
    (is (some #(str/includes? (:path %) "%") r/table) "a path with a percent-encoding in it")
    (is (some #(str/includes? (:path %) "//") r/table) "an empty segment")))

(deftest the-name-of-the-script-is-put-where-it-goes
  (let [rows (r/requests script)]
    (is (= (count r/table) (count rows)))
    (is (not-any? #(str/includes? (:path %) "{script}") rows))
    (is (some #(= (str "/js/" script) (:path %)) rows))
    (is (some #(= (str "/js/" script "/") (:path %)) rows) "and a slash after it, which is no file")))

;; ---------------------------------------------------------------------------
;; The check

(deftest an-answer-that-is-what-the-row-expects-has-no-problems
  (doseq [row (r/requests script)]
    (is (= [] (r/problems row (answer row) context)) (str (:method row) " " (:path row)))))

(deftest the-status-the-type-and-how-long-it-is-kept-are-checked
  (let [page (row :get "/")]
    (is (= ["the status is 404 and should be 200"] (r/problems page (answer page :status 404) context)))
    (is (= ["the Content-Type is \"text/plain\" and should be \"text/html; charset=utf-8\""]
           (r/problems page (assoc-in (answer page) [:headers "Content-Type"] "text/plain") context)))
    (is (= ["the Cache-Control is \"no-store\" and should be \"no-cache\""]
           (r/problems page (assoc-in (answer page) [:headers "Cache-Control"] "no-store") context)))
    (is (= ["the Cache-Control is nil and should be \"no-cache\""]
           (r/problems page (update (answer page) :headers dissoc "Cache-Control") context)))))

(deftest what-a-405-allows-is-checked-and-nothing-else-says-it
  (let [refused (row :post "/")
        page (row :get "/")]
    (is (= [] (r/problems refused (answer refused) context)))
    (is (= ["the Allow is nil and should be \"GET, HEAD\""] (r/problems refused (update (answer refused) :headers dissoc "Allow") context)))
    (is (= ["the Allow is \"GET\" and should be \"GET, HEAD\""] (r/problems refused (assoc-in (answer refused) [:headers "Allow"] "GET") context)))
    (is (= ["the Allow is \"GET, HEAD\" and should be nil"] (r/problems page (assoc-in (answer page) [:headers "Allow"] "GET, HEAD") context))
        "an Allow where nothing is refused is a difference as well")))

(deftest every-answer-carries-the-headers-of-the-policy-and-an-id
  (let [page (row :get "/")]
    (is (= ["the Content-Security-Policy is nil and should be \"default-src 'self'\""]
           (r/problems page (update (answer page) :headers dissoc "Content-Security-Policy") context)))
    (is (= ["the X-Frame-Options is \"SAMEORIGIN\" and should be \"DENY\""]
           (r/problems page (assoc-in (answer page) [:headers "X-Frame-Options"] "SAMEORIGIN") context)))
    (is (= ["there is no X-Request-Id"] (r/problems page (update (answer page) :headers dissoc "X-Request-Id") context)))
    (is (= ["there is no X-Request-Id"] (r/problems page (assoc-in (answer page) [:headers "X-Request-Id"] " ") context)))
    (testing "an error carries them as well"
      (let [missing (row :get "/missing.js")]
        (is (= 1 (count (r/problems missing (update (answer missing) :headers dissoc "X-Frame-Options") context))))))))

(deftest header-names-are-read-in-any-case-and-a-header-sent-twice-is-one-list
  (let [page (row :get "/")
        lower {:status 200
               :headers {"content-type" "text/html; charset=utf-8" "cache-control" "no-cache" "x-request-id" "abc"
                         "content-security-policy" "default-src 'self'" "x-frame-options" "DENY"}
               :body (:page context)}]
    (is (= [] (r/problems page lower context)) "as the client of Babashka and the JDK give them")
    (is (= [] (r/problems page (assoc-in lower [:headers :content-type] "text/html; charset=utf-8") context)) "a keyword is a name too")
    (is (= [] (r/problems page (assoc-in lower [:headers "cache-control"] ["no-cache"]) context)))
    (is (= ["the Cache-Control is \"no-cache, no-store\" and should be \"no-cache\""]
           (r/problems page (assoc-in lower [:headers "cache-control"] ["no-cache" "no-store"]) context)))))

(deftest the-page-the-health-check-and-a-problem-are-read-in-the-body
  (let [page (row :get "/")
        healthz (row :get "/healthz")
        missing (row :get "/missing.js")]
    (is (= ["the body is not the page"] (r/problems page (answer page :body "<html>") context)))
    (is (= ["the body is not the page"] (r/problems page (answer page :body nil) context)))
    (is (= [] (r/problems healthz (answer healthz) context)))
    (is (= ["the body is \"{\\\"status\\\":\\\"down\\\"}\" and should be {\"status\":\"up\"}"]
           (r/problems healthz (answer healthz :body "{\"status\":\"down\"}") context)))
    (is (= 1 (count (r/problems healthz (answer healthz :body "ok") context))))
    (is (= [] (r/problems missing (answer missing) context)))
    (is (= ["the body is not a problem: \"Not found\""] (r/problems missing (answer missing :body "Not found") context)))
    (is (= ["the status of the problem is 500"]
           (r/problems missing (answer missing :body (json/write-str {"type" "about:blank" "title" "Not Found" "status" 500})) context)))
    (is (= ["the type of the problem is \"x\""]
           (r/problems missing (answer missing :body (json/write-str {"type" "x" "title" "Not Found" "status" 404})) context)))
    (is (= ["the title of the problem is \"Missing\""]
           (r/problems missing (answer missing :body (json/write-str {"type" "about:blank" "title" "Missing" "status" 404})) context)))
    (is (= ["the body is not a problem: \"[1]\""] (r/problems missing (answer missing :body "[1]") context)))))

(deftest a-head-has-no-body-to-look-at
  (doseq [path ["/" "/pulse.css" "/missing.js"]
          :let [head (row :head path)]]
    (is (= [] (r/problems head (assoc (answer head) :body nil) context)) path)
    (is (= [] (r/problems head (assoc (answer head) :body "anything") context)) path)))

(deftest every-difference-is-reported-not-only-the-first
  (let [page (row :get "/")
        bad (-> (answer page :status 500 :body "x")
                (update :headers dissoc "X-Request-Id" "X-Frame-Options"))]
    (is (= 4 (count (r/problems page bad context))) "the status, the body, the id and a header of the policy")))
