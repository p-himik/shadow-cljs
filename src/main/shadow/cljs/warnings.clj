(ns shadow.cljs.warnings
  (:require [clojure.string :as str])
  (:import (java.io BufferedReader StringReader)))

(defn get-source-excerpts
  "adds source excerpts to warnings if line information is available"
  [state {:keys [input file name] :as rc} locations]
  (let [source-lines
        (into [] (-> @input
                     (StringReader.)
                     (BufferedReader.)
                     (line-seq)))

        excerpt-offset
        5

        max-lines
        (count source-lines)

        make-source-excerpt
        (fn [line col]
          (let [before
                (Math/max 0 (- line excerpt-offset))

                idx
                (Math/max 0 (dec line))

                after
                (Math/min max-lines (+ line excerpt-offset))]

            {:start-idx before
             :before (subvec source-lines before idx)
             :line (nth source-lines idx)
             :after (subvec source-lines line after)}

            ))]

    (->> (for [{:keys [line column]} locations]
           (make-source-excerpt line column))
         (into []))))

(defn get-source-excerpt [state rc location]
  ;; defaults to a seq of locations to avoid parsing a file multiple times
  (-> (get-source-excerpts state rc [location])
      (first)))

;; https://en.wikipedia.org/wiki/ANSI_escape_code
;; https://github.com/chalk/ansi-styles/blob/master/index.js

(def sgr-pairs
  {:reset [0, 0]
   :bold [1 22]
   :dim [2 22] ;; cursive doesn't support this
   :yellow [33 39] ;; cursive doesn't seem to support 39
   :red [31 39]
   })

(defn ansi-seq [codes]
  (str \u001b \[ (str/join ";" codes) \m))

(defn coded-str [codes s]
  (let [open
        (->> (map sgr-pairs codes)
             (map first))
        close
        (->> (map sgr-pairs codes)
             (map second))]

    ;; FIXME: cursive doesn't support some ANSI codes
    ;; always reset to 0 sucks if there are nested styles
    (str (ansi-seq open) s (ansi-seq [0]))))

;; all this was pretty rushed and should be rewritten propely
;; long lines are really ugly and should maybe do some kind of word wrap
(def sep-length 80)

(defn sep-line
  ([]
   (sep-line "" 0))
  ([label offset]
   (let [sep-len (Math/max sep-length offset)
         len (count label)

         sep
         (fn [c]
           (->> (repeat c "-")
                (str/join "")))]
     (str (sep offset) label (sep (- sep-len (+ offset len)))))))

(defn print-source-lines
  [start-idx lines transform]
  (->> (for [[idx text] (map-indexed vector lines)]
         (format "%4d | %s" (+ 1 idx start-idx) text))
       (map transform)
       (str/join "\n")
       (println)))

(defn dim [s]
  (coded-str [:dim] s))


(defn print-source-excerpt-header
  [{:keys [source-excerpt column] :as warning}]
  (let [{:keys [start-idx before line after]} source-excerpt]
    (println (sep-line))
    (print-source-lines start-idx before dim)
    (print-source-lines (+ start-idx (count before)) [line] #(coded-str [:bold] %))
    ;; all CLJS warnings start at column 1
    ;; closure source mapped errors always seem to have column 0
    ;; doesn't make sense to have an arrow then
    (if (pos-int? column)
      (let [arrow-idx (+ 6 (or column 1))]
        (println (sep-line "^" arrow-idx)))
      (println (sep-line)))))

(defn print-source-excerpt-footer
  [{:keys [source-excerpt] :as warning}]
  (let [{:keys [start-idx before line after]} source-excerpt]

    (when (seq after)
      (print-source-lines (+ start-idx (count before) 1) after dim)
      (println (sep-line)))
    ))

(defn print-warning
  [{:keys [source-name file line column source-excerpt msg] :as warning}]
  (println (coded-str [:bold] (sep-line (str " WARNING #" (::idx warning) " ") 6)))
  (println " File:"
    (if-not file
      source-name
      (str file
           (when (pos-int? line)
             (str ":" line
                  (when (pos-int? column)
                    (str ":" column))))
           )))

  (if-not source-excerpt
    (do (println)
        (println (str " " (coded-str [:yellow :bold] msg)))
        (println (sep-line)))

    (do (print-source-excerpt-header warning)
        (println (str " " (coded-str [:yellow :bold] msg)))
        (println (sep-line))
        (print-source-excerpt-footer warning)))
  (println))

(defn print-warnings
  [warnings]
  (doseq [[idx w] (map-indexed vector warnings)]
    (print-warning (assoc w ::idx (inc idx)))))