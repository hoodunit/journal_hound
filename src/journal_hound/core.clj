(ns journal-hound.core
  (:gen-class))

(use 'clj-webdriver.taxi)
(require '[clj-webdriver.firefox :as ff])

(def driver (clj-webdriver.core/new-driver 
              {:browser :firefox 
               :profile 
                (doto (ff/new-profile) 
                  (ff/enable-extension "/home/ennus/code/clojure_test/webdriver.xpi")
                  ;; Auto-download PDFs to a specific folder
                  (ff/set-preferences {:browser.download.dir "/tmp/asdf", 
                                       :browser.download.folderList 2 
                                       :browser.helperApps.neverAsk.saveToDisk "application/pdf"}))}))

(set-driver! driver)

(defn get-periodicals
  "Fetches all periodicals"
  (input-text {:name "j_username"} "karinin1")
  (input-text {:name "j_password"} "password")
  (click {:value "Login"})
  (click {:text "IEEE Xplore"}) 
  (to "http://ieeexplore.ieee.org.libproxy.aalto.fi/servlet/opac?punumber=2") ; Computer
  (click {:src "/assets/img/btn.viewcontents.gif"})

(defn get-periodical-pdfs
  "Fetches all PDFs on all pages for the current issue of a periodical"
  ; TODO: doesn't work because it doesn't wait for PDFs to start dling or finish
  ([] (get-periodical-pdfs 2))
  ([next-page]
    ; get PDFs on this page
    (loop [pdf-count 1]
      (let [pdf-links (find-elements {:tag :a, :text "PDF"})]
        (if (< pdf-count (count pdf-links))
          (do (click (nth pdf-links pdf-count)) 
            (back)
            (recur (inc pdf-count))))))
    (let [next-page-link (find-element {:tag :a, :text (str next-page)})]
      (if (not (nil? (:webelement next-page-link)))
        (do (click next-page-link) (recur (inc next-page)))))))
