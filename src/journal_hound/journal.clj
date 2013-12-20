(ns journal-hound.journal
  (require [journal-hound.file-utils :as file-utils]))

(defn wait-for-download-to-start [download-dir] 
  (while (empty? (file-utils/get-files-with-extension download-dir "pdf"))))

(defn wait-for-download-to-complete [download-dir] 
  (while (or (not (file-utils/dir-contains-pdfs? download-dir))
             (file-utils/dir-contains-incomplete-downloads? download-dir))
    (Thread/sleep 200)))
