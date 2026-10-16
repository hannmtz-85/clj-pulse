(ns env-test
  "The .env tooling: reading dotenv text, filling the secrets of a new .env, and completing an old one
  with the variables it lacks (ADR-0025). The functions are pure, so no file is touched."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [env]))

(def ^:private example
  (str "# Copy to `.env` and adjust.\n"
       "\n"
       "# PostgreSQL\n"
       "POSTGRES_USER=pulse\n"
       "POSTGRES_PASSWORD=change-me\n"
       "\n"
       "# Confidential client secrets\n"
       "WEB_CLIENT_SECRET=change-me\n"
       "SCHEDULER_CLIENT_SECRET=change-me\n"
       "API_CLIENT_SECRET=change-me\n"))

(defn- counter
  "A stand-in for the generator of secrets that gives s1, s2, ... so that a test can say which is which."
  []
  (let [n (atom 0)]
    (fn [] (str "s" (swap! n inc)))))

;; ---------------------------------------------------------------------------
;; Reading

(deftest dotenv-text-is-read-into-pairs-in-order
  (is (= [["POSTGRES_USER" "pulse"] ["POSTGRES_PASSWORD" "change-me"] ["WEB_CLIENT_SECRET" "change-me"]
          ["SCHEDULER_CLIENT_SECRET" "change-me"] ["API_CLIENT_SECRET" "change-me"]]
         (env/entries example)))
  (testing "comments, blank lines and what is not a variable are left out; a value may hold an equals sign"
    (is (= [["A" "1"] ["B" "x=y"] ["C" ""]]
           (env/entries "# comment\n\nA=1\n  B=x=y  \nnot a variable\n# D=4\nC=\n"))))
  (testing "and as a map, the last one of a name wins"
    (is (= {"A" "2"} (env/parse "A=1\nA=2\n")))
    (is (= {} (env/parse "")))))

(deftest a-name-is-letters-digits-and-underscores-and-does-not-start-with-a-digit
  (is (= [["A1" "y"] ["_C" "z"] ["lower_case9" "w"]]
         (env/entries "1A=x\nA1=y\n-B=z\n_C=z\nlower_case9=w\nA B=q\nA-B=r\nA.B=s\n=empty\n")))
  (testing "a comment is not a variable even if it looks like one"
    (is (= [] (env/entries "# A=1\n#A=1\n  # A=1\n")))))

(deftest a-secret-is-32-characters-that-can-go-in-a-url-and-is-never-the-same
  (let [secrets (repeatedly 20 #(#'env/random-secret))]
    (is (every? #(re-matches #"[A-Za-z0-9_-]{32}" %) secrets))
    (is (= 20 (count (set secrets))))))

(deftest lines-may-end-either-way
  (is (= {"A" "1" "B" "2"} (env/parse "A=1\r\nB=2\r\n"))))

;; ---------------------------------------------------------------------------
;; A new .env

(deftest every-placeholder-gets-a-secret-and-nothing-else-changes
  (let [filled (env/fill example (counter))]
    (is (= (str "# Copy to `.env` and adjust.\n"
                "\n"
                "# PostgreSQL\n"
                "POSTGRES_USER=pulse\n"
                "POSTGRES_PASSWORD=s1\n"
                "\n"
                "# Confidential client secrets\n"
                "WEB_CLIENT_SECRET=s2\n"
                "SCHEDULER_CLIENT_SECRET=s3\n"
                "API_CLIENT_SECRET=s4\n")
           filled))
    (is (not (str/includes? filled "change-me")))))

(deftest the-line-endings-of-a-file-are-kept-when-it-is-filled
  (is (= "A=s1\r\nB=keep\r\nC=s2\r\n" (env/fill "A=change-me\r\nB=keep\r\nC=change-me\r\n" (counter))))
  (is (= "A=s1" (env/fill "A=change-me" (counter))) "also on a last line that has none")
  (is (= "A=s1\n" (env/fill "A=change-me \n" (counter)))
      "a space after the placeholder is part of it, and goes with it"))

(deftest only-a-value-that-is-the-placeholder-is-replaced
  (is (= "A=change-me-not\nB=xchange-me\nC=s1\n"
         (env/fill "A=change-me-not\nB=xchange-me\nC=change-me\n" (counter)))))

;; ---------------------------------------------------------------------------
;; An .env that exists

(def ^:private old-env
  "A .env made before the api had a secret of its own: the secrets are the owner's and must stay, and the
  file has no newline at the end, because that is how it was written."
  (str "POSTGRES_USER=pulse\n"
       "POSTGRES_PASSWORD=pw-of-the-owner\n"
       "WEB_CLIENT_SECRET=web-of-the-owner\n"
       "SCHEDULER_CLIENT_SECRET=scheduler-of-the-owner"))

(deftest an-env-from-before-the-api-had-a-secret-gets-that-one-and-nothing-more
  (let [{:keys [text added]} (env/complete old-env example (counter))]
    (is (= ["API_CLIENT_SECRET"] added))
    (is (str/starts-with? text old-env) "what it had is kept exactly as it was")
    (is (= {"POSTGRES_USER" "pulse"
            "POSTGRES_PASSWORD" "pw-of-the-owner"
            "WEB_CLIENT_SECRET" "web-of-the-owner"
            "SCHEDULER_CLIENT_SECRET" "scheduler-of-the-owner"
            "API_CLIENT_SECRET" "s1"}
           (env/parse text))
        "and the new one is a secret of its own")
    (testing "on a line of its own, after a note about why it is there"
      (is (= (str old-env "\n"
                  "\n"
                  "# Added by `bb env:init`: in .env.example, and not in this file when it was made\n"
                  "API_CLIENT_SECRET=s1\n")
             text)))))

(deftest a-file-that-ends-its-last-line-gets-one-blank-line-before-the-note
  (let [{:keys [text]} (env/complete "POSTGRES_USER=pulse\n" example (counter))]
    (is (str/starts-with? text "POSTGRES_USER=pulse\n\n# Added by `bb env:init`")
        "one blank line, not two, and not none"))
  (let [{:keys [text]} (env/complete "POSTGRES_USER=pulse" example (counter))]
    (is (str/starts-with? text "POSTGRES_USER=pulse\n\n# Added by `bb env:init`")
        "the same when the last line has no ending of its own")))

(deftest nothing-is-added-when-nothing-is-missing
  (let [complete-env (str/replace example "change-me" "mine")
        {:keys [text added]} (env/complete complete-env example (counter))]
    (is (= [] added))
    (is (identical? complete-env text) "the file as it was")))

(deftest a-variable-the-example-gives-a-value-is-added-with-that-value
  (let [{:keys [text added]} (env/complete "POSTGRES_PASSWORD=mine\n" example (counter))]
    (is (= ["POSTGRES_USER" "WEB_CLIENT_SECRET" "SCHEDULER_CLIENT_SECRET" "API_CLIENT_SECRET"] added))
    (is (= {"POSTGRES_PASSWORD" "mine" "POSTGRES_USER" "pulse"
            "WEB_CLIENT_SECRET" "s1" "SCHEDULER_CLIENT_SECRET" "s2" "API_CLIENT_SECRET" "s3"}
           (env/parse text)))))

(deftest a-value-someone-left-as-the-placeholder-is-theirs-to-change
  (let [{:keys [text added]} (env/complete "API_CLIENT_SECRET=change-me\n" example (counter))]
    (is (not (contains? (set added) "API_CLIENT_SECRET")))
    (is (= "change-me" (get (env/parse text) "API_CLIENT_SECRET")))))

(deftest a-commented-variable-is-not-a-variable
  (let [{:keys [added]} (env/complete "# API_CLIENT_SECRET=old\nPOSTGRES_USER=pulse\n" example (counter))]
    (is (some #{"API_CLIENT_SECRET"} added))))

(deftest what-is-added-ends-its-lines-as-the-file-does
  (let [{:keys [text]} (env/complete "POSTGRES_USER=pulse\r\nPOSTGRES_PASSWORD=mine\r\n" example (counter))]
    (is (str/ends-with? text "API_CLIENT_SECRET=s3\r\n"))
    (is (not (re-find #"[^\r]\n" text)) "no line of it ends with a bare newline")))

(deftest an-empty-file-gets-everything
  (let [{:keys [added text]} (env/complete "" example (counter))]
    (is (= 5 (count added)))
    (is (= 5 (count (env/parse text))))
    (is (str/starts-with? text "# Added by") "with nothing before it to be set apart from"))
  (testing "nor does a file that is only blank, whose blank line is the one that sets the note apart"
    (is (str/starts-with? (:text (env/complete "\n" example (counter))) "\n# Added by"))
    (is (str/starts-with? (:text (env/complete "  " example (counter))) "  # Added by"))))

;; ---------------------------------------------------------------------------
;; The .env.example of the repository

(deftest the-example-of-the-repository-asks-for-the-secret-of-the-api
  (let [repo-example (slurp ".env.example")
        names (set (map first (env/entries repo-example)))]
    (is (contains? names "API_CLIENT_SECRET") "the variable that the realm import resolves for pulse-api")
    (is (not (str/includes? (env/fill repo-example (counter)) "change-me")))
    (testing "and the .env of the day before has everything but that"
      (let [before (str/join "\n" (map (fn [[k v]] (str k "=" v))
                                       (remove (comp #{"API_CLIENT_SECRET"} first) (env/entries repo-example))))]
        (is (= ["API_CLIENT_SECRET"] (:added (env/complete before repo-example (counter)))))))))
