(ns pulse.spa.ack-test
  "Acknowledging an alert (ADR-0029, points 3 and 8): the call, what each answer of the api means, what the workspace keeps while it is
  done and what a screen reader is told. Pure, so these run on the JVM and on Node."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.ack :as ack]
            [pulse.spa.http :as http]))

(def ^:private label "BP stage 2 · Ana García")

;; ---------------------------------------------------------------------------
;; The call

(deftest the-call-is-a-post-to-the-ack-of-the-alert-with-no-body
  (is (= {:method :post
          :path ["api" "alerts" "flag-pt-ana-R1" "ack"]
          :id [:ack "flag-pt-ana-R1"]
          :on-success [:pulse.spa.ack/answered "flag-pt-ana-R1"]
          :on-refusal [:pulse.spa.ack/refused "flag-pt-ana-R1"]
          :on-failure [:pulse.spa.ack/failed "flag-pt-ana-R1"]}
         (ack/request "flag-pt-ana-R1")))
  (is (not (contains? (ack/request "x") :body)) "the alert is named by the path, and the person by the token"))

(deftest the-id-of-the-alert-is-a-segment-of-the-path-and-nothing-else
  (testing "so that what it holds cannot add to the path"
    (is (= "/api/alerts/a%2Fb%3F/ack" (http/path (:path (ack/request "a/b?")))))
    (is (= "/api/alerts/flag-pt-ana-R1/ack" (http/path (:path (ack/request "flag-pt-ana-R1")))))))

(deftest one-call-for-an-alert-stops-the-one-before-it
  (is (= (:id (ack/request "a")) (:id (ack/request "a"))))
  (is (not= (:id (ack/request "a")) (:id (ack/request "b"))) "and the calls of two alerts do not stop each other"))

;; ---------------------------------------------------------------------------
;; What is kept while it is done

(deftest the-button-is-busy-from-the-click-until-the-page-has-been-read-again
  (let [begun (ack/begin nil "a" label)]
    (is (= {:alerts {"a" {:state :sending :label label}}} begun))
    (is (true? (ack/busy? begun "a")))
    (is (false? (ack/busy? begun "b")))
    (let [settling (ack/settling begun "a" "said")]
      (is (= :settling (get-in settling [:alerts "a" :state])))
      (is (= label (ack/label-of settling "a")) "the label is kept")
      (is (true? (ack/busy? settling "a")) "it stays busy until the page has it"))
    (is (false? (ack/busy? (ack/failed begun "a" "Not done.") "a")))
    (is (false? (ack/busy? nil "a")))
    (is (false? (ack/busy? {} "a")))))

(deftest what-is-said-to-a-screen-reader-is-the-last-thing-said
  (let [begun (ack/begin {:said "before"} "a" label)]
    (is (not (contains? begun :said)) "beginning is a new thing, and what was said before is over"))
  (is (= "x" (:said (ack/settling (ack/begin nil "a" label) "a" "x"))))
  (is (= "y" (:said (ack/forget (ack/begin nil "a" label) "a" "y"))))
  (is (nil? (:said (ack/forget (ack/begin nil "a" label) "a" nil))))
  (is (not (contains? (ack/hush {:said "x" :alerts {"a" {:state :sending}}}) :said)))
  (is (= {:alerts {"a" {:state :sending}}} (ack/hush {:said "x" :alerts {"a" {:state :sending}}})) "and nothing else is touched"))

(deftest an-alert-that-was-not-acknowledged-says-why-and-its-button-is-free
  (let [failed (ack/failed (ack/begin nil "a" label) "a" "We could not confirm that the alert was acknowledged. Check the list before you try again.")]
    (is (= {:state :failed :label label :message "We could not confirm that the alert was acknowledged. Check the list before you try again."}
           (get-in failed [:alerts "a"])))
    (is (= "BP stage 2 · Ana García: We could not confirm that the alert was acknowledged. Check the list before you try again." (:said failed))
        "a screen reader is told it, with the alert it is about")
    (is (false? (ack/busy? failed "a"))))
  (testing "an alert with no name is not told as nothing: and says only the message"
    (is (= "Not done." (:said (ack/failed (ack/begin nil "a" nil) "a" "Not done."))))
    (is (= "Not done." (:said (ack/failed (ack/begin nil "a" "  ") "a" "Not done."))))))

(deftest an-alert-the-page-has-nothing-to-show-for-is-forgotten
  (let [begun (-> nil (ack/begin "a" label) (ack/begin "b" "other"))
        forgotten (ack/forget begun "a" "Said.")]
    (is (= {"b" {:state :sending :label "other"}} (:alerts forgotten)))
    (is (= "Said." (:said forgotten)))))

