(ns journal-hound.core
  (:gen-class)
  (:use clj-webdriver.taxi
        [clojure.tools.logging :only (info error)]
        [clojure.java.io :only (file copy)])
  (:require clj-webdriver.firefox
            clojure-csv.core
            net.cgrand.enlive-html)
  (:import org.apache.pdfbox.util.PDFMergerUtility))

(def journal-hound-dir "/tmp/journal_hound")
(def temp-dir     (str journal-hound-dir "/temp"))
(def download-dir (str journal-hound-dir "/downloads"))
(def log-file     (str journal-hound-dir "/log"))
(def dest-dir "/home/ennus/Dropbox/periodicals")
(def webdriver-dir "/home/ennus/code/github_public/journal_hound/webdriver.xpi")
(def journal-list-url "http://ieeexplore.ieee.org/otherfiles/OPACJrnListIEEE.txt")
(def journal-info-url "http://ieeexplore.ieee.org/xpl/opacissue.jsp?punumber=")
(def journal-url "http://ieeexplore.ieee.org.libproxy.aalto.fi/servlet/opac?punumber=")
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
    (reduce (fn [a b] 
              (let [line (first (clojure-csv.core/parse-csv b))
                   [title pub-num _ end-year current issues-per-year] line]
                (if (contains? journals title) 
                  (conj a {:title title 
                           :pub-num (str->num pub-num)
                           :current-vol (str->num current)}) 
                  a)))
            [] 
            (line-seq rdr))))

(defn get-files-with-extension [dir extension]
  (filter #(and (.isFile %) (.endsWith (.toLowerCase (.getName %)) (str "." extension))) 
          (file-seq (clojure.java.io/file dir))))

(defn get-downloaded-journals []
  (into #{} (map #(-> % .getName (.replace ".pdf" "")) 
                 (get-files-with-extension dest-dir "pdf"))))

(defn get-current-issue [pub-num]
  (let [parsed-url (net.cgrand.enlive-html/html-resource 
                     (clojure.java.io/as-url (str journal-info-url pub-num)))
        date-elements (net.cgrand.enlive-html/select parsed-url [:table [:td]])
        reduce-fn (fn [a b] 
                    (let [[_ year vol _ issue]
                          (re-find #"(\d*),Vol (.*\d*),Issue( )?(\d{1,2})?" 
                                   (first (:content b)))
                          date (into [] (map str->num [year vol issue]))]
                      (if (every? #(not (nil? %)) date)
                        (conj a date) 
                        a)))]
    (nth (last (reduce reduce-fn (sorted-set) date-elements)) 2)))

(defn get-file-title [title]
  (str (-> title
         .toLowerCase
         (.replace " " "_")
         (.replace "," "")
         (.replace "/" "_")
         (.replace "&" "and"))))

(defn get-file-name [j]
  (let [{:keys [title current-vol current-issue]} j]
    (str 
      (get-file-title title)
      "_vol"
      (format "%02d" current-vol) 
      "_issue"
      (format "%02d" current-issue))))

(defn outdated-journal? [downloaded journal]
  (let [title (get-file-title (:title journal))]
    (print (format "%-60s" (:title journal)))
    (flush)
    (if (not (contains? downloaded (get-file-name journal)))
      (do (println "Outdated") true)
      (do (println "Recent") false))))

(defn get-outdated-journals
  "Returns all journals for which the latest issue is not in the destination
  directory."
  []
  (let [downloaded-journals (get-downloaded-journals)]
    (doall (filter #(outdated-journal? downloaded-journals %)
                   (pmap #(assoc % :current-issue 
                                 (get-current-issue (:pub-num %))) (get-journal-urls))))))

(defn request-user-pass []
  (let [get-input (fn [p] (print p) (flush) (read-line))
       get-pass (fn [p] (print p) (flush) (read-line))]
       ;get-pass (fn [p] (print p) (flush) (apply str (. (. System console) readPassword)))]
    [(get-input "Username: ") (get-pass "Password: ")]))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents.
  Raise an exception if any deletion fails unless silently is true."
  [f & [silently]]
  (let [f (clojure.java.io/file f)]
    (if (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-file-recursively child silently)))
    (clojure.java.io/delete-file f silently)))

(defn empty-directory
  "Recursively delete all the files in f, but not f itself.
  Raise an exception if any deletion fails unless silently is true."
  [f & [silently]]
  (let [f (clojure.java.io/file f)]
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-file-recursively child silently)))))

