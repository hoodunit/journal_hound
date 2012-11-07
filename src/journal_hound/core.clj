(ns journal-hound.core
  (:gen-class)
  (:use clj-webdriver.taxi
        [clojure.tools.logging :only (info error)]
        [clojure.java.io :only (file copy)])
  (:require clj-webdriver.firefox
            [clj-commons-exec :as exec])
  (:import org.pdfbox.util.PDFMergerUtility))

(def temp-dir "/tmp/journal_hound/temp")
(def download-dir "/tmp/journal_hound/downloads")
(def log-file "/tmp/journal_hound/log")
(def dest-dir "/home/ennus/Dropbox/periodicals")
(def webdriver-dir "/home/ennus/code/journal_hound/webdriver.xpi")
(def journal-list-url "http://ieeexplore.ieee.org/otherfiles/OPACJrnListIEEE.txt")

(comment
(import '(java.net URL)
        '(java.lang StringBuilder)
        '(java.io BufferedReader InputStreamReader))

(defn fetch-url
  "Return the web page as a string."
  [address]
  (let [url (URL. address)]
    (with-open [stream (. url (openStream))]
      (let [buf (BufferedReader. (InputStreamReader. stream))]
        (apply str (line-seq buf))))))
)

(defn request-user-pass []
  (let [get-input (fn [p] (print p) (flush) (read-line))
        get-pass (fn [p] (print p) (flush) (read-line))]
       ; get-pass (fn [p] (print p) (flush) (apply str (. (. System console) readPassword)))]
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

(defn get-files-with-extension [dir extension]
  (filter #(and (.isFile %) (.endsWith (.toLowerCase (.getName %)) (str "." extension))) 
          (file-seq (clojure.java.io/file dir))))

(defn get-page-pdfs [page-num]
  (loop [pdf-count 0]
    (let [pdf-links (find-elements {:tag :a, :text "PDF"})]
      (if (< pdf-count (count pdf-links))
        (do 
          (info "Page: " page-num " pdf: " pdf-count " links: " (count pdf-links))
          (click (nth pdf-links pdf-count)) 
          ; wait for download to start
          (while (empty? (get-files-with-extension download-dir "pdf")))
          (back)
          ; wait for download to complete
          (while (or (< (count (get-files-with-extension download-dir "pdf")) 1)
                     (not (empty? (get-files-with-extension download-dir "part")))))
          (let [pdf-file (first (get-files-with-extension download-dir "pdf"))]
            (.renameTo pdf-file (clojure.java.io/file 
                                  (format "%s/%02d%02d.pdf" temp-dir 
                                          page-num pdf-count))))
          (recur (inc pdf-count)))))))

(defn get-periodical-pdfs
  "Fetches all PDFs on all pages for the current issue of a periodical"
  ; TODO: doesn't work because it doesn't wait for PDFs to start dling or finish
  ([] (get-periodical-pdfs 2))
  ([next-page]
   (get-page-pdfs (- next-page 1))
   (let [next-page-link (find-element {:tag :a, :text (str next-page)})]
     (if (not (nil? (:webelement next-page-link)))
       (do (click next-page-link) (recur (inc next-page)))))))

(defn merge-documents [journal-name]
  (let [merger (PDFMergerUtility.)]
    (doall (map #(.addSource merger %) (sort (get-files-with-extension temp-dir "pdf"))))
    (.setDestinationFileName merger (str dest-dir "/" journal-name ".pdf"))
    (.mergeDocuments merger)))
    
(defn get-periodicals []
  "Fetches all periodicals"
  (println "Getting username and password...")
  (let [[username password] ;["karinin1" "asdf"]]
        (request-user-pass)]
    (println "Emptying directories")
    (.mkdir (java.io.File. temp-dir))
    (.mkdir (java.io.File. download-dir))
    (empty-directory temp-dir)
    (empty-directory download-dir)
    (println "Setting driver")
    (set-driver! (clj-webdriver.core/new-driver 
                   {:browser :firefox :profile 
                    (doto (clj-webdriver.firefox/new-profile) 
                      (clj-webdriver.firefox/enable-extension webdriver-dir)
                      ;; Auto-download PDFs to a specific folder
                      (clj-webdriver.firefox/set-preferences 
                           {:browser.download.dir download-dir, 
                            :browser.download.folderList 2 
                            :browser.helperApps.neverAsk.saveToDisk "application/pdf"}))}))
    (println "Logging in")
    ;(to "http://login.libproxy.aalto.fi/")
    (to "http://ieeexplore.ieee.org.libproxy.aalto.fi/servlet/opac?punumber=2") ; Computer
    (input-text {:name "j_username"} username)
    (input-text {:name "j_password"} password)
    (click {:value "Login"})
    ;(click {:text "IEEE Xplore"}) 
    (click {:src "/assets/img/btn.viewcontents.gif"})
    (get-periodical-pdfs)
    (merge-documents "computer")))

