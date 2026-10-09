(ns metapaste.log-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [metapaste.log :as log]))

(defn- parse-one-line
  "Runs f (which must log exactly one line), parses it as JSON."
  [f]
  (let [out (with-out-str (f))
        line (first (str/split-lines out))]
    (is (some? line) "expected exactly one log line")
    (json/read-str line :key-fn keyword)))

(deftest log-line-shape
  (let [m (parse-one-line #(log/info "config loaded" {:items 3}))]
    (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" (:ts m))
        "ts is ISO-8601 UTC")
    (is (= "info" (:level m)))
    (is (= "config loaded" (:msg m)))
    (is (= 3 (:items m)))))

(deftest log-levels
  (is (= "warn" (:level (parse-one-line #(log/warn "careful")))))
  (is (= "error" (:level (parse-one-line #(log/error "boom" {:code 7}))))))

(deftest with-request-id-binds-id
  (let [m (parse-one-line #(log/with-request-id "req-42"
                              (log/info "inside request")))]
    (is (= "req-42" (:request_id m))))
  (testing "no id bound -> no request_id field"
    (let [m (parse-one-line #(log/info "outside request"))]
      (is (nil? (:request_id m))))))

(deftest secrets-never-appear-in-log-lines
  (let [out (with-out-str (log/info "auth" {:admin_token "sekrit-token-value"}))]
    (is (not (str/includes? out "sekrit-token-value")))
    (is (str/includes? out "***REDACTED***"))))