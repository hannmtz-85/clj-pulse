(ns pulse.design.base-test
  "What the base rules of the stylesheets (`design/css/base.css`, which the patient portal, the clinical workspace and the login theme of
  Keycloak all compile) must still say in what is committed: less motion for a person who asked for it, and, in forced colours (Windows
  high contrast, which drops every background), something other than a colour that tells the page or the filter that is current from
  the others. The acceptance of the workspace looks at both in a real browser (`bb spa:journey`); this holds what is committed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private stylesheets
  {"the patient portal" "apps/web/resources/public/pulse.css"
   "the clinical workspace" "apps/spa/public/pulse.css"
   "the login theme of Keycloak" "infra/keycloak/themes/pulse/login/resources/pulse.css"})

(defn- rule-after
  "The text of the stylesheet that follows `opening`, up to the end of its rule (the second closing brace): what the block says."
  [css opening]
  (when-let [at (str/index-of css opening)]
    (let [tail (subs css (+ at (count opening)))
          close (str/index-of tail "}")]
      (subs tail 0 (when close (inc close))))))

(deftest a-person-who-asks-for-less-motion-gets-it-in-every-stylesheet
  (doseq [[what file] stylesheets
          :let [rule (rule-after (slurp file) "@media (prefers-reduced-motion:reduce){:not(.loading)")]]
    (testing what
      (is (some? rule) "the rule is there")
      (is (str/includes? rule "animation-duration:.01ms!important"))
      (is (str/includes? rule "animation-iteration-count:1!important"))
      (is (str/includes? rule "transition-duration:.01ms!important")))))

(deftest in-forced-colours-the-current-page-is-told-by-more-than-a-colour-in-every-stylesheet
  (doseq [[what file] stylesheets
          :let [rule (rule-after (slurp file) "@media (forced-colors:active){[aria-current=page]{")]]
    (testing what
      (is (some? rule) "the rule is there")
      (is (str/includes? rule "text-decoration:underline"))
      (is (str/includes? rule "text-decoration-thickness:2px"))
      (is (str/includes? rule "font-weight:700")))))
