(ns metapaste.cors-test
  "M7 / Q55: CORS is disabled by default; cors_allow_origins (list) enables
   headers for exactly those origins; preflight OPTIONS is answered for
   allowed origins only. Driven through api/make-handler — the behavior is
   response-header policy, not a framework concern."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [metapaste.api :as api]))

(defn- handler-cfg [overrides]
  (merge {:min_ttl_seconds 60
          :max_ttl_seconds 31536000
          :default_ttl_seconds 259200
          :allow_never_expire false
          :path_prefix ""
          :rate_limit_rps 10
          :rate_limit_burst 20
          :max_item_bytes 65536}
         overrides))

(defn- handler
  "make-handler over a nil store: every request below takes a path that
   never touches storage (healthz, 401-before-store, preflight, 404/405)."
  [cfg-overrides]
  (api/make-handler {:store nil
                     :config (handler-cfg cfg-overrides)
                     :multipart-parser (fn [_] nil)}))

(defn- get* [h headers]
  (h {:request-method :get :uri "/healthz" :headers headers}))

(deftest disabled-by-default
  (let [h (handler {})]
    (testing "no CORS headers even when the request carries an Origin"
      (let [resp (get* h {"origin" "https://app.example"})]
        (is (= 200 (:status resp)))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))
        (is (nil? (get-in resp [:headers "Vary"])))))
    (testing "preflight OPTIONS falls through to 405, no CORS headers"
      (let [resp (h {:request-method :options :uri "/api/v1/items"
                     :headers {"origin" "https://app.example"
                               "access-control-request-method" "POST"}})]
        (is (= 405 (:status resp)))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))))))

(deftest allowed-origin-actual-request
  (let [h (handler {:cors_allow_origins ["https://app.example"]})]
    (testing "200 carries Access-Control-Allow-Origin and Vary: Origin"
      (let [resp (get* h {"origin" "https://app.example"})]
        (is (= 200 (:status resp)))
        (is (= "https://app.example"
               (get-in resp [:headers "Access-Control-Allow-Origin"])))
        (is (= "Origin" (get-in resp [:headers "Vary"])))))
    (testing "error responses carry the header too"
      (let [resp (h {:request-method :get :uri "/api/v1/items/k3v9qx2m4pta"
                     :headers {"origin" "https://app.example"}})]
        (is (= 401 (:status resp)))
        (is (= "https://app.example"
               (get-in resp [:headers "Access-Control-Allow-Origin"])))))
    (testing "origin match ignores case (origins are case-insensitive)"
      (let [resp (get* h {"origin" "https://App.Example"})]
        (is (= "https://App.Example"
               (get-in resp [:headers "Access-Control-Allow-Origin"])))))
    (testing "requests without Origin get Vary but no allow-origin"
      (let [resp (get* h {})]
        (is (= 200 (:status resp)))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))
        (is (= "Origin" (get-in resp [:headers "Vary"])))))))

(deftest disallowed-origin
  (let [h (handler {:cors_allow_origins ["https://app.example"]})]
    (testing "actual request from another origin: no Access-Control-Allow-Origin"
      (let [resp (get* h {"origin" "https://evil.example"})]
        (is (= 200 (:status resp)))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))
        (is (= "Origin" (get-in resp [:headers "Vary"])))))
    (testing "preflight from another origin falls through, no CORS headers"
      (let [resp (h {:request-method :options :uri "/api/v1/items"
                     :headers {"origin" "https://evil.example"
                               "access-control-request-method" "POST"}})]
        (is (= 405 (:status resp)))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Methods"])))))))

(deftest preflight-allowed-origin
  (let [h (handler {:cors_allow_origins ["https://app.example" "http://localhost:3000"]})
        resp (h {:request-method :options :uri "/api/v1/items"
                 :headers {"origin" "http://localhost:3000"
                           "access-control-request-method" "POST"
                           "access-control-request-headers" "authorization, content-type"}})]
    (is (= 204 (:status resp)))
    (is (= "http://localhost:3000"
           (get-in resp [:headers "Access-Control-Allow-Origin"])))
    (testing "Allow-Methods lists the route's real methods"
      (let [am (get-in resp [:headers "Access-Control-Allow-Methods"])]
        (is (str/includes? am "POST"))
        (is (str/includes? am "GET"))))
    (testing "Allow-Headers covers the API's request headers"
      (let [ah (get-in resp [:headers "Access-Control-Allow-Headers"])]
        (is (str/includes? ah "Authorization"))
        (is (str/includes? ah "Content-Type"))
        (is (str/includes? ah "X-Request-Id"))))
    (is (= "600" (get-in resp [:headers "Access-Control-Max-Age"])))
    (testing "Vary names every request header the decision used"
      (let [v (get-in resp [:headers "Vary"])]
        (is (str/includes? v "Origin"))
        (is (str/includes? v "Access-Control-Request-Method"))
        (is (str/includes? v "Access-Control-Request-Headers")))))
  (testing "preflight to an unknown path is not answered with 204"
    (let [h (handler {:cors_allow_origins ["https://app.example"]})
          resp (h {:request-method :options :uri "/nope"
                   :headers {"origin" "https://app.example"
                             "access-control-request-method" "GET"}})]
      (is (= 404 (:status resp))))))

(deftest preflight-without-origin-is-plain-options
  (let [h (handler {:cors_allow_origins ["https://app.example"]})
        resp (h {:request-method :options :uri "/api/v1/items"
                 :headers {"access-control-request-method" "POST"}})]
    (is (= 405 (:status resp)))
    (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))))
