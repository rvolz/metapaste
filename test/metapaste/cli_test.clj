(ns metapaste.cli-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [app.core :as app]
            [metapaste.config :as config]))

(deftest config-check-exits-zero-and-redacts
  (with-redefs [config/load-config
                (fn [& _] {:admin_token "leak-me-please"
                           :dev_api_key "dev-key-leak-value"
                           :default_ttl_seconds 259200})]
    (let [{:keys [exit out err]} (app/run-command ["config" "check"])]
      (is (zero? exit))
      (is (str/blank? err))
      (is (not (str/blank? out)) "output must be non-empty now that D1 is fixed")
      (is (not (str/includes? out "leak-me-please"))
          "the admin_token value must never appear in output")
      (is (not (str/includes? out "dev-key-leak-value"))
          "the dev_api_key value must never appear in output")
      (is (str/includes? out "***REDACTED***"))
      (let [m (json/read-str out :key-fn keyword)] ;; valid JSON, grep-level check
        (is (= "***REDACTED***" (:admin_token m)))
        (is (= "***REDACTED***" (:dev_api_key m)))
        (is (= 259200 (:default_ttl_seconds m)))))))

(deftest unknown-command-is-an-error
  (let [{:keys [exit err]} (app/run-command ["frobnicate"])]
    (is (= 2 exit))
    (is (str/includes? err "frobnicate"))
    (is (str/includes? err "serve") "usage text documents the serve subcommand")))

(deftest d1-run-command-output-reaches-the-caller
  ;; D1: -main discarded run-command's :out/:err, so the built binary printed
  ;; nothing. Both streams must land in the return map AND be printable
  ;; stdout/stderr by -main-style printing.
  (let [r (app/run-command ["config" "check"])
        o (java.io.StringWriter.)
        e (java.io.StringWriter.)]
    (is (not (str/blank? (:out r))))
    (binding [*out* o *err* e]
      (app/print-run-result! r))
    (testing "out goes to stdout, err to stderr"
      (is (= (:out r) (str o)))
      (is (= (:err r) (str e)))))
  (let [r (app/run-command ["frobnicate"])
        o (java.io.StringWriter.)
        e (java.io.StringWriter.)]
    (is (not (str/blank? (:err r))))
    (binding [*out* o *err* e]
      (app/print-run-result! r))
    (is (= (:err r) (str e)))
    (is (= (:out r) (str o)))))