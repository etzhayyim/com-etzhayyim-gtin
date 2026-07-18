(ns gtin.coverage-test
  "Coverage metrics over the committed catalog."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.coverage :as cov]))

(deftest snapshot-counts-the-catalog
  (let [s (cov/snapshot)]
    (is (pos? (:products s)))
    (is (pos? (:brands s)))
    (is (pos? (:aliases s)))
    (is (pos? (:commodities s)))
    (is (pos? (:segments-reached s)))))

(deftest segment-coverage-includes-food-beverage
  ;; Coca-Cola / Nutella -> segment 50 (Food Beverage and Tobacco)
  (let [sc (cov/segment-coverage)]
    (is (contains? sc "50"))
    (is (pos? (get sc "50")))))

(deftest sourcing-breakdown-has-authoritative-and-representative
  ;; real public GTINs are :authoritative; BOM/teardown-derived are :representative
  (let [sb (cov/sourcing-breakdown)]
    (is (pos? (:authoritative sb 0)))
    (is (pos? (:representative sb 0)))))

(deftest seed-brands-are-unverified-honestly
  ;; seed brand-owners carry kabuto handles but owner-did-verified is false
  ;; until a live kakuto actor attests — the gap list should be non-empty.
  (let [unverified (cov/brands-without-verified-owner)]
    (is (seq unverified))
    (is (some #(= "coca-cola" (:gtin.brand/brandId %)) unverified))))