(deftest what-the-page-was-read-again-for-stops-waiting-and-the-rest-is-kept
  (let [acks (-> nil
                 (ack/begin "a" "A") (ack/settling "a" "x")
                 (ack/begin "b" "B")
                 (ack/begin "c" "C") (ack/failed "c" "Not done.")
                 (ack/begin "d" "D") (ack/settling "d" "x"))
        cleared (ack/clear-settling acks)]
    (is (= #{"b" "c"} (set (keys (ack/entries cleared)))) "the ones that were settling are over")
    (is (= :sending (get-in cleared [:alerts "b" :state])) "a command still on its way is not")
    (is (= :failed (get-in cleared [:alerts "c" :state])) "nor is a failure the person has not seen go")
    (is (= (:said acks) (:said cleared)))
    (is (= {:alerts {}} (ack/clear-settling nil)) "and nothing to clear is nothing"))
  (is (nil? (ack/entries {})) "but the entries of nothing are nothing")
  (is (nil? (ack/entries nil))))

(deftest the-label-of-an-alert-is-the-one-the-page-gave
  (is (= label (ack/label-of (ack/begin nil "a" label) "a")))
  (is (nil? (ack/label-of (ack/begin nil "a" label) "b")))
  (is (nil? (ack/label-of nil "a"))))

;; ---------------------------------------------------------------------------
;; What an answer means

(deftest a-409-means-that-someone-else-got-there-first
  (is (= {:result :already} (ack/verdict {:kind :refused :status 409 :problem {:title "x"}}))))

(deftest a-404-means-the-alert-is-gone
  (is (= {:result :gone} (ack/verdict {:kind :not-found :status 404}))))

(deftest a-403-is-a-refusal-that-changes-nothing
  (is (= {:result :forbidden :message "You are not allowed to acknowledge this alert."} (ack/verdict {:kind :forbidden :status 403}))))

(deftest a-400-or-a-422-is-a-command-that-was-not-done
  (doseq [status [400 422]]
    (is (= {:result :refused :message "The alert was not acknowledged."} (ack/verdict {:kind :refused :status status})) (str status))))

(deftest a-call-that-did-not-come-back-is-not-known-to-have-been-done
  (doseq [kind [:timeout :unreachable :server :unexpected :nonsense nil]]
    (is (= {:result :unconfirmed
            :message "We could not confirm that the alert was acknowledged. Check the list before you try again."}
           (ack/verdict {:kind kind})) (pr-str kind))))

(deftest a-call-that-was-stopped-or-a-sign-in-the-api-refused-are-left-to-those-who-answer-them
  (is (= {:result :ignore} (ack/verdict {:kind :aborted})))
  (is (= {:result :ignore} (ack/verdict {:kind :unauthorized :status 401}))))

(deftest every-kind-of-failure-the-effect-tells-has-a-meaning
  (doseq [kind http/failure-kinds
          :let [verdict (ack/verdict {:kind kind})]]
    (is (contains? #{:gone :forbidden :ignore :unconfirmed} (:result verdict)) (pr-str kind))
    (is (or (nil? (:message verdict)) (string? (:message verdict))))))

;; ---------------------------------------------------------------------------
;; What a screen reader is told

(deftest what-is-told-of-each-end-names-the-alert-it-is-about
  (is (= "BP stage 2 · Ana García: Alert acknowledged." (ack/announcement :done label)))
  (is (= "BP stage 2 · Ana García: That alert was already acknowledged." (ack/announcement :already label)))
  (is (= "BP stage 2 · Ana García: That alert could not be found." (ack/announcement :gone label)))
  (is (= "BP stage 2 · Ana García: You are not allowed to acknowledge this alert." (ack/announcement :forbidden label)))
  (is (= "BP stage 2 · Ana García: The alert was not acknowledged." (ack/announcement :refused label)))
  (is (= "BP stage 2 · Ana García: We could not confirm that the alert was acknowledged." (ack/announcement :unconfirmed label))))

(deftest an-alert-with-no-name-is-told-without-one
  (doseq [no-label [nil "" "   "]]
    (is (= "Alert acknowledged." (ack/announcement :done no-label)) (pr-str no-label))
    (is (= "That alert was already acknowledged." (ack/announcement :already no-label)))))

;; ---------------------------------------------------------------------------
;; Where the focus belongs

(deftest the-focus-belongs-on-the-first-alert-that-was-not-acknowledged
  (let [acks (-> nil
                 (ack/begin "a" "A") (ack/failed "a" "No.")
                 (ack/begin "b" "B")
                 (ack/begin "c" "C") (ack/failed "c" "No."))]
    (is (= "a" (ack/first-failed acks ["a" "b" "c"])))
    (is (= "c" (ack/first-failed acks ["b" "c" "a"])) "in the order the page shows them, and not the order they happened in")
    (is (= "c" (ack/first-failed acks ["b" "c"])) "among the alerts that are still on the page")
    (is (nil? (ack/first-failed acks ["b" "d"])) "a command on its way is not one that failed, and an alert with no entry is none")
    (is (nil? (ack/first-failed acks [])))
    (is (nil? (ack/first-failed nil ["a"])))
    (is (nil? (ack/first-failed (ack/settling (ack/begin nil "a" "A") "a" "x") ["a"])))))
