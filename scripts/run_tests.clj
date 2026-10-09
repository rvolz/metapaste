;; Test runner: discovers every test/**/*_test.clj namespace, runs it through
;; clojure.test, and exits nonzero on any failure or error.
(require 'clojure.test 'clojure.java.io '[clojure.string :as str])

(def test-root (clojure.java.io/file "test"))

(defn- test-ns-name
  "test/metapaste/foo_test.clj -> metapaste.foo-test"
  [f]
  (let [p (.getPath f)
        rel (subs p (inc (count (.getPath test-root))))
        base (subs rel 0 (- (count rel) 4))]
    (symbol (-> base (str/replace "/" ".") (str/replace "_" "-")))))

(def namespaces
  (->> (file-seq test-root)
       (filter #(.isFile %))
       (filter #(re-matches #".*_test\.clj" (.getName %)))
       (map test-ns-name)
       (sort-by str)
       seq))

(when (nil? namespaces)
  (binding [*out* *err*] (println "error: no test namespaces found under test/"))
  (System/exit 1))

(doseq [n namespaces]
  (try
    (require n)
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "error: failed to load " n ": " (or (ex-message t) t))))
      (System/exit 1))))

(let [summary (apply clojure.test/run-tests namespaces)]
  (flush)
  (System/exit (if (and (zero? (:fail summary)) (zero? (:error summary))) 0 1)))