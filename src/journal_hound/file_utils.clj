(ns journal-hound.file-utils)

(defn get-files-with-extension [dir extension]
  (filter #(and (.isFile %) (.endsWith (.toLowerCase (.getName %)) (str "." extension))) 
          (file-seq (clojure.java.io/file dir))))

(defn get-files-with-extension-as-hash [dir extension]
  (into #{} (map #(-> % .getName (.replace (str "." extension) "")) 
                 (get-files-with-extension dir extension))))

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

(defn create-directories [& dirs]
  (doseq [d dirs]
     (.mkdir (java.io.File. d))))
