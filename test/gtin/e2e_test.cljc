(ns gtin.e2e-test
  "End-to-end identity chain: GTIN input → validate → catalog lookup → UNSPSC
  segment (metadata mirror) → alias round-trip → register-product DID.

  The cloud-itonami procurement-tag end of the chain (product->goyoukiki-unspsc-tags
  → #{segment commodity}) is covered by cloud-itonami's portable-cljs suite
  (dual-source import + tag derivation, PR #451). This test exercises the
  gtin-actor-side surface end to end on the committed catalog."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.catalog :as cat]
            [gtin.identifier-registry :as ir]))

(deftest gtin-to-unspsc-segment-e2e
  (testing "Coca-Cola 330ml — validate → lookup → UNSPSC segment"
    ;; 1. validate the public EAN-13
    (let [v (ir/validate-gtin {:code "5449000000996"})]
      (is (true? (:valid v)))
      (is (= :ean-13 (:codeType v)))
      (is (= "05449000000996" (:canonicalGtin14 v))))
    ;; 2. resolve the canonical product via the GTIN-14
    (let [p (cat/lookup-product {:gtin "05449000000996"})]
      (is p "catalog resolves the trade item")
      (is (= "Coca-Cola Classic 330ml can" (:gtin.product/name p)))
      (is (= "05449000000996" (:gtin.product/gtin p)))
      (is (= "50202301" (:gtin.product/category p)) "commodity attached as metadata")
      ;; 3. derive the segment via the UNSPSC-side rule (NOT a gtin-side tree)
      (is (= "50" (cat/segment-for-product p))
          "segment resolved by gtin.identifier/unspsc-segment, mirroring kotoba-lang/unspsc"))))

(deftest alias-round-trip-e2e
  (testing "a caller arriving with the 13-digit EAN resolves to the canonical DID"
    (let [r (cat/resolve-alias {:codeType :ean :codeValue "5449000000996"})]
      (is (= "05449000000996" (:canonicalProductId r)))
      (is (= "did:web:etzhayyim.com:actor:gtin:product:gtin_05449000000996"
             (:productDid r))))))

(deftest register-product-issues-canonical-did-e2e
  (testing "a newly-registered GTIN-14 carries the new-scheme product DID"
    (let [tx (cat/register-product {:name "Test Item"
                                    :gtin "00194253396062"
                                    :brand "x"
                                    :category "43200000"})]
      (is (= "did:web:etzhayyim.com:actor:gtin:product:gtin_00194253396062"
             (:gtin.product/did tx)))
      (is (= :synthesized (:gtin.product/sourcing tx))
          "newly registered, not yet authoritative"))))

(deftest invalid-gtin-rejected-at-the-gate-e2e
  (testing "an invalid check digit never reaches the catalog"
    (let [v (ir/validate-gtin {:code "5449000000997"})]
      (is (false? (:valid v)))
      (is (nil? (:canonicalGtin14 v))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (cat/register-product {:name "Bad" :gtin "5449000000997"})))))
