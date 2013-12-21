(ns journal-hound.core
  (:gen-class)
  (:require [clj-webdriver.taxi :refer :all]
            clj-webdriver.firefox
            [journal-hound.file-utils :as file-utils]
            [journal-hound.acm :as config]
            [journal-hound.ieee :as ieee]
            [journal-hound.acm :as acm]))

(def journal-hound-dir "/tmp/journal_hound")
(def temp-dir     (str journal-hound-dir "/temp"))
(def download-dir (str journal-hound-dir "/downloads"))
(def log-file     (str journal-hound-dir "/log"))

(def config (-> (load-file "config.clj")
                (assoc :temp-dir temp-dir
                       :download-dir download-dir
                       :log-file log-file)))

(defn get-outdated-journals []
  (let [outdated (into (acm/get-outdated-journals config)
                       (ieee/get-outdated-journals config))]
    (println "Found" (count outdated) "outdated journals:")
    (doseq [j outdated] (println "  " (:title j)))
    outdated))

(defn request-user-pass []
  (let [get-input (fn [p] (print p) (flush) (read-line))
       get-pass (fn [p] (print p) (flush) (read-line))]
       ;get-pass (fn [p] (print p) (flush) (apply str (. (. System console) readPassword)))]
    [(get-input "Username: ") (get-pass "Password: ")]))

(defn login [user pass]
   (input-text {:name "j_username"} user)
   (input-text {:name "j_password"} pass)
   (click {:tag :button, :text "Login"}))

(defn start-browser []
  (set-driver! (clj-webdriver.core/new-driver 
                {:browser :firefox :profile 
                 (doto (clj-webdriver.firefox/new-profile) 
                   (clj-webdriver.firefox/enable-extension "webdriver.xpi")
                   ;; Auto-download PDFs to a specific folder
                   (clj-webdriver.firefox/set-preferences 
                    {:browser.download.dir download-dir, 
                     :browser.download.folderList 2 
                     :browser.helperApps.neverAsk.saveToDisk "application/pdf"
                     :pdfjs.disabled true}))})))

(defn initialize-browser []
   (let [[user pass] (request-user-pass)]
     (println "Creating temporary directories if necessary.")
     (file-utils/create-directories journal-hound-dir temp-dir download-dir)
     (println "Starting browser.")
     (start-browser)
     (println "Logging in.")
     (to "http://login.libproxy.aalto.fi/")
     (login user pass)))
    
(defn empty-temp-directories []
  (println "Emptying temporary directories.")
  (file-utils/empty-directory temp-dir)
  (file-utils/empty-directory download-dir))

(defmulti fetch-journal :type)

(defmethod fetch-journal :ieee [journal]
  (ieee/fetch-journal config journal))

(defmethod fetch-journal :acm [journal]
  (acm/fetch-journal config journal))

(defn update-journals [outdated]
  (doseq [journal outdated] (fetch-journal journal)))

(defn update-outdated-journals [outdated]
  (initialize-browser)
  (doseq [journal outdated]
    (println "Fetching latest" (:title journal) "journal.")
    (empty-temp-directories)
    (fetch-journal journal))
  (close)
  (println "All journals have been updated."))

(defn check-and-update-outdated-journals []
   (println "Checking for outdated journals.")
   (let [outdated (get-outdated-journals)]
     (if (empty? outdated)
       (println "All journals are up to date.")
       (update-outdated-journals outdated))))

(defn -main []
  (check-and-update-outdated-journals))
