(ns gtin.brand-registry-test
  "Brand registry: register / match / by-id / owner-verified."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.brand-registry :as br]))

(deftest match-brand-resolves-by-name-and-slug
  (is (= "coca-cola" (:gtin.brand/brandId (first (br/match-brand "Coca-Cola")))))
  (is (= "coca-cola" (:gtin.brand/brandId (first (br/match-brand "coca-cola")))))
  (is (= "coca-cola" (:gtin.brand/brandId (first (br/match-brand "  COCA-COLA  ")))))
  (is (empty? (br/match-brand "nonexistent-brand-xyz"))))

(deftest brand-by-id
  (let [b (br/brand-by-id "coca-cola")]
    (is b)
    (is (= "Coca-Cola" (:gtin.brand/name b))))
  (is (nil? (br/brand-by-id "nope"))))

(deftest register-brand-issues-tx-data-with-slug
  (let [tx (br/register-brand {:name "Acme Corp" :country "US"
                               :owner-handle "org.corp.us.acme"})]
    (is (= "acme-corp" (:gtin.brand/brandId tx)))
    (is (= "Acme Corp" (:gtin.brand/name tx)))
    (is (= "org.corp.us.acme" (:gtin.brand/owner-handle tx)))
    (is (= "US" (:gtin.brand/country tx)))
    (is (false? (:gtin.brand/owner-did-verified tx)))
    (is (= :synthesized (:gtin.brand/sourcing tx)))))

(deftest register-brand-rejects-blank-name
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (br/register-brand {:name ""}))))

(deftest owner-verified-defaults-false-for-seed-brands
  ;; Seed brand-owners carry kabuto handles but are NOT DID-verified until a live
  ;; kakuto actor attests — verify the catalog rows reflect this honestly.
  (is (false? (br/owner-verified? "coca-cola"))))
