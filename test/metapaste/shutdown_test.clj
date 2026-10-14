(ns metapaste.shutdown-test
  "M7 / spec section 14 risk 7: graceful shutdown. Unit: the hook stops the
   cleanup worker, stops every listener with a bounded drain, then exits 0.
   Integration: a real child process (`jolt -m app.core serve`) is SIGTERMed
   and must exit 0 with its worker thread gone — best effort per the milestone
   prompt; if the child cannot start (no jolt), the test FAILS loudly rather
   than skipping silently."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [metapaste.cleanup :as cleanup]
            [metapaste.http :as http]
            [metapaste.storage :as storage])
  (:import [java.io File]
           [java.lang ProcessBuilder]
           [java.lang.ProcessBuilder$Redirect]))

;; --- unit: the hook itself ----------------------------------------------------

(deftest hook-stops-components-in-order
  (let [dir (doto (File. "target/test-shutdown") (.delete) (.mkdirs))
        store (storage/open! (.getAbsolutePath dir))
        calls (atom [])
        hook (http/shutdown-hook! {:store store
                                   :listeners []
                                   :cleanup-stop! (fn [] (swap! calls conj :cleanup))
                                   :exit-fn (fn [code] (swap! calls conj [:exit code]))})]
    (try
      (hook)
      (is (= [:cleanup [:exit 0]] @calls))
      (testing "second invocation is a no-op — hooks run exactly once"
        (hook)
        (is (= [:cleanup [:exit 0]] @calls)))
      (finally
        (try (storage/close-storage store) (catch Throwable _ nil))
        (doseq [f (reverse (file-seq dir))] (.delete ^File f))))))

(deftest hook-stops-listeners-before-exit
  (let [dir (doto (File. "target/test-shutdown2") (.delete) (.mkdirs))
        store (storage/open! (.getAbsolutePath dir))
        calls (atom [])
        stopped (fn [] (swap! calls conj :listener))
        hook (http/shutdown-hook! {:store store
                                   :listeners [stopped]
                                   :cleanup-stop! (fn [] (swap! calls conj :cleanup))
                                   :exit-fn (fn [code] (swap! calls conj [:exit code]))})]
    (try
      (hook)
      (is (= [:listener :cleanup [:exit 0]] @calls))
      (finally
        (try (storage/close-storage store) (catch Throwable _ nil))
        (doseq [f (reverse (file-seq dir))] (.delete ^File f))))))

;; --- integration: SIGTERM to a real child process -----------------------------

(defn- find-jolt
  "Absolute path of the jolt launcher, or nil when absent (the test then fails
   loudly)."
  []
  (let [on-path (System/getenv "PATH")]
    (when on-path
      (some (fn [dir]
              (let [f (io/file dir "jolt")]
                (when (.canExecute f) (.getAbsolutePath f))))
            (str/split on-path (re-pattern File/pathSeparator))))))

(deftest sigterm-child-exits-zero
  (let [jolt (find-jolt)
        dir (doto (File. "target/test-sigterm") (.delete) (.mkdirs))
        out (io/file dir "serve.out")
        err (io/file dir "serve.err")]
    (is (some? jolt) "jolt launcher must be on PATH for the SIGTERM test")
    (when jolt
      (let [pb (doto (ProcessBuilder. [jolt "-m" "app.core" "serve"])
                 (.directory (io/file "."))
                 (.redirectOutput (ProcessBuilder$Redirect/to out))
                 (.redirectError (ProcessBuilder$Redirect/to err)))
            _ (doto (.environment pb)
                (.put "METAPASTE_DATA_DIR" (.getAbsolutePath (io/file dir "data")))
                (.put "METAPASTE_PORT" "0")
                (.put "METAPASTE_METRICS_PORT" "0")
                (.put "METAPASTE_CLEANUP_INTERVAL_S" "3600"))
            proc (.start pb)]
        (try
          ;; wait for the serve line on stdout (worker + hook installed by then)
          (let [deadline (+ (System/currentTimeMillis) 30000)]
            (loop []
              (cond
                (str/includes? (try (slurp out) (catch Exception _ ""))
                               "\"msg\":\"metapaste serving\"")
                (is true)

                (not (.isAlive proc))
                (is false (str "serve exited early with "
                               (try (.exitValue proc) (catch Exception _ "?"))
                               ": " (try (slurp err) (catch Exception _ ""))))

                (< (System/currentTimeMillis) deadline)
                (do (Thread/sleep 100) (recur))
                :else (is false "serve never printed its startup line"))))
          (.destroy proc)
          (let [waited (.waitFor proc 15 java.util.concurrent.TimeUnit/SECONDS)]
            (is waited "child must exit within 15s of SIGTERM")
            (when waited
              (is (zero? (.exitValue proc))
                  (str "exit status must be 0, got " (.exitValue proc)))))
          (testing "stdout shows the worker stopped and the hook exiting"
            (let [s (try (slurp out) (catch Exception _ ""))]
              (is (str/includes? s "\"msg\":\"shutting down\""))
              (is (str/includes? s "\"msg\":\"cleanup worker stopped\""))))
          (finally
            (when (.isAlive proc) (.destroyForcibly proc))
            (doseq [f (reverse (file-seq dir))] (.delete ^File f))))))))