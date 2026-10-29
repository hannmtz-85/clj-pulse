(ns pulse.view.parity-jvm-test
  "The table that holds the browser to the JVM is committed, and it is what the generator makes (ADR-0029, point 2): a change in the
  way `pulse.view.format` writes a time changes the table, and the browser must pass the new one."
  (:require [clojure.test :refer [deftest is]]
            [pulse.view.parity-generator :as generator]
            [pulse.view.parity-table :as parity]))

(deftest the-committed-table-is-what-the-generator-makes
  (let [made (generator/table)]
    (doseq [section (keys made)]
      (is (= (get made section) (get parity/table section))
          (str "the section " section " is stale: write it again with `pulse.view.parity-generator/write!` (see its docstring)")))
    (is (= (set (keys made)) (set (keys parity/table))))))

(deftest the-source-of-the-table-is-what-the-generator-writes
  (is (= (generator/text) (slurp "libs/view/test/pulse/view/parity_table.cljc"))
      "the file is the text of the generator, so that no one edits it by hand"))
