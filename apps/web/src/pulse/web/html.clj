(ns pulse.web.html
  "A page of the portal as a Ring response: the hiccup of `pulse.web.pages` written as HTML by Hiccup 2, after the doctype."
  (:require [hiccup2.core :as h]))

(defn render
  "The text of a page: the doctype and the HTML of `hiccup`, as Hiccup 2 writes it for a page."
  [hiccup]
  (str "<!DOCTYPE html>\n" (h/html {:mode :html} hiccup) "\n"))

(defn response
  "A response with the page `hiccup`, with `status` (200 if none is given)."
  ([hiccup] (response 200 hiccup))
  ([status hiccup]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body (render hiccup)}))
