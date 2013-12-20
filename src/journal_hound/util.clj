(ns journal-hound.util)

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
