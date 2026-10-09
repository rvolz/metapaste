(ns metapaste.names-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [metapaste.names :as names]))

(deftest random-name-alphabet-and-length
  (testing "12 chars, lowercase, Crockford base32 (no l/o/u)"
    (dotimes [_ 200]
      (let [n (names/random-name)]
        (is (= 12 (count n)))
        (is (= n (str/lower-case n)))
        (is (every? (fn [c] (str/includes? names/alphabet (str c))) n))
        (is (not (re-find #"[lou]" n)))))))

(deftest normalize-is-case-insensitive
  (is (= "k3v9qx2m4pta" (names/normalize "K3V9QX2M4PTA")))
  (is (= "k3v9qx2m4pta" (names/normalize "k3v9qx2m4pta")))
  (is (= "abc0123hjkmn" (names/normalize "AbC0123HjKmN"))))

(deftest unique-name-returns-first-fresh-name
  (let [seen (atom nil)
        ret (names/unique-name (fn [n] (reset! seen n) :done))]
    (is (= :done ret))
    (is (= 12 (count @seen)))
    (is (every? #(str/includes? names/alphabet (str %)) @seen))))

(deftest unique-name-retries-taken-names-then-succeeds
  (let [calls (atom 0)]
    (is (= "fresh-name-1"  ; f's return value passes through
           (names/unique-name
            (fn [_]
              (swap! calls inc)
              (if (<= @calls 3)
                (throw (names/taken "collision"))
                "fresh-name-1")))))
    (is (= 4 @calls))))

(deftest unique-name-errors-after-five-retries
  (let [calls (atom 0)
        e (try
            (names/unique-name
             (fn [_]
               (swap! calls inc)
               (throw (names/taken "collision"))))
            (catch Throwable t t))]
    (is (some? e))
    (is (= :metapaste.names/exhausted (:type (ex-data e))))
    (is (= 6 @calls))))

(deftest unique-name-does-not-retry-non-collision-errors
  (let [calls (atom 0)
        e (try
            (names/unique-name
             (fn [_]
               (swap! calls inc)
               (throw (ex-info "boom" {:other true}))))
            (catch Throwable t t))]
    (is (= "boom" (ex-message e)))
    (is (= 1 @calls))))
