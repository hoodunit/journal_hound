(defproject journal-hound "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"] 
                 [clj-webdriver "0.6.0"]
                 [org.apache.pdfbox/pdfbox "1.6.0"]
                 [clojure-csv/clojure-csv "2.0.0-alpha1"]
                 [enlive "1.0.0"]
                 [clj-http "1.1.2"]]
  :main journal-hound.core)