(defn get-page-pdfs
  "Downloads all PDFs on the current page."
  [page-num]
  (println "Fetching PDFs from page" page-num)
  (let [get-pdf-links #(find-elements {:tag :a, :text "PDF"})
        num-links (count (get-pdf-links))]
    (println "Found" num-links "links.")
    (loop [pdf-count 0]
      (if (< pdf-count num-links)
        (do ; wait for all PDF links to load
          (while (< (count (get-pdf-links)) num-links))
          (let [pdf-links (get-pdf-links)]
            (println "Fetching PDF" (str (inc pdf-count) "/" (count pdf-links)) 
                     "on page" page-num ".")
            (try-times 3 3000 (click (nth pdf-links pdf-count)))
            ; wait for download to start
            (while (empty? (get-files-with-extension download-dir "pdf")))
            (back)
            ; wait for download to complete
            (while (or (< (count (get-files-with-extension download-dir "pdf")) 1)
                       (not (empty? (get-files-with-extension download-dir "part")))))
            ; move completed file
            (.renameTo (first (get-files-with-extension download-dir "pdf")) 
                       (clojure.java.io/file (format "%s/%02d%02d.pdf" temp-dir 
                                                     page-num pdf-count)))
            (recur (inc pdf-count))))))))

(defn get-journal-pdfs
  "Fetches all PDFs on all pages for the current issue of a journal"
  ([] (get-journal-pdfs 2))
  ([next-page]
   (get-page-pdfs (- next-page 1))
   (let [next-page-link (find-element {:tag :a, :text (str next-page)})]
     (if-not (nil? (:webelement next-page-link))
       (do (click next-page-link) (recur (inc next-page)))))))

(defn merge-documents [journal-name]
  (let [merger (PDFMergerUtility.)]
    (doall (map #(.addSource merger %) (sort (get-files-with-extension temp-dir "pdf"))))
    (.setDestinationFileName merger (str dest-dir "/" journal-name ".pdf"))
    (.mergeDocuments merger)))

(defn login [user pass]
   (input-text {:name "j_username"} user)
   (input-text {:name "j_password"} pass)
   (click {:value "Login"})
   (try (click {:text "IEEE Xplore"})
     (catch NullPointerException e
       (println "Authentication failed.")
       (apply login (request-user-pass)))))

(defn create-directories [& dirs]
  (doseq [d dirs]
     (.mkdir (java.io.File. d))))

(defn start-browser []
  (set-driver! (clj-webdriver.core/new-driver 
                {:browser :firefox :profile 
                 (doto (clj-webdriver.firefox/new-profile) 
                   (clj-webdriver.firefox/enable-extension webdriver-dir)
                   ;; Auto-download PDFs to a specific folder
                   (clj-webdriver.firefox/set-preferences 
                    {:browser.download.dir download-dir, 
                     :browser.download.folderList 2 
                     :browser.helperApps.neverAsk.saveToDisk "application/pdf"}))})))

(defn initialize-browser []
   (let [[user pass] (request-user-pass)]
     (println "Creating temporary directories if necessary.")
     (create-directories journal-hound-dir temp-dir download-dir)
     (println "Starting browser.")
     (start-browser)
     (println "Logging in.")
     (to "http://login.libproxy.aalto.fi/")
     (login user pass)))
    
(defn update-journals [outdated]
  (loop [remaining outdated]
    (println "Emptying temporary directories.")
    (empty-directory temp-dir)
    (empty-directory download-dir)
    (let [journal (first remaining)]
      (if-not (nil? journal)
        (do (println "Fetching latest" (:title journal) "journal.")
            (to (str journal-url (:pub-num journal)))
            (click {:src "/assets/img/btn.viewcontents.gif"})
            (get-journal-pdfs)
            (merge-documents (get-file-name journal))
            (recur (rest remaining)))))))

(defn update-outdated-journals
  "Fetches all journals that are outdated."
  ([]
   (println "Checking for outdated journals.")
   (let [outdated (get-outdated-journals)]
     (if (empty? outdated)
       (println "All journals are up to date.")
       (do
         (println "Found" (count outdated) "outdated journals:")
         (doseq [j outdated] (println "  " (:title j)))
         (initialize-browser)
         (update-journals outdated))))))

(defn -main []
  (update-outdated-journals))

