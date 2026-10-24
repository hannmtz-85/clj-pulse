(ns pulse.web.home-page-test
  "The home of the portal as a function of the data in the shapes of the api (ADR-0028): what it shows for a patient with
  readings, for one with none, and for each part that the api could not give; where its buttons lead, and that a button whose
  page the portal does not have yet is not there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.pages.home :as home]
            [pulse.web.pages.parts :as parts]
            [pulse.web.views :as views]))

(def ^:private now data/now-text)

(def ^:private ana (portal/home-model "pt-ana"))

(def ^:private noah (portal/home-model "pt-noah"))

(def ^:private all-links portal/links)

(defn- buttons [tree]
  (hx/select tree #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))

(defn- link-named [tree text]
  (first (filter #(= text (hx/words %)) (concat (buttons tree) (hx/by-tag tree :a)))))

(defn- href [element] (:href (hx/attributes element)))

(defn- section [tree id]
  (first (hx/select tree #(and (= :section (hx/tag %)) (= id (:aria-labelledby (hx/attributes %)))))))

(defn- section-ids [tree]
  (keep #(:aria-labelledby (hx/attributes %)) (hx/by-tag tree :section)))

(defn- alerts [tree]
  (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))

(defn- lost
  "The summary of `patient-id` with the parts named in `unavailable` not loaded: the api says null for each, and names it."
  [model & {:keys [appointments vitals]}]
  (let [vital-names (fn [kinds] (map #(str "vitals." (name %)) kinds))]
    (-> model
        (update :summary
                (fn [summary]
                  (cond-> summary
                    appointments (assoc :appointments nil)
                    (seq vitals) (update :vitals merge (zipmap vitals (repeat nil)))
                    true (assoc :unavailable (vec (concat (when appointments ["appointments"]) (vital-names vitals))))))))))

;; ---------------------------------------------------------------------------
;; Where the buttons lead

(deftest every-button-is-there-when-every-page-is
  (let [tree (home/content ana all-links)]
    (is (= "../portal-new-reading.html" (href (link-named tree "Record a reading"))))
    (is (= "../portal-readings.html" (href (link-named tree "See my readings"))))
    (is (= "../portal-readings.html" (href (link-named tree "See all"))))
    (is (= "../portal-cancel.html" (href (link-named tree "Cancel appointment"))))))

(deftest a-button-whose-page-is-not-there-yet-is-not-there
  (let [labels ["Record a reading" "See my readings" "See all" "Cancel appointment"]]
    (testing "with no link at all"
      (let [tree (home/content ana {})]
        (is (every? nil? (map #(link-named tree %) labels)))
        (is (empty? (hx/by-tag tree :a)) "nothing on the page leads anywhere")
        (is (= [] (a11y/problems (into [:main] [tree]))))))
    (testing "and each is there only when its own link is"
      (doseq [[key present] {:new-reading #{"Record a reading"} :readings #{"See my readings" "See all"}}]
        (let [tree (home/content ana {key "/somewhere"})]
          (is (= present (set (filter #(some? (link-named tree %)) labels))) (pr-str key))
          (is (every? #(= "/somewhere" (href (link-named tree %))) present)))))))

(deftest the-address-of-a-cancellation-is-a-function-of-the-appointment
  (let [seen (atom [])
        tree (home/content ana {:cancel-appointment (fn [row] (swap! seen conj row) (str "/appointments/" (:id row) "/cancel"))})]
    (is (= "/appointments/appt-ana-1/cancel" (href (link-named tree "Cancel appointment"))))
    (is (= ["appt-ana-1"] (map :id @seen)) "it is given the row of the appointment, and only that one")
    (is (= "Wed 14 Oct, 10:00–10:30" (:when (first @seen))))
    (testing "a function that has no address leaves the button out"
      (is (nil? (link-named (home/content ana {:cancel-appointment (constantly nil)}) "Cancel appointment"))))))

;; ---------------------------------------------------------------------------
;; A patient with readings

(deftest the-home-of-a-patient-with-readings-has-these-parts-in-this-order
  (let [tree (home/content ana all-links)]
    (is (= :div (hx/tag tree)))
    (is (= [:header :div :section :section :section] (map hx/tag (filter hx/element? (hx/child-nodes tree)))))
    (is (= ["next" "latest" "recent"] (section-ids tree)))
    (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag tree :h1))))
    (is (= ["Your next appointment" "Your latest readings" "Recent readings"] (map hx/words (hx/by-tag tree :h2))))))

(deftest the-greeting-is-by-the-part-of-the-day-and-by-the-name-the-api-gave
  (let [greeting (fn [model] (hx/words (first (hx/by-tag (home/content model all-links) :h1))))]
    (is (= ["Good morning, Ana" "Good afternoon, Ana" "Good evening, Ana"]
           (map #(greeting (assoc ana :now %)) ["2026-10-13T06:00:00Z" "2026-10-13T12:00:00Z" "2026-10-13T19:00:00Z"])))
    (testing "a patient whose resource has no name, or none that can be said, is greeted without one"
      (doseq [me [(assoc ana :me (update (:me ana) :resource dissoc :name))
                  (assoc ana :me (assoc-in (:me ana) [:resource :name] []))
                  (assoc ana :me (assoc-in (:me ana) [:resource :name 0 :given] []))
                  (assoc ana :me (assoc-in (:me ana) [:resource :name 0 :given 0] "  "))
                  (assoc ana :me (assoc (:me ana) :resource nil))
                  (assoc ana :me nil)]]
        (is (= "Good afternoon" (greeting me)) (pr-str (:resource (:me me))))))
    (testing "the first of the given names"
      (is (= "Good afternoon, María"
             (greeting (assoc-in ana [:me :resource :name 0 :given] ["María" "José"])))))))

(deftest the-date-is-the-date-in-the-clinic
  (is (= "Tuesday, 13 October 2026" (hx/words (first (hx/select (home/content ana all-links) #(hx/has-class? % "text-muted"))))))
  (is (str/includes? (hx/words (home/content (assoc ana :now "2026-10-13T22:30:00Z") all-links)) "Wednesday, 14 October 2026")
      "22:30 UTC is the next day in Madrid"))

(deftest the-notice-is-there-when-the-pressure-is-very-high-and-only-then
  (let [notice (fn [model] (first (hx/select (home/content model all-links) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft")))))
        with-band (fn [band] (assoc-in ana [:summary :vitals :blood-pressure :band] band))]
    (is (some? (notice ana)))
    (is (str/includes? (hx/words (notice ana)) "Your last blood pressure reading is very high"))
    (is (str/includes? (hx/words (notice ana)) "177/100 mmHg, today, 10:00"))
    (is (str/includes? (hx/words (notice ana)) "call 112"))
    (doseq [band ["normal" "elevated" "high" "very-low" nil]]
      (is (nil? (notice (with-band band))) (pr-str band)))
    (testing "a pressure with no numbers is not told"
      (is (nil? (notice (assoc-in ana [:summary :vitals :blood-pressure :systolic] nil)))))
    (testing "with no time, it still says what it was"
      (let [words (hx/words (notice (assoc-in ana [:summary :vitals :blood-pressure :at] nil)))]
        (is (str/includes? words "177/100 mmHg. If you feel unwell"))))
    (testing "and the unit is the reading's, mmHg when it has none"
      (is (str/includes? (hx/words (notice (assoc-in ana [:summary :vitals :blood-pressure :unit] "kPa"))) "177/100 kPa,"))
      (is (str/includes? (hx/words (notice (assoc-in ana [:summary :vitals :blood-pressure :unit] nil))) "177/100 mmHg,")))
    (testing "it does not say that anyone has been told"
      (is (not (re-find #"(?i)told|notified|informed" (str/replace (hx/words (notice ana)) "tell your care team" "")))))))

(deftest the-tiles-are-the-latest-reading-of-each-measurement-the-patient-has
  (let [tiles (fn [model] (map hx/words (hx/select (home/content model all-links) #(hx/has-class? % "text-4xl"))))]
    (is (= ["177/100" "70" "82.3"] (tiles ana)))
    (is (= ["130/80" "70" "92" "82.3"]
           (tiles (update-in ana [:summary :vitals] assoc
                             :blood-pressure {:id "b" :at now :systolic 130 :diastolic 80 :unit "mm[Hg]" :band "normal"}
                             :spo2 {:id "s" :at now :value 92 :unit "%" :band "low"})))
        "in the order of the measurements")
    (testing "a reading that says nothing has no tile"
      (is (= ["70" "82.3"] (tiles (assoc-in ana [:summary :vitals :blood-pressure :diastolic] nil)))))))

(deftest the-recent-readings-are-five-whatever-the-series-hold
  (let [rows (fn [model] (hx/by-tag (first (hx/select (home/content model all-links) #(= "Recent readings" (:aria-label (hx/attributes %))))) :li))]
    (is (= 5 home/recent-count))
    (is (= 5 (count (rows ana))))
    (is (= 2 (count (rows (update ana :recent #(vec (take 2 %)))))))
    (testing "and when there are none among them that can be shown, the section is not there"
      (is (not (contains? (set (section-ids (home/content (assoc ana :recent []) all-links))) "recent")))
      (is (not (contains? (set (section-ids (home/content (assoc ana :recent [{:measurement "heart-rate" :at nil :value 70}]) all-links))) "recent"))))
    (testing "the note says what the words beside a reading are and are not"
      (is (str/includes? (hx/words (section (home/content ana all-links) "recent")) "They are not a diagnosis.")))))

;; ---------------------------------------------------------------------------
;; The next appointment

(deftest the-next-appointment-has-the-way-to-cancel-it-and-the-doctor-of-the-patient
  (let [tree (home/content ana all-links)
        row (first (hx/by-tag (section tree "next") :li))]
    (is (= 1 (count (hx/by-tag (section tree "next") :li))))
    (is (= ["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"] (map hx/words (hx/by-tag row :p))))
    (is (str/includes? (hx/words row) "Confirmed"))
    (testing "a doctor the api did not name is left out, with the dot that joined it"
      (let [no-doctor (assoc-in ana [:me :generalPractitioner] nil)
            row (first (hx/by-tag (section (home/content no-doctor all-links) "next") :li))]
        (is (= ["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30"] (map hx/words (hx/by-tag row :p))))))
    (testing "a doctor with no name is not a doctor"
      (is (= ["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30"]
             (map hx/words (hx/by-tag (first (hx/by-tag (section (home/content (assoc-in ana [:me :generalPractitioner :name] nil) all-links) "next") :li)) :p)))))))

(deftest a-patient-with-no-appointment-coming-up-is-told-so-and-can-ask-for-one
  (let [model (assoc-in ana [:summary :appointments] [])
        tree (home/content model all-links)
        next-words (hx/words (section tree "next"))]
    (is (str/includes? next-words "No appointment coming up"))
    (is (str/includes? next-words "When your clinic confirms one, you will see it here."))
    (is (= "../portal-appointments.html#ask" (href (link-named (section tree "next") "Ask for a visit"))))
    (testing "and with nowhere to ask, it only says so"
      (is (nil? (link-named (home/content model {}) "Ask for a visit"))))
    (testing "an appointment that has begun, and one that was cancelled, are not coming up"
      (is (str/includes? (hx/words (section (home/content (assoc-in ana [:summary :appointments]
                                                                    [{:id "p" :status "booked" :description "Past" :start "2026-10-01T08:00:00Z" :end "2026-10-01T08:30:00Z"
                                                                      :requestedStart nil :requestedEnd nil :practitioner nil :reason nil}
                                                                     {:id "c" :status "cancelled" :description "Gone" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"
                                                                      :requestedStart nil :requestedEnd nil :practitioner nil :reason "x"}])
                                                          all-links)
                                            "next"))
                         "No appointment coming up")))))

(deftest a-request-that-waits-is-the-next-appointment-when-nothing-is-booked
  (let [tree (home/content noah all-links)
        row (first (hx/by-tag (section tree "next") :li))]
    (is (= ["New patient intake" "Asked for a visit between Oct 14 and Oct 20 · Dr. Chidi Okafor"] (map hx/words (hx/by-tag row :p))))
    (is (str/includes? (hx/words row) "Requested"))
    (is (= "../portal-cancel-request.html" (href (link-named row "Cancel request"))))
    (is (nil? (link-named row "Cancel appointment")))))

;; ---------------------------------------------------------------------------
;; A patient with no readings

(deftest a-patient-with-no-readings-is-welcomed-and-told-what-to-do-first
  (let [tree (home/content noah all-links)]
    (is (= ["first" "latest" "next"] (section-ids tree)))
    (is (= ["Welcome, Noah"] (map hx/words (hx/by-tag tree :h1))))
    (is (str/includes? (hx/words tree) "Your clinic has set up your account."))
    (is (= [["Record your first reading" "Blood pressure, heart rate, oxygen, glucose, weight or temperature."]
            ["Look at your record" "Your doctor sees what you see there."]]
           (map #(map hx/words (hx/by-tag % :p)) (hx/by-tag (section tree "first") :li))))
    (is (str/includes? (hx/words (section tree "latest")) "No readings yet"))
    (is (= "../portal-new-reading.html" (href (link-named (section tree "latest") "Record a reading"))))
    (is (empty? (hx/select tree #(hx/has-class? % "text-4xl"))) "no tile of a reading")
    (is (not (contains? (set (section-ids tree)) "recent")))))

(deftest a-first-step-is-there-only-when-the-page-it-is-about-is
  (testing "with no page, no step and no section"
    (let [tree (home/content noah {})]
      (is (= ["latest" "next"] (section-ids tree)))
      (is (nil? (link-named tree "Record a reading")))))
  (testing "one of them"
    (is (= ["Record your first reading"]
           (map #(hx/words (first (hx/by-tag % :p))) (hx/by-tag (section (home/content noah {:new-reading "/readings/new"}) "first") :li))))
    (is (= ["Look at your record"]
           (map #(hx/words (first (hx/by-tag % :p))) (hx/by-tag (section (home/content noah {:record "/record"}) "first") :li))))))

(deftest a-patient-whose-only-readings-say-nothing-is-welcomed-as-new
  ;; a reading with no number cannot be shown, and a page that said "your latest readings" over nothing would say less than the truth
  (let [odd (assoc-in noah [:summary :vitals :weight] {:id "w" :at now :value nil :unit "kg" :band nil})]
    (is (true? (home/new-patient? (:summary odd))))
    (is (= ["Welcome, Noah"] (map hx/words (hx/by-tag (home/content odd all-links) :h1))))
    (is (str/includes? (hx/words (home/content odd all-links)) "No readings yet"))))

(deftest nobody-is-taken-for-a-new-patient-when-the-api-could-not-say
  (testing "who has nothing, when every part loaded"
    (is (true? (home/new-patient? (:summary noah)))))
  (testing "who has readings"
    (is (false? (home/new-patient? (:summary ana)))))
  (testing "whose readings could not be loaded, all or some of them"
    (is (false? (home/new-patient? (:summary (lost noah :vitals views/kinds)))))
    (is (false? (home/new-patient? (:summary (lost noah :vitals [:glucose]))))))
  (testing "whose appointments could not be loaded, which says nothing of readings"
    (is (true? (home/new-patient? (:summary (lost noah :appointments true))))))
  (testing "and when there is no summary at all"
    (is (false? (home/new-patient? nil)))))

;; ---------------------------------------------------------------------------
;; A part that could not be loaded

(deftest a-summary-that-could-not-be-had-is-one-message-and-a-way-to-try-again
  (let [tree (home/content (assoc ana :summary nil :recent nil) all-links)]
    (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag tree :h1))))
    (is (str/includes? (hx/words tree) "Tuesday, 13 October 2026"))
    (is (= [] (section-ids tree)))
    (is (= 1 (count (alerts tree))))
    (is (str/includes? (hx/words (first (alerts tree))) "Your appointments and readings could not be loaded"))
    (is (str/includes? (hx/words (first (alerts tree))) "The problem is on our side, not yours."))
    (is (not (str/includes? (hx/words (first (alerts tree))) "The rest of the page")) "there is no rest of the page to speak of")
    (is (= "../portal-home.html" (href (link-named tree "Try again"))))
    (testing "no button that makes no sense without the summary"
      (is (nil? (link-named tree "See all"))))
    (testing "the way to try again is not there when the page has no address for it"
      (is (nil? (link-named (home/content (assoc ana :summary nil) (dissoc all-links :retry)) "Try again"))))))

(deftest the-appointments-that-could-not-be-loaded-are-a-message-where-they-were
  (let [tree (home/content (lost ana :appointments true) all-links)]
    (is (= ["next" "latest" "recent"] (section-ids tree)))
    (is (str/includes? (hx/words (section tree "next")) "Your appointments could not be loaded"))
    (is (empty? (hx/by-tag (section tree "next") :li)) "no row that could be taken for the list of appointments")
    (is (nil? (link-named (section tree "next") "Ask for a visit")) "she may have one, and the page does not know")
    (is (= ["177/100" "70" "82.3"] (map hx/words (hx/select tree #(hx/has-class? % "text-4xl")))))
    (is (= 1 (count (alerts tree))))))

(deftest some-readings-that-could-not-be-loaded-are-a-message-above-the-ones-that-were
  (let [tree (home/content (lost ana :vitals [:heart-rate]) all-links)
        latest (section tree "latest")]
    (is (str/includes? (hx/words latest) "Some of your readings could not be loaded"))
    (is (= ["177/100" "82.3"] (map hx/words (hx/select latest #(hx/has-class? % "text-4xl")))))
    (is (= 1 (count (alerts latest))))
    (is (= ["next" "latest" "recent"] (section-ids tree)))
    (testing "the message comes first, and there is no new-patient page for somebody who has readings"
      (let [[message grid] (filter hx/element? (hx/child-nodes (first (hx/select latest #(hx/has-class? % "space-y-4")))))]
        (is (str/includes? (hx/words message) "Some of your readings could not be loaded") "the message is the first thing in the box")
        (is (= ["177/100" "82.3"] (map hx/words (hx/select grid #(hx/has-class? % "text-4xl")))) "and the tiles are under it"))
      (is (not (contains? (set (section-ids tree)) "first"))))))

(deftest every-latest-reading-that-could-not-be-loaded-is-a-message-and-no-list-of-recent-ones
  (let [tree (home/content (-> (lost ana :vitals views/kinds) (assoc :recent nil)) all-links)]
    (is (= ["next" "latest"] (section-ids tree)))
    (is (str/includes? (hx/words (section tree "latest")) "Your latest readings could not be loaded"))
    (is (empty? (hx/select tree #(hx/has-class? % "text-4xl"))))
    (is (not (str/includes? (hx/words tree) "Welcome")) "who has readings that could not be loaded is not new")
    (is (not (str/includes? (hx/words tree) "Your last blood pressure reading is very high")) "and nothing is said of a reading that is not there")
    (testing "the way to see them all is still there: the page for them may work"
      (is (some? (link-named (section tree "latest") "See all"))))))

(deftest the-recent-readings-that-could-not-be-loaded-are-a-message-in-their-list
  (let [tree (home/content (assoc ana :recent nil) all-links)]
    (is (= ["next" "latest" "recent"] (section-ids tree)))
    (is (str/includes? (hx/words (section tree "recent")) "Your recent readings could not be loaded"))
    (is (= ["177/100" "70" "82.3"] (map hx/words (hx/select tree #(hx/has-class? % "text-4xl")))) "the latest readings are there")
    (is (= "../portal-home.html" (href (link-named (section tree "recent") "Try again"))))))

(deftest several-parts-lost-are-several-messages
  (let [tree (home/content (-> (lost ana :appointments true :vitals [:weight]) (assoc :recent nil)) all-links)]
    (is (= 3 (count (alerts tree))))
    (is (= ["next" "latest" "recent"] (section-ids tree)))))

;; ---------------------------------------------------------------------------
;; The page passes the checks a program can make

(deftest every-state-of-the-home-passes-the-checks-of-accessibility
  (doseq [[what model links] [["a patient with readings" ana all-links]
                              ["a patient with readings, with no link" ana {}]
                              ["a new patient" noah all-links]
                              ["a new patient with no link" noah {}]
                              ["no summary" (assoc ana :summary nil :recent nil) all-links]
                              ["no appointments" (lost ana :appointments true) all-links]
                              ["some readings lost" (lost ana :vitals [:heart-rate]) all-links]
                              ["all readings lost" (lost ana :vitals views/kinds) all-links]
                              ["no recent readings" (assoc ana :recent nil) all-links]
                              ["nothing coming up" (assoc-in ana [:summary :appointments] []) all-links]]]
    (testing what
      (is (= [] (a11y/problems [:main (home/content model links)]))))))

;; ---------------------------------------------------------------------------
;; The parts

(def ^:private booked
  {:id "a1" :status "booked" :description "Review" :doctor "Dr. Elena Rivera" :month "Oct" :day "14"
   :when "Wed 14 Oct, 10:00–10:30" :start "2026-10-14T08:00:00Z"})

(def ^:private request
  {:id "a2" :status "proposed" :description "Review" :doctor "Dr. Elena Rivera"
   :asked "Asked for a visit between Oct 14 and Oct 20" :requested-start "2026-10-14T00:00:00Z"})

(def ^:private cancelled
  {:id "a3" :status "cancelled" :description "Review" :doctor "Dr. Elena Rivera" :month "Sep" :day "29"
   :when "Tue 29 Sep, 09:00–09:30" :start "2026-09-29T07:00:00Z" :reason "I had to travel."})

(def ^:private links
  {:cancel-appointment (fn [row] (str "/appointments/" (:id row) "/cancel"))
   :cancel-request (fn [row] (str "/appointments/" (:id row) "/withdraw"))})

(deftest what-a-patient-may-cancel-is-for-the-lifecycle-to-say
  (let [button-of (fn [row now] (some-> (first (buttons (parts/appointment-item row links now))) hx/words))]
    (testing "a booked appointment until it begins, and a request at any time"
      (is (= "Cancel appointment" (button-of booked "2026-10-13T12:00:00Z")))
      (is (= "Cancel appointment" (button-of booked "2026-10-14T07:59:59Z")))
      (is (= "Cancel request" (button-of request "2026-10-13T12:00:00Z")))
      (is (= "Cancel request" (button-of request "2030-01-01T00:00:00Z"))))
    (testing "not one that has begun, and not one that is cancelled"
      (is (nil? (button-of booked "2026-10-14T08:00:00Z")))
      (is (nil? (button-of booked "2026-10-15T08:00:00Z")))
      (is (nil? (button-of cancelled "2026-10-13T12:00:00Z"))))
    (testing "not a status the lifecycle does not know"
      (is (nil? (button-of (assoc booked :status "fulfilled") "2026-10-13T12:00:00Z")))
      (is (nil? (button-of (assoc booked :status "pending") "2026-10-13T12:00:00Z"))))))

(deftest the-button-of-each-kind-leads-where-its-own-link-says
  (is (= "/appointments/a1/cancel" (href (first (buttons (parts/appointment-item booked links "2026-10-13T12:00:00Z"))))))
  (is (= "/appointments/a2/withdraw" (href (first (buttons (parts/appointment-item request links "2026-10-13T12:00:00Z"))))))
  (testing "a kind with no link has no button, and the other has"
    (is (empty? (buttons (parts/appointment-item booked (dissoc links :cancel-appointment) "2026-10-13T12:00:00Z"))))
    (is (some? (first (buttons (parts/appointment-item request (dissoc links :cancel-appointment) "2026-10-13T12:00:00Z")))))
    (is (empty? (buttons (parts/appointment-item request (dissoc links :cancel-request) "2026-10-13T12:00:00Z"))))))

(deftest a-row-of-appointments-has-its-day-its-words-and-its-status
  (let [row-of (fn [row] (parts/appointment-item row links "2026-10-13T12:00:00Z"))]
    (testing "booked: the day on a calendar page, the what and the when"
      (is (= ["Oct" "14"] (map hx/words (hx/by-tag (first (hx/select (row-of booked) #(hx/has-class? % "w-14"))) :span))))
      (is (= ["Review" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"] (map hx/words (hx/by-tag (row-of booked) :p))))
      (is (str/includes? (hx/words (row-of booked)) "Confirmed")))
    (testing "a request has a clock and the days that were asked for"
      (is (empty? (hx/select (row-of request) #(and (hx/has-class? % "w-14") (seq (hx/by-tag % :span))))) "no day")
      (is (= ["Review" "Asked for a visit between Oct 14 and Oct 20 · Dr. Elena Rivera"] (map hx/words (hx/by-tag (row-of request) :p))))
      (is (str/includes? (hx/words (row-of request)) "Requested")))
    (testing "a cancelled one has the reason that was given"
      (is (= ["Review" "Tue 29 Sep, 09:00–09:30 · Dr. Elena Rivera" "Reason: I had to travel."] (map hx/words (hx/by-tag (row-of cancelled) :p))))
      (is (str/includes? (hx/words (row-of cancelled)) "Cancelled")))
    (testing "without a doctor it is only the time"
      (is (= ["Review" "Wed 14 Oct, 10:00–10:30"] (map hx/words (hx/by-tag (row-of (dissoc booked :doctor)) :p)))))
    (testing "its chip and button go under the text on a phone"
      (is (hx/has-class? (first (hx/select (row-of booked) #(hx/has-class? % "min-w-0"))) "basis-44")))))

(deftest the-tiles-are-in-the-order-of-the-measurements-and-leave-out-what-says-nothing
  (let [vitals {:weight {:id "w" :at now :value 82 :unit "kg" :band nil}
                :heart-rate {:id "h" :at now :value 70 :unit "/min" :band "normal"}
                :blood-pressure nil
                :glucose {:id "g" :at now :value nil :unit "mg/dL" :band nil}
                :temperature {:id "t" :at now :value 36.6 :unit "Cel" :band nil}}]
    (is (= ["70" "82" "36.6"] (map hx/words (hx/select (parts/readings-grid vitals now) #(hx/has-class? % "text-4xl")))))
    (is (= [] (hx/select (parts/readings-grid {} now) #(hx/has-class? % "text-4xl"))))
    (is (= [] (hx/select (parts/readings-grid nil now) #(hx/has-class? % "text-4xl"))))
    (is (every? #(hx/has-class? (parts/readings-grid vitals now) %) ["grid" "gap-4" "sm:grid-cols-2" "lg:grid-cols-3"]))))

(deftest a-message-about-a-part-that-could-not-be-loaded-says-what-and-that-it-is-not-the-patients-doing
  (let [message (parts/not-loaded "Your appointments could not be loaded" {:retry "/where"})]
    (is (hx/has-class? message "bg-tone-high-soft"))
    (is (= ["Your appointments could not be loaded"] (map hx/words (hx/by-tag message :p))))
    (is (str/includes? (hx/words message) "The rest of the page is up to date. The problem is on our side, not yours."))
    (is (= [["Try again" "/where"]] (map (juxt hx/words href) (hx/by-tag message :a))))
    (is (empty? (hx/by-tag (parts/not-loaded "x" {}) :a)))
    (is (empty? (a11y/problems [:main message])))
    (testing "when it was all there was, it does not speak of a rest of the page"
      (let [alone (parts/not-loaded "Your appointments and readings could not be loaded" {:retry "/where"} {:rest-of-page? false})]
        (is (str/includes? (hx/words alone) "The problem is on our side, not yours."))
        (is (not (str/includes? (hx/words alone) "The rest of the page")))
        (is (= [["Try again" "/where"]] (map (juxt hx/words href) (hx/by-tag alone :a)))))
      (is (= (parts/not-loaded "x" {:retry "/r"}) (parts/not-loaded "x" {:retry "/r"} {:rest-of-page? true})) "the rest is there by default")
      (is (= (parts/not-loaded "x" {:retry "/r"}) (parts/not-loaded "x" {:retry "/r"} {}))))))

(deftest a-section-has-the-heading-its-section-names-itself-by
  (is (= [:h2 {:class "text-xl font-semibold tracking-tight" :id "next"} "Title"] (parts/section-heading "next" "Title")))
  (is (= [:h2 {:class "text-xl font-semibold tracking-tight"} "Title"] (parts/section-heading "Title"))))
