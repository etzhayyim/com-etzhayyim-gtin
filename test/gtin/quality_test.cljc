(ns gtin.quality-test
  "Quality: detect-duplicate / review-packaging-split."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.catalog :as cat]
            [gtin.quality :as q]))

(deftest catalog-has-no-duplicate-gtins
  ;; seed/merged catalog: each canonical GTIN-14 appears exactly once
  (is (empty? (q/detect-duplicate-gtins))))

(deftest catalog-has-no-duplicate-aliases
  ;; a barcode never resolves to two trade items in the seed catalog
  (is (empty? (q/detect-duplicate-aliases))))

(deftest detect-duplicate-gtins-finds-injected-dup
  (let [fake-prod {:gtin.product/productId "DUP1" :gtin.product/gtin "05449000000996"}
        snap (update (cat/snapshot) :product #(conj % fake-prod))]
    (is (some #(= "05449000000996" (:gtin %)) (q/detect-duplicate-gtins snap)))))

(deftest distinct-gtins-require-split
  (let [r (q/review-packaging-split "05449000000996" "03017620422003")]
    (is (true? (:split-required? r)))
    (is (= "distinct GTINs are always distinct DIDs" (:reason r)))))

(deftest missing-product-no-split
  (let [r (q/review-packaging-split "05449000000996" "DOES-NOT-EXIST")]
    (is (false? (:split-required? r)))
    (is (= "one or both products not found" (:reason r)))))
