(ns metapaste.cli-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [app.core :as app]
            [metapaste.config :as config]))

(deftest config-check-exits-zero-and-redacts
  (with-redefs [config/load-config
                (fn [& _] {:admin_token "leak-me-please"
                           :default_ttl_seconds 259200})]
    (let [{:keys [exit out err]} (app/run-command ["config" "check"])]
      (is (zero? exit))
      (is (str/blank? err))
      (is (not (str/includes? out "leak-me-please"))
          "the secret value must never appear in output")
      (is (str/includes? out "***REDACTED***"))
      (let [m (json/read-str out :key-fn keyword)]
        (is (= "***REDACTED***" (:admin_token m)))
        (is (= 259200 (:default_ttl_seconds m)))))))

(deftest unknown-command-is-an-error
  (let [{:keys [exit err]} (app/run-command ["frobnicate"])]
    (is (= 2 exit))
    (is (str/includes? err "frobnicate"))))