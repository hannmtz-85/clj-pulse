(ns pulse.spa.route-test
  "Where the workspace is, as far as what it does first depends on it (ADR-0029). These run on the JVM and on Node."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.route :as route]))

(deftest the-page-of-someone-who-signed-out-is-one-path-with-or-without-a-slash-at-its-end
  (is (= "/signed-out" route/signed-out-path))
  (is (true? (route/signed-out? "/signed-out")))
  (is (true? (route/signed-out? "/signed-out/"))))

(deftest every-other-path-asks-for-the-sign-in
  (doseq [pathname ["/" "" nil "/signed-out/x" "/signed-out//" "/signed-outx" "/x/signed-out" "/Signed-Out" "/SIGNED-OUT" "signed-out"
                    "/signed-out.html" "/signed_out" "/signed-out%2F" " /signed-out" "/signed-out "
                    "/patients/pt-ana" "/alerts" "/jobs"]]
    (testing (pr-str pathname)
      (is (false? (route/signed-out? pathname))))))
