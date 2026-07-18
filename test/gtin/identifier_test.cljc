(ns gtin.identifier-test
  "GS1 mod-10 and DID-derivation contract for gtin.identifier. Uses known public
  GS1 vectors (Coca-Cola 5449000000996, Nutella 3017620422003, JAN 4902102139496,
  GTIN-14 00194253396062) — the same spec uchiwake_edn.cljc is tested against, so
  this verifies the 1:1 spec parity without requiring uchiwake (standalone contract)."
  (:require [clojure.test :refer [deftest is testing]]
            [gtin.identifier :as id]))

(deftest gs1-check-digit-known-vectors
  (testing "real public GTINs validate (GS1 mod-10)"
    (is (true? (id/gtin-check-digit-ok "5449000000996")))    ; Coca-Cola 330ml EAN-13
    (is (true? (id/gtin-check-digit-ok "3017620422003")))    ; Nutella 750g EAN-13
    (is (true? (id/gtin-check-digit-ok "4902102139496")))    ; JAN (45 prefix)
    (is (true? (id/gtin-check-digit-ok "00194253396062"))))  ; GTIN-14
  (testing "wrong check digit rejects"
    (is (false? (id/gtin-check-digit-ok "5449000000997")))   ; last digit flipped
    (is (false? (id/gtin-check-digit-ok "5449000000990"))))  ; wrong check
  (testing "non-GTIN-family lengths reject"
    (is (false? (id/gtin-check-digit-ok "12345")))
    (is (false? (id/gtin-check-digit-ok "123456789012345")))))

(deftest normalize-left-pads-to-gtin14
  (is (= "05449000000996" (id/normalize-gtin "5449000000996")))   ; 13 → 14
  (is (= "05449000000996" (id/normalize-gtin "05449000000996")))  ; already 14, idempotent
  (is (= "00000000123456" (id/normalize-gtin "123456")))          ; mechanical pad (check-digit rejects later)
  (is (= "00194253396062" (id/normalize-gtin "194253396062"))))   ; UPC-12 → GTIN-14

(deftest detect-code-type-by-length-and-prefix
  (is (= :ean-13 (id/detect-code-type "5449000000996")))
  (is (= :jan-13 (id/detect-code-type "4902102139496")))   ; 49 prefix
  (is (= :jan-13 (id/detect-code-type "4500000000000")))   ; 45 prefix
  (is (= :gtin-14 (id/detect-code-type "00194253396062")))
  (is (= :upc-12 (id/detect-code-type "012345678905")))
  (is (= :gtin-8 (id/detect-code-type "40170725")))
  (is (nil? (id/detect-code-type "12345"))))

(deftest product-did-new-scheme
  (is (= "did:web:etzhayyim.com:actor:gtin:product:jan_4902102139496"
         (id/product-did :jan "4902102139496")))
  (is (= "did:web:etzhayyim.com:actor:gtin:product:gtin_00194253396062"
         (id/product-did :gtin "00194253396062")))
  (is (= "did:web:etzhayyim.com:actor:gtin:product:upc_012345678905"
         (id/product-did :upc "012345678905"))))

(deftest parse-product-id-handles-uchiwake-prefixes
  (is (= {:code-type :gtin :code "05449000000996"}
         (id/parse-product-id "gtin.05449000000996")))
  (is (= {:code-type :prod :code "smartphone-flagship"}
         (id/parse-product-id "prod.smartphone-flagship")))
  (is (nil? (id/parse-product-id "mat.aluminum")))        ; materials are not products
  (is (nil? (id/parse-product-id "part.soc"))))           ; parts are not products

(deftest unspsc-segment-extracts-2-digit-prefix
  (is (= "50" (id/unspsc-segment "50202301")))
  (is (= "43" (id/unspsc-segment "43191501")))
  (is (= "43" (id/unspsc-segment "43")))                  ; already a segment
  (is (nil? (id/unspsc-segment "5")))                     ; too short
  (is (nil? (id/unspsc-segment nil))))
