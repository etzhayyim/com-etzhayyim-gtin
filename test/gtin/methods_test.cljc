(ns gtin.methods-test
  "GTIN actor methods (validate / lookup / register / resolve-alias) — the XRPC
  surface from openapi/gtin.openapi.json, exercised against the generated catalog."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.catalog :as cat]
            [gtin.identifier-registry :as ir]))

(deftest validate-gtin-roundtrip
  (testing "real EAN-13 / JAN / GTIN-14 validate"
    (let [r (ir/validate-gtin {:code "5449000000996"})]
      (is (true? (:valid r)))
      (is (= :ean-13 (:codeType r)))
      (is (= "05449000000996" (:canonicalGtin14 r)))
      (is (= 6 (:checkDigit r))))
    (let [r (ir/validate-gtin {:code "4902102139496"})]
      (is (true? (:valid r)))
      (is (= :jan-13 (:codeType r))))
    (let [r (ir/validate-gtin {:code "00194253396062"})]
      (is (true? (:valid r)))
      (is (= :gtin-14 (:codeType r)))))
  (testing "bad check digit → invalid, no canonicalGtin14"
    (let [r (ir/validate-gtin {:code "5449000000997"})]
      (is (false? (:valid r)))
      (is (nil? (:canonicalGtin14 r))))))

(deftest lookup-product-by-gtin-and-by-13-digit-alias
  (let [by-g14 (cat/lookup-product {:gtin "05449000000996"})]
    (is by-g14)
    (is (= "Coca-Cola Classic 330ml can" (:gtin.product/name by-g14))))
  ;; A caller arriving with the 13-digit EAN resolves through the alias table.
  (let [by-13 (cat/lookup-product {:code "5449000000996"})]
    (is by-13)
    (is (= "05449000000996" (:gtin.product/productId by-13)))))

(deftest resolve-alias-returns-canonical-product-did
  (let [r (cat/resolve-alias {:codeType :ean :codeValue "5449000000996"})]
    (is (= "05449000000996" (:canonicalProductId r)))
    (is (= "did:web:etzhayyim.com:actor:gtin:product:gtin_05449000000996"
           (:productDid r)))))

(deftest register-product-validates-and-issues-did
  (testing "valid new GTIN-14 → tx-data with new-scheme DID"
    (let [tx (cat/register-product {:name      "Test Item"
                                    :gtin      "00194253396062"
                                    :brand     "x"
                                    :category  "43200000"})]
      (is (= "did:web:etzhayyim.com:actor:gtin:product:gtin_00194253396062"
             (:gtin.product/did tx)))
      (is (= :gtin (:gtin.product/code-type tx)))
      (is (= :synthesized (:gtin.product/sourcing tx)))))
  (testing "invalid check digit throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (cat/register-product {:name "Bad" :gtin "5449000000997"})))))

(deftest segment-resolution-stays-on-unspsc-side
  ;; GTIN actor carries commodity as metadata only; the segment (2-digit) is
  ;; derived via gtin.identifier/unspsc-segment, NOT from a gtin-side tree.
  (let [cola (cat/lookup-product {:gtin "05449000000996"})]
    (is (= "50" (cat/segment-for-product cola)))))
