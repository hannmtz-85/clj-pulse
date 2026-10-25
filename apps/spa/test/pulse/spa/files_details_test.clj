(ns pulse.spa.files-details-test
  "What the mutation analysis of the files found that `files-test` did not pin (Phase 8, step 3): a file that the build may leave
  beside the script, a directory that has the name of a file, a link to something outside the directory, and the name of a
  script that is not one the build made."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [pulse.spa.files :as files]
            [ring.util.codec :as codec]
            [ring.util.response :as response])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *root* nil)
(def ^:dynamic *link?* false)

(defn- write! [^java.io.File root path content]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file content)))

(defn- with-release [f]
  (let [parent (.toFile (Files/createTempDirectory "spa-files-details-" (make-array FileAttribute 0)))
        root (io/file parent "release")]
    (write! root "index.html" "<!DOCTYPE html><title>Pulse</title>")
    (write! root "js/main.77BB12C92D2AE8EA.js" "console.log(1)")
    (write! root "js/main.77BB12C92D2AE8EA.js.map" "{\"version\":3}")
    (write! root "dir.js/index.html" "the index of a directory")
    (write! root "dir.js/inner.js" "inner")
    (write! parent "outside.js" "outside the directory")
    ;; a link to a file outside the directory: it may not be possible to make one (Windows asks for a privilege that a
    ;; developer's machine may not give), and then there is nothing to test
    (let [link? (try (Files/createSymbolicLink (.toPath (io/file root "link.js")) (.toPath (io/file parent "outside.js")) (make-array FileAttribute 0))
                     true
                     (catch Exception _ false))]
      (binding [*root* (str root) *link?* link?]
        (f)))))

(use-fixtures :once with-release)

(defn- get! [uri]
  ((files/handler *root*) {:request-method :get :uri uri}))

(defn- body [response]
  (let [b (:body response)] (cond (string? b) b b (slurp b))))

(deftest a-source-map-is-not-a-kind-of-file-that-is-served
  (is (= 404 (:status (get! "/js/main.77BB12C92D2AE8EA.js.map"))))
  (is (= 200 (:status (get! "/js/main.77BB12C92D2AE8EA.js"))) "and the script beside it is"))

(deftest a-directory-with-the-name-of-a-file-is-not-served-nor-is-its-index
  (is (= 404 (:status (get! "/dir.js"))))
  (is (not= "the index of a directory" (body (get! "/dir.js"))))
  (is (= 404 (:status (get! "/dir.js/"))) "a file does not end in a slash")
  (testing "what is in it is a file"
    (is (= "inner" (body (get! "/dir.js/inner.js"))))))

(deftest a-link-to-a-file-outside-the-directory-is-not-followed
  (if *link?*
    (let [response (get! "/link.js")]
      (is (= 404 (:status response)))
      (is (not= "outside the directory" (body response))))
    (is true "this machine cannot make a symbolic link, so there is nothing to follow")))

(deftest a-name-with-another-dot-in-it-is-not-one-the-build-made
  (is (= "no-cache" (files/cache-control "main.min.0123abcd.js")))
  (is (= "no-cache" (files/cache-control "main.0123abcd.min.js")))
  (is (= "public, max-age=31536000, immutable" (files/cache-control "js/main.0123abcd.js"))))

(deftest what-is-asked-of-the-file-server-is-no-index-of-a-directory-and-no-link
  (let [original response/file-response
        asked (atom [])]
    (with-redefs [response/file-response (fn [name options] (swap! asked conj options) (original name options))]
      (get! "/index.html")
      (get! "/dir.js"))
    (is (= [{:root *root* :index-files? false :allow-symlinks? false}
            {:root *root* :index-files? false :allow-symlinks? false}]
           @asked))))

(deftest an-address-that-cannot-be-decoded-is-no-name
  (with-redefs [codec/url-decode (fn [_] (throw (ex-info "not an address" {})))]
    (doseq [uri ["/" "/anything" "/js/main.77BB12C92D2AE8EA.js"]]
      (is (= 404 (:status (get! uri))) uri)))
  (testing "and an address that decodes to nothing is no name either"
    (with-redefs [codec/url-decode (fn [_] nil)]
      (is (= 404 (:status (get! "/")))))))

(deftest a-file-that-is-not-there-says-so-in-a-problem
  (let [response (get! "/missing.js")
        problem (json/read-value (body response))]
    (is (= 404 (:status response)))
    (is (= {"title" "Not Found" "status" 404 "detail" "There is no such file." "instance" "/missing.js"}
           (select-keys problem ["title" "status" "detail" "instance"])))))
