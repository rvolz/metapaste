(ns app.core
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [metapaste.config :as config]
            [metapaste.http :as http]
            [metapaste.log :as log]))

(declare run-command)

(defn- config-check! []
  (let [cfg (config/load-config)]
    (doseq [w (:warnings cfg)]
      (log/warn w))
    (when (nil? (:admin_token cfg))
      (log/warn "admin_token is not set; admin endpoints will be disabled"))
    (println (json/write-str (-> cfg
                                 (dissoc :warnings)
                                 config/redact-config)
                             :escape-slash false))
    0))

(defn- dispatch [args]
  (cond
    (= ["config" "check"] args) (config-check!)

    :else
    (do (binding [*out* *err*]
          (println (str "unknown command: " (str/join " " args)))
          (println "usage: metapaste <command>
commands:
  config check    print the effective configuration as redacted JSON
  serve           start the HTTP API server (blocks)"))
        2)))

(defn run-command
  "Runs a CLI subcommand without touching the process streams.
  Returns {:exit n :out s :err s}."
  [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out
              *err* err]
      (let [exit (dispatch args)]
        (flush)
        {:exit exit :out (str out) :err (str err)}))))

(defn print-run-result!
  "D1 fix: send what run-command captured to the real process streams — out to
   stdout, err to stderr — so the built binary actually prints."
  [{:keys [out err]}]
  (print out)
  (flush)
  (binding [*out* *err*]
    (print err)
    (flush))
  nil)

(defn -main [& args]
  (if (= ["serve"] (vec args))
    ;; serve blocks forever with no capture binding, so its log lines hit
    ;; stdout directly and the accept loop owns the process lifetime
    (http/serve!)
    (let [result (run-command (vec args))]
      (print-run-result! result)
      (System/exit (:exit result)))))