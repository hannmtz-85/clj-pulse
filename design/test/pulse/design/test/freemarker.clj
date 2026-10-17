(ns pulse.design.test.freemarker
  "FreeMarker as Keycloak 26.0.0 runs it, to fill in the templates of the login theme in a test (ADR-0026): the same
  engine and the same configuration (a template whose name ends in `.ftl` is HTML, so what `${...}` writes is escaped and
  `?no_esc` is how a sanitized message gets through), the same `msg` (a MessageFormat over the messages of the theme,
  that gives the key back when it has no such message) and a model that stands for what Keycloak gives a page.

  The model is a Clojure map with strings for keys, and it becomes what FreeMarker reads: a map is a hash, a vector a
  sequence, a function a method that a template calls, and strings, numbers and booleans are themselves. What the map
  does not have is missing, which is what `??` and `!` ask about. `render` answers the HTML and the keys of the messages
  that the page asked for, so that a test can tell a message nobody has written from one that is there."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pulse.design.keycloak :as kc])
  (:import (freemarker.cache FileTemplateLoader MultiTemplateLoader StringTemplateLoader TemplateLoader)
           (freemarker.core HTMLOutputFormat)
           (freemarker.template Configuration SimpleNumber SimpleScalar TemplateBooleanModel TemplateExceptionHandler
                                TemplateHashModel TemplateMethodModelEx TemplateModel TemplateNumberModel
                                TemplateScalarModel TemplateSequenceModel)
           (java.io StringWriter)
           (java.text MessageFormat)
           (java.util Locale)))

(def keycloak-messages
  "Messages of Keycloak, in English, that a page asks for by a key a template makes up from the model, and that the
  theme does not word: the required actions that a person may be asked to do."
  {"requiredAction.UPDATE_PASSWORD" "Update Password"
   "requiredAction.VERIFY_EMAIL" "Verify Email"
   "requiredAction.CONFIGURE_TOTP" "Configure OTP"})

(def properties
  "The messages a page can have: Keycloak's own that the templates ask for, and the theme's, which win."
  (merge kc/base-messages keycloak-messages kc/messages))

(defn- unwrap
  "What a template passes to a method, as Clojure data."
  [^TemplateModel value]
  (cond (instance? TemplateScalarModel value) (.getAsString ^TemplateScalarModel value)
        (instance? TemplateBooleanModel value) (.getAsBoolean ^TemplateBooleanModel value)
        (instance? TemplateNumberModel value) (.getAsNumber ^TemplateNumberModel value)
        :else (throw (ex-info "A template passed a method something that is not a string, a number or a boolean"
                              {:value value}))))

(defn model
  "The FreeMarker model of the Clojure data `x`, or nil for nil. A model is returned as it is."
  [x]
  (cond
    (nil? x) nil
    (instance? TemplateModel x) x
    (string? x) (SimpleScalar. ^String x)
    (boolean? x) (if x TemplateBooleanModel/TRUE TemplateBooleanModel/FALSE)
    (number? x) (SimpleNumber. ^Number x)
    (map? x) (reify TemplateHashModel
               (get [_ k] (model (get x k)))
               (isEmpty [_] (empty? x)))
    (sequential? x) (reify TemplateSequenceModel
                      (get [_ i] (model (nth x i)))
                      (size [_] (count x)))
    (fn? x) (reify TemplateMethodModelEx
              (exec [_ arguments] (model (apply x (map unwrap arguments)))))
    :else (throw (ex-info "No model for this value" {:value x :class (class x)}))))

(defn- message-method
  "`msg` as Keycloak has it: the key, then the arguments of a MessageFormat over the text of the message, or over the
  key itself when there is no such message. The keys asked for are added to `asked`."
  [messages asked]
  (fn [k & arguments]
    (swap! asked conj k)
    (.format (MessageFormat. ^String (get messages k k) Locale/ENGLISH) (object-array arguments))))

(defn- configuration
  "Keycloak's: `new Configuration()`, HTML as the output format of a `.ftl`, and the templates of the theme (and, for a
  test, some of its own, by name)."
  ^Configuration [templates]
  (let [string-loader (StringTemplateLoader.)]
    (doseq [[template-name text] templates]
      (.putTemplate string-loader ^String template-name ^String text))
    (doto (Configuration.)
      (.setOutputFormat HTMLOutputFormat/INSTANCE)
      (.setTemplateLoader (MultiTemplateLoader. (into-array TemplateLoader [string-loader (FileTemplateLoader. (io/file kc/theme-dir))])))
      (.setTemplateExceptionHandler TemplateExceptionHandler/RETHROW_HANDLER)
      (.setLogTemplateExceptions false))))

(defn render
  "Fills in the template `template-name` of the theme with `data`, and answers `{:html :asked :missing}`: the page, the
  keys of the messages it asked for in the order it asked, and the ones among them that no message has. Options:
  `:messages` (what `msg` knows, `properties` by default), `:sanitize` (what `kcSanitize` does to a text, which in
  Keycloak is the job of a sanitizer of HTML; here nothing unless a test says, so that it can tell which texts went
  through it) and `:templates` (templates by name, which come before those of the theme, for a test page that stands for a
  page of Keycloak's own `base` theme). `data` is the model of the page: it needs no `msg` or `kcSanitize`, which are
  added here as Keycloak adds them."
  [template-name data & {:keys [messages sanitize templates] :or {messages properties sanitize identity}}]
  (let [asked (atom [])
        template (.getTemplate (configuration templates) ^String template-name "UTF-8")
        out (StringWriter.)]
    (.process template
              (model (assoc data
                            "msg" (message-method messages asked)
                            "kcSanitize" sanitize))
              out)
    {:html (str out)
     :asked @asked
     :missing (vec (distinct (remove #(contains? messages %) @asked)))}))

(defn leftovers
  "What FreeMarker should have taken out of a page and did not: a directive or an interpolation in the text of a page."
  [html]
  (vec (re-seq #"</?#[a-z]+|</?@[a-z.]+|\$\{[^}]*\}" html)))

(defn text-of
  "The text of the page `html` with the tags taken out and white space collapsed, to read what a person would."
  [html]
  (-> html (str/replace #"(?s)<script.*?</script>|<style.*?</style>" "") (str/replace #"<[^>]*>" " ") (str/replace #"\s+" " ") str/trim))
