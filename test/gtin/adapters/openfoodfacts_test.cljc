(ns gtin.adapters.openfoodfacts-test
  "Open Food Facts adapter: OFF record → 4-collection identity tx-data."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.adapters.openfoodfacts :as off]))

(def nutella {"code" "3017620422003"
              "product_name" "Nutella"
              "brands" "Ferrero"})

(deftest nutella-record-normalizes-to-3-collections
  (let [r    (off/normalize-record nutella)
        prod (first (:product r))
        br   (first (:brand r))]
    (is (= 1 (count (:product r))))
    (is (= "03017620422003" (:gtin.product/gtin prod)))
    (is (= :ean (:gtin.product/code-type prod)))
    (is (= :ean-13 (:gtin.product/source-format prod)))
    (is (= "Nutella" (:gtin.product/name prod)))
    (is (= :food-beverage (:gtin.product/sector prod)))
    (is (= :representative (:gtin.product/sourcing prod)))
    (is (= "did:web:etzhayyim.com:actor:gtin:product:ean_03017620422003"
           (:gtin.product/did prod)))
    (is (= "ferrero" (:gtin.product/brand prod)))
    (testing "GTIN-14 + 13-digit EAN alias both emitted"
      (is (some (fn [a] (and (= :gtin (:gtin.alias/codeType a))
                             (= "03017620422003" (:gtin.alias/codeValue a))))
                (:alias r)))
      (is (some (fn [a] (and (= :ean (:gtin.alias/codeType a))
                             (= "3017620422003" (:gtin.alias/codeValue a))))
                (:alias r))))
    (testing "brand row"
      (is (= "ferrero" (:gtin.brand/brandId br)))
      (is (= "Ferrero" (:gtin.brand/name br))))))

(deftest bad-or-missing-check-digit-is-skipped
  (is (empty? (off/normalize-record {"code" "3017620422000"})))
  (is (empty? (off/normalize-record {"code" ""})))
  (is (empty? (off/normalize-record {})))
  (is (empty? (off/normalize-record {"product_name" "No code"}))))

(deftest category-omitted-bom-out-of-scope
  (let [r (off/normalize-record (assoc nutella "ingredients" [{"id" "en:sugar"}]))]
    (is (nil? (:category r)))
    (is (nil? (:gtin.product/category (first (:product r)))))
    (is (nil? (:material r)))
    (is (nil? (:bom.edge r)))))

(deftest normalize-dataset-skips-invalid-keeps-valid
  (let [ds [{"code" "3017620422003" "brands" "Ferrero"}
            {"code" "BADCODE"}
            {"code" "5449000000996" "product_name" "Coca-Cola" "brands" "Coca-Cola"}]
        r (off/normalize-dataset ds)]
    (is (= 2 (count (:product r))))
    (is (some (fn [p] (= "03017620422003" (:gtin.product/gtin p))) (:product r)))
    (is (some (fn [p] (= "05449000000996" (:gtin.product/gtin p))) (:product r)))))

(deftest jan-prefixed-record-emits-jan-and-ean-aliases
  (let [r (off/normalize-record {"code" "4902102139496" "product_name" "Coke JP"})
        aliases (:alias r)]
    (is (some (fn [a] (= :jan (:gtin.alias/codeType a))) aliases))
    (is (some (fn [a] (= :ean (:gtin.alias/codeType a))) aliases))))
