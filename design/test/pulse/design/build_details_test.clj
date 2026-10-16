(ns pulse.design.build-details-test
  "What a mutation analysis found the tests of the build did not notice."
  (:require [clojure.test :refer [deftest is]]
            [pulse.design.build :as build]))

(deftest a-class-is-not-defined-by-a-longer-one-that-starts-with-it
  (doseq [css [".btnx{}" ".btnX{}" ".btn1{}" ".btn_x{}" ".btn-x{}" ".btn\\:x{}"]]
    (is (= ["btn"] (seq (build/unknown-classes "<b class=\"btn\"></b>" css))) css))
  (doseq [css [".btn{}" ".btn:hover{}" ".a .btn{}" ".btn.other{}" ".btn,.other{}" ".btn >.c{}"]]
    (is (empty? (build/unknown-classes "<b class=\"btn\"></b>" css)) css)))
