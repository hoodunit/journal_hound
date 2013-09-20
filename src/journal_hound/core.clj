(ns journal-hound.core
  (:gen-class)
  (:use clj-webdriver.taxi
        [clojure.tools.logging :only (info error)]
        [clojure.java.io :only (file copy)])
  (:require clj-webdriver.firefox
            clojure-csv.core
            net.cgrand.enlive-html
            [journal-hound.file-utils :as util])
  (:import org.apache.pdfbox.util.PDFMergerUtility))

(def journal-hound-dir "/tmp/journal_hound")
(def temp-dir     (str journal-hound-dir "/temp"))
(def download-dir (str journal-hound-dir "/downloads"))
(def log-file     (str journal-hound-dir "/log"))
(def dest-dir "/home/ennus/Dropbox/periodicals")
(def webdriver-dir "/home/ennus/code/github_public/journal_hound/webdriver.xpi")
(def journal-list-url "http://ieeexplore.ieee.org/otherfiles/OPACJrnListIEEE.txt")
(def journal-info-url "http://ieeexplore.ieee.org/xpl/opacissue.jsp?punumber=")
(def journal-url "http://ieeexplore.ieee.org.libproxy.aalto.fi/xpl/mostRecentIssue.jsp?punumber=")
(def journals
  #{"Computer"
   "Software, IEEE"
   "Spectrum, IEEE"
   "Intelligent Systems, IEEE"
   "Network, IEEE"
   "Networking, IEEE/ACM Transactions on"
   "Internet Computing, IEEE"
   "Micro, IEEE"
   "Technology and Society Magazine, IEEE"
   "Mobile Computing, IEEE Transactions on"
   "Pervasive Computing, IEEE"
   "Security & Privacy, IEEE"
   "Software Engineering, IEEE Transactions on"
   "Consumer Electronics, IEEE Transactions on"
   "Network and Service Management, IEEE Transactions on"})

(defn try-times*
  "Executes thunk. If an exception is thrown, will retry after sleep-ms delay.
  At most n retries are done. If still some exception is thrown it is bubbled
  upwards in the call chain."
  [n sleep-ms thunk]
  (loop [n n]
    (if-let [result (try
                      [(thunk)]
                      (catch Exception e
                        (when (zero? n)
                          (throw e))))]
      (result 0)
      (do (Thread/sleep sleep-ms) (recur (dec n))))))

