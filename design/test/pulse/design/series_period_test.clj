(ns pulse.design.series-period-test
  "The series of the mock-ups for a period (ADR-0026): what `GET /api/patients/{id}/observations` answers with `from` and `to`, from the
  same cohort, so that a page of readings made from the mock-up's data is the page the portal makes from the api's."
  (:require [clojure.set]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [pulse.api.observations :as api-observations]
            [pulse.design.data :as data]
            [pulse.fhir.client.memory :as memory]
            [pulse.fhir.measurement :as measurement]))

(def ^:private fhir
  (delay (memory/memory-client (data/resources))))

(defn- series-of-api [id kind query]
  (:body ((api-observations/list-handler {:fhir @fhir})
          {:path-params {:id id} :parameters {:query (merge {:measurement (name kind) :sort "-date" :limit 500} query)}})))

(deftest a-series-for-a-period-is-what-the-api-answers-for-it
  (doseq [{:keys [id]} (data/patients)
          kind (keys measurement/measurements)
          period [{:from "2026-09-13T12:00:00Z"}
                  {:from "2026-10-06T12:00:00Z"}
                  {:from "2026-10-12T00:00:00Z" :to "2026-10-13T00:00:00Z"}
                  {:to "2026-10-01T00:00:00Z"}
                  {:from "2026-10-13T12:00:00Z"}]]
    (let [mine (data/series id kind (assoc period :limit 500))]
      (is (m/validate api-observations/list-schema mine) (str id " " kind " " period))
      (is (= (series-of-api id kind period) mine) (str id " " kind " " period)))))

(deftest a-series-for-a-period-and-a-limit-is-the-newest-of-the-period
  (doseq [limit [1 5 20]]
    (is (= (series-of-api "pt-ana" :blood-pressure {:from "2026-10-01T00:00:00Z" :limit limit})
           (data/series "pt-ana" :blood-pressure {:from "2026-10-01T00:00:00Z" :limit limit}))
        (str limit)))
  (let [{:keys [items truncated]} (data/series "pt-ana" :blood-pressure {:from "2026-10-01T00:00:00Z" :limit 5})]
    (is (= 5 (count items)))
    (is (true? truncated))
    (is (every? #(>= (compare (:at %) "2026-10-01T00:00:00Z") 0) items))))

(deftest the-start-of-a-period-is-in-and-the-end-is-out
  (let [at (:at (nth (:items (data/series "pt-ana" :blood-pressure 500)) 4))
        ats (fn [period] (set (map :at (:items (data/series "pt-ana" :blood-pressure (assoc period :limit 500))))))]
    (is (contains? (ats {:from at}) at) "a reading of the very moment a period begins is in it")
    (is (not (contains? (ats {:to at}) at)) "and one of the very moment it ends is not")
    (is (= (ats {}) (clojure.set/union (ats {:from at}) (ats {:to at}))) "so that two periods that meet have every reading between them, once")
    (is (empty? (clojure.set/intersection (ats {:from at}) (ats {:to at}))))))

(deftest a-series-with-no-period-is-the-whole-cohort-and-the-limit-alone-is-as-before
  (is (= (data/series "pt-ana" :weight {:limit 500}) (data/series "pt-ana" :weight 500)))
  (is (= (data/series "pt-ana" :weight {:limit 5}) (data/series "pt-ana" :weight 5)))
  (is (= {:items [] :truncated false} (data/series "pt-noah" :weight {:from "2026-09-13T12:00:00Z" :limit 500}))))
