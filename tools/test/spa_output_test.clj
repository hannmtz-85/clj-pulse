(ns spa-output-test
  "What the build of the clinical workspace reads of its output and its surroundings (ADR-0029, point 9): a build with any warning
  is not a release, and a shell without Windows PowerShell on its PATH gets it."
  (:require [clojure.test :refer [deftest is testing]]
            [spa-output :as out]))

(def ^:private summary "[:app] Build completed. (131 files, 130 compiled, %s warnings, 36.90s)")

(deftest the-warnings-are-those-of-the-summary-of-the-build
  (is (= 0 (out/warnings-in (format summary 0))))
  (is (= 1 (out/warnings-in (format summary 1))))
  (is (= 12 (out/warnings-in (format summary 12))))
  (testing "a build of one warning says so in the singular too"
    (is (= 1 (out/warnings-in "[:app] Build completed. (131 files, 130 compiled, 1 warning, 36.90s)"))))
  (testing "among the rest of the output, with the warnings themselves printed"
    (is (= 1 (out/warnings-in (str "shadow-cljs - config: x\n[:app] Compiling ...\n" (format summary 1)
                                   "\n\n------ WARNING #1 - :infer-warning -----\n File: x.cljs:54:23\n"))))))

(deftest a-build-that-does-not-say-how-it-went-has-no-count-and-so-does-not-pass
  (is (nil? (out/warnings-in nil)))
  (is (nil? (out/warnings-in "")))
  (is (nil? (out/warnings-in "[:app] Build failure:\nThe required namespace \"x\" is not available")))
  (is (nil? (out/warnings-in "Build completed.")))
  (is (nil? (out/warnings-in "[:app] Build completed. (131 files, 130 compiled, warnings, 36.90s)"))))

(deftest the-last-summary-is-the-one-that-counts
  (is (= 0 (out/warnings-in (str (format summary 3) "\n" (format summary 0)))))
  (is (= 3 (out/warnings-in (str (format summary 0) "\n" (format summary 3))))))

(deftest powershell-is-put-on-the-path-of-a-windows-that-lacks-it
  (let [path "C:\\Windows\\System32;C:\\Tools"]
    (is (= {"PATH" (str out/windows-powershell ";" path)}
           (out/environment {:windows? true :powershell-exists? true :path path})))
    (testing "and left alone when it is there, in any case"
      (is (= {} (out/environment {:windows? true :powershell-exists? true :path (str "C:\\X;" out/windows-powershell ";C:\\Y")})))
      (is (= {} (out/environment {:windows? true :powershell-exists? true :path (.toLowerCase (str "C:\\X;" out/windows-powershell))}))))
    (testing "and not on another system, or where it is not installed"
      (is (= {} (out/environment {:windows? false :powershell-exists? true :path "/usr/bin"})))
      (is (= {} (out/environment {:windows? true :powershell-exists? false :path path}))))
    (testing "a PATH that is not there is an empty one"
      (is (= {"PATH" (str out/windows-powershell ";")} (out/environment {:windows? true :powershell-exists? true :path nil}))))))

(deftest powershell-is-where-windows-keeps-it
  (is (= "C:\\Windows\\System32\\WindowsPowerShell\\v1.0" out/windows-powershell)))
