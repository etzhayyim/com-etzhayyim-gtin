(ns gtin.identifier-registry
  "validateGtin XRPC handler (openapi/gtin.openapi.json). GS1 mod-10 check-digit
  validation + GTIN-14 canonicalization + code-type detection. Delegates the
  arithmetic to gtin.identifier (the standalone, uchiwake-parity port)."
  (:require [gtin.identifier :as id]))

(defn validate-gtin
  "Implements /xrpc/com.etzhayyim.gtin.validateGtin.
  Input  {:code \"5449000000996\"}
  Output {:valid true :codeType :ean-13 :normalized \"05449000000996\"
          :canonicalGtin14 \"05449000000996\" :checkDigit 6}.

  A code is :valid only when its digit length is a GTIN family length (8/12/13/14)
  AND its GS1 mod-10 check digit is correct. codeType / canonicalGtin14 are
  returned only when valid."
  [{:keys [code] :as _q}]
  (let [d    (id/digits (str code))
        ct   (id/detect-code-type d)
        ok?  (boolean (and ct (id/gtin-check-digit-ok d)))
        chk  (when (seq d) (- (int (nth d (dec (count d)))) (int \0)))]
    {:valid          ok?
     :codeType       (when ok? ct)
     :normalized     (when (seq d) (id/normalize-gtin d))
     :canonicalGtin14 (when ok? (id/normalize-gtin d))
     :checkDigit     chk}))
