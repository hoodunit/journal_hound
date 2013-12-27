(ns journal-hound.acm
  (:require [clj-webdriver.taxi :refer :all]
            [journal-hound.file-utils :as util]
            [journal-hound.journal :as journal])
  (:import java.util.Calendar))

(def communications-title "Communications of the ACM")

(def acm-base-url "http://cacm.acm.org/magazines")
(def aalto-acm-base-url "http://cacm.acm.org.libproxy.aalto.fi/magazines")

(defn next-issue [{:keys [year month]}]
  (if (= month 12)
    {:year (inc year) :month 1}
    {:year year :month (inc month)}))

(defn prev-issue [{:keys [year month]}]
  (if (= month 1)
    {:year (dec year) :month 12}
    {:year year :month (dec month)}))

(defn get-current-year-month []
  (let [calendar (Calendar/getInstance)
        current-year (.get calendar Calendar/YEAR)
        current-month (inc (.get calendar Calendar/MONTH))]
    {:year current-year
     :month current-month}))

(defn issue-published? [issue]
  (let [url (format "%s/%s/%s" acm-base-url (:year issue) (:month issue))]
    (try (clojure.java.io/reader url)
         (catch java.io.FileNotFoundException e nil))))

(defn get-latest-issue []
  (let [current-month-issue (get-current-year-month)
        next-month-issue (next-issue current-month-issue)
        prev-month-issue (prev-issue current-month-issue)]
    (first (drop-while #(not (issue-published? %)) [next-month-issue 
                                                    current-month-issue 
                                                    prev-month-issue]))))

(defn get-downloaded-journals [dest-dir]
  (util/get-file-names-with-extension dest-dir "pdf"))

(defn issue-filename [{:keys [year month]}]
  (format "communications_%s_%s.pdf" year month))

(defn get-outdated-journals [{:keys [dest-dir acm-journals]}]
  (when (seq acm-journals)
    (let [latest-issue (-> (get-latest-issue)
                           (assoc :title communications-title
                                  :type :acm))
          downloaded (get-downloaded-journals dest-dir)]
      (print (format "%-60s" (:title latest-issue)))
      (if (contains? downloaded (issue-filename latest-issue))
        (do (println "Up to date") 
            [])
        (do (println "Outdated") 
            [latest-issue])))))

(defn element-url-starts-with [elem prefix]
  (->> elem
       :webelement
       (#(.getAttribute % "Href"))
       (#(and % (.startsWith % prefix)))))

(defn get-link-with-url-prefix [prefix]
  (->> (find-elements {:tag :a})
       (filter #(element-url-starts-with % prefix))
       (first)))

(defn click-and-switch-windows [elem]
  (let [current-window (window)]
    (click elem)
    (let [other-window (->> (windows)
                            (filter #(not= current-window %))
                            (first))]
      (switch-to-window other-window))))

(defn start-pdf-download []
  (click-and-switch-windows (get-link-with-url-prefix "http://dl.acm.org"))
  (wait-until (find-element {:text "PDF"}) 10000)
  (click {:text "PDF"}))

(defn download-acm-issue [download-dir year month]
  (let [issue-url (format "%s/%s/%s" aalto-acm-base-url year month)]
    (to issue-url)
    (start-pdf-download)
    (journal/wait-for-download-to-complete download-dir)
    (close)))

(defn move-issue [download-dir dest-dir year month]
  (let [orig-file (first (util/get-files-with-extension download-dir "pdf")) 
        file-name (.getName orig-file)
        moved-file-name (format "%s/communications_%d_%d.pdf" dest-dir year month)
        moved-file (clojure.java.io/file moved-file-name)]
    (.renameTo orig-file moved-file)))

(defn fetch-journal
  "Assumes you are logged in"
  [{:keys [download-dir dest-dir] :as config} journal]
  (let [calendar (Calendar/getInstance)
        current-year (.get calendar Calendar/YEAR)
        current-month (inc (.get calendar Calendar/MONTH))]
    (download-acm-issue download-dir current-year current-month)
    (move-issue download-dir dest-dir current-year current-month)))
