(ns journal-hound.ieee
  (:require [clj-webdriver.taxi :refer :all]
            net.cgrand.enlive-html
            clojure-csv.core
            [journal-hound.file-utils :as file-utils]
            [journal-hound.util :as util]
            [journal-hound.journal :as journal])
  (:import java.util.Calendar
           org.apache.pdfbox.util.PDFMergerUtility))

(def journal-list-url "http://ieeexplore.ieee.org/otherfiles/OPACJrnListIEEE.txt")
(def journal-info-url "http://ieeexplore.ieee.org/xpl/opacissue.jsp?punumber=")
(def journal-url "http://ieeexplore.ieee.org.libproxy.aalto.fi/xpl/mostRecentIssue.jsp?punumber=")

(defn get-journal-urls [journals]
  (with-open [rdr (clojure.java.io/reader journal-list-url)]
    (let [urls (->> (line-seq rdr)
                    (map clojure-csv.core/parse-csv)
                    (map first)
                    (map (fn [[title pub-num _ _ current _]] 
                           {:title title 
                            :pub-num (util/str->num pub-num) 
                            :current-vol (util/str->num current)}))
                    (filter #(contains? journals (:title %))))]
      (dorun urls)
      urls)))

(defn get-downloaded-journals [dest-dir]
  (file-utils/get-files-with-extension-as-hash dest-dir "pdf"))

(defn parse-publish-date-from-string [strings]
  (let [regex #"(\d*),Vol (.*\d*),Issue( )?(\d{1,2})?"
        get-vals (fn [[_ year vol _ issue]] [year vol issue])]
        (map #(into [] (map util/str->num (get-vals (re-find regex %)))) strings)))

(defn get-current-issue [pub-num]
  (let [url (clojure.java.io/as-url (str journal-info-url pub-num))
        parsed-url (net.cgrand.enlive-html/html-resource url)
        sorted-issues (->> (net.cgrand.enlive-html/select parsed-url [:table [:td]])
                           (map #(first (:content %)))
                           (parse-publish-date-from-string)
                           (filter seq)
                           (filter (fn [j] (every? #(not (nil? %)) j)))
                           (sort)
                           (reverse))]
    (nth (first sorted-issues) 2)))

(defn make-journal-file-title [title]
  (str (-> title
         .toLowerCase
         (.replace " " "_")
         (.replace "," "")
         (.replace "/" "_")
         (.replace "&" "and"))))

(defn make-journal-file-name [journal]
  (let [{:keys [title current-vol current-issue]} journal]
    (str 
      (make-journal-file-title title)
      "_vol"
      (format "%02d" current-vol) 
      "_issue"
      (format "%02d" current-issue))))

(defn outdated-journal? [downloaded journal]
  (let [title (make-journal-file-title (:title journal))]
    (print (format "%-60s" (:title journal)))
    (flush)
    (if (not (contains? downloaded (make-journal-file-name journal)))
      (do (println "Outdated") true)
      (do (println "Up to date") false))))

(defn get-outdated-journals 
  "Returns all journals for which the latest issue is not in the destination
  directory."
  [{dest-dir :dest-dir
    journals :ieee-journals}]
  (let [downloaded-journals (get-downloaded-journals dest-dir)
        current-journals-info (pmap #(assoc % :current-issue 
                                            (get-current-issue (:pub-num %))) 
                                    (get-journal-urls journals))]
    (doall (->> current-journals-info
                (filter #(outdated-journal? downloaded-journals %))
                (map #(assoc % :type :ieee))
                (into [])))))

(defn get-pdf-links [] 
  (find-elements {:tag :a, :text "PDF"}))

(defn move-and-rename-downloaded-pdf [{:keys [download-dir temp-dir]} page-num pdf-count]
  (.renameTo (first (file-utils/get-files-with-extension download-dir "pdf")) 
             (clojure.java.io/file (format "%s/%02d%02d.pdf" temp-dir 
                                           page-num pdf-count))))

(defn download-pdf [download-dir pdf-link]
  (util/try-times 3 3000 (click pdf-link))
  (journal/wait-for-download-to-start download-dir)
  (back)
  (journal/wait-for-download-to-complete download-dir))

(defn wait-for-pdf-links-to-load [count-fn num]
  (while (< (count-fn) num)))

(defn get-page-pdfs [{:keys [download-dir] :as config} page-num]
 (println "Fetching PDFs from page")
 (let [num-links (count (get-pdf-links))]
   (println "Found" num-links "links.")
   (doseq [pdf-count (range num-links)]
     (do (wait-for-pdf-links-to-load #(count (get-pdf-links)) num-links)
         (let [pdf-links (get-pdf-links)
               current-pdf-link (nth pdf-links pdf-count)]
           (println "Fetching PDF" (str (inc pdf-count) "/" (count pdf-links)) 
                    "on page" page-num ".")
           (download-pdf download-dir current-pdf-link)
           (move-and-rename-downloaded-pdf config page-num pdf-count))))))

(defn get-journal-pdfs
  "Fetches all PDFs on all pages for the current issue of a journal"
  ([config] (get-journal-pdfs config 2))
  ([config next-page]
   (get-page-pdfs config (- next-page 1))
   (let [next-page-link (find-element {:tag :a, :text (str next-page)})]
     (when (:webelement next-page-link)
       (click next-page-link)
       (recur config (inc next-page))))))

(defn merge-and-move-pdfs [{:keys [temp-dir dest-dir]} filename]
  (let [merger (PDFMergerUtility.)]
    (doall (map #(.addSource merger %) (sort (file-utils/get-files-with-extension temp-dir "pdf"))))
    (.setDestinationFileName merger (str dest-dir "/" filename ".pdf"))
    (.mergeDocuments merger)))

(defn navigate-to-journal [journal]
  (to (str journal-url (:pub-num journal))))

(defn fetch-journal [config journal]
  (navigate-to-journal journal)
  (get-journal-pdfs config)
  (merge-and-move-pdfs config (make-journal-file-name journal)))