(defmacro try-times
  "Executes body. If an exception is thrown, will retry. At most n retries
  are done. If still some exception is thrown it is bubbled upwards in
  the call chain."
  [n sleep-ms & body]
  `(try-times* ~n ~sleep-ms (fn [] ~@body)))

(defn str->num [s]
  (try (if (re-matches (re-pattern "\\d+") s) (read-string s))
    (catch Exception e nil)))

(defn get-journal-urls []
  (with-open [rdr (clojure.java.io/reader journal-list-url)]
    (let [urls (->> (line-seq rdr)
                    ;(map #(first (clojure-csv.core/parse-csv %)))
                    (map clojure-csv.core/parse-csv)
                    (map first)
                    (map (fn [[title pub-num _ _ current _]] 
                           {:title title 
                            :pub-num (str->num pub-num) 
                            :current-vol (str->num current)}))
                    (filter #(contains? journals (:title %))))]
      (dorun urls)
      urls)))

(defn get-downloaded-journals []
  (util/get-files-with-extension-as-hash dest-dir "pdf"))

(defn parse-publish-date-from-string [strings]
  (let [regex #"(\d*),Vol (.*\d*),Issue( )?(\d{1,2})?"
        get-vals (fn [[_ year vol _ issue]] [year vol issue])]
        (map #(into [] (map str->num (get-vals (re-find regex %)))) strings)))

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
      (do (println "Recent") false))))

(defn get-outdated-journals
  "Returns all journals for which the latest issue is not in the destination
  directory."
  []
  (let [downloaded-journals (get-downloaded-journals)
        current-journals-info (pmap #(assoc % :current-issue 
                                            (get-current-issue (:pub-num %))) 
                                    (get-journal-urls))]
    (doall (filter #(outdated-journal? downloaded-journals %) current-journals-info))))

(defn request-user-pass []
  (let [get-input (fn [p] (print p) (flush) (read-line))
       get-pass (fn [p] (print p) (flush) (read-line))]
       ;get-pass (fn [p] (print p) (flush) (apply str (. (. System console) readPassword)))]
    [(get-input "Username: ") (get-pass "Password: ")]))

(defn wait-for-download-to-start [] 
  (while (empty? (util/get-files-with-extension download-dir "pdf"))))

(defn wait-for-download-to-complete [] 
  (while (or (< (count (util/get-files-with-extension download-dir "pdf")) 1)
             (not (empty? (util/get-files-with-extension download-dir "part"))))))

(defn get-pdf-links [] 
  (find-elements {:tag :a, :text "PDF"}))

(defn move-and-rename-downloaded-pdf [page-num pdf-count]
  (.renameTo (first (util/get-files-with-extension download-dir "pdf")) 
             (clojure.java.io/file (format "%s/%02d%02d.pdf" temp-dir 
                                           page-num pdf-count))))

(defn download-pdf [pdf-link]
  (try-times 3 3000 (click pdf-link))
  (wait-for-download-to-start)
  (back)
  (wait-for-download-to-complete))

(defn wait-for-pdf-links-to-load [count-fn num]
  (while (< (count-fn) num)))

(defn get-page-pdfs [page-num]
 (println "Fetching PDFs from page")
 (let [num-links (count (get-pdf-links))]
   (println "Found" num-links "links.")
   (doseq [pdf-count (range num-links)]
     (do (wait-for-pdf-links-to-load #(count (get-pdf-links)) num-links)
         (let [pdf-links (get-pdf-links)
               current-pdf-link (nth pdf-links pdf-count)]
           (println "Fetching PDF" (str (inc pdf-count) "/" (count pdf-links)) 
                    "on page" page-num ".")
           (download-pdf current-pdf-link)
           (move-and-rename-downloaded-pdf page-num pdf-count))))))

(defn get-journal-pdfs
  "Fetches all PDFs on all pages for the current issue of a journal"
  ([] (get-journal-pdfs 2))
  ([next-page]
   (get-page-pdfs (- next-page 1))
   (let [next-page-link (find-element {:tag :a, :text (str next-page)})]
     (if-not (nil? (:webelement next-page-link))
       (do (click next-page-link) (recur (inc next-page)))))))

(defn merge-pdfs-with-name [journal-name]
  (let [merger (PDFMergerUtility.)]
    (doall (map #(.addSource merger %) (sort (util/get-files-with-extension temp-dir "pdf"))))
    (.setDestinationFileName merger (str dest-dir "/" journal-name ".pdf"))
    (.mergeDocuments merger)))

(defn login [user pass]
   (input-text {:name "j_username"} user)
   (input-text {:name "j_password"} pass)
   (click {:tag :button, :text "Login"})
   (try (click {:text "IEEE Xplore"})
     (catch NullPointerException e
       (println "Authentication failed.")
       (apply login (request-user-pass)))))

(defn start-browser []
  (set-driver! (clj-webdriver.core/new-driver 
                {:browser :firefox :profile 
                 (doto (clj-webdriver.firefox/new-profile) 
                   (clj-webdriver.firefox/enable-extension webdriver-dir)
                   ;; Auto-download PDFs to a specific folder
                   (clj-webdriver.firefox/set-preferences 
                    {:browser.download.dir download-dir, 
                     :browser.download.folderList 2 
                     :browser.helperApps.neverAsk.saveToDisk "application/pdf"
                     :pdfjs.disabled true}))})))

(defn initialize-browser []
   (let [[user pass] (request-user-pass)]
     (println "Creating temporary directories if necessary.")
     (util/create-directories journal-hound-dir temp-dir download-dir)
     (println "Starting browser.")
     (start-browser)
     (println "Logging in.")
     (to "http://login.libproxy.aalto.fi/")
     (login user pass)))
    
(defn empty-temp-directories []
  (println "Emptying temporary directories.")
  (util/empty-directory temp-dir)
  (util/empty-directory download-dir))

(defn navigate-to-journal [journal]
  (to (str journal-url (:pub-num journal))))

(defn fetch-journal [journal]
  (println "Fetching latest" (:title journal) "journal.")
  (empty-temp-directories)
  (navigate-to-journal journal)
  (get-journal-pdfs)
  (merge-pdfs-with-name (make-journal-file-name journal)))

(defn update-journals [outdated]
  (doseq [journal outdated] (fetch-journal journal)))

(defn update-outdated-journals
  "Fetches all journals that are outdated."
  []
   (println "Checking for outdated journals.")
   (let [outdated (get-outdated-journals)]
     (if (empty? outdated)
       (println "All journals are up to date.")
       (do
         (println "Found" (count outdated) "outdated journals:")
         (doseq [j outdated] (println "  " (:title j)))
         (initialize-browser)
         (update-journals outdated)
         (println "All journals have been updated.")))))

(defn -main []
  (update-outdated-journals))

