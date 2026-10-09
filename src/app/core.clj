(ns app.core
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [metapaste.config :as config]
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
  config check    print the effective configuration as redacted JSON"))
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

(defn -main [& args]
  (let [{:keys [exit]} (run-command (vec args))]
    (flush)
    (System/exit exit)))