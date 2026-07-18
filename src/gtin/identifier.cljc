(ns gtin.identifier
  "GTIN-family identifier normalization, GS1 mod-10 check-digit validation, and
  product-DID derivation for the gtin actor.

  normalize-gtin / gtin-check-digit-ok are a spec-faithful (GS1 mod-10) re-impl of
  com-etzhayyim-uchiwake/methods/uchiwake_edn.cljc:150-179. This repo's standalone
  contract (CLAUDE.md L149-156, bb run_tests.clj superproject-independent) forbids
  requiring uchiwake, so the logic is kept here and written cljs/nbb-portable
  (no Character/.charAt interop). The GS1 algorithm is a fixed spec, so the two
  implementations agree on every valid/invalid code — covered by
  gtin.identifier-test with known GS1 vectors (Coca-Cola 5449000000996,
  Nutella 3017620422003, JAN 4902102139496, etc.)."
  (:require [clojure.string :as str]))

;; ── digit helpers (cljs/nbb-portable; no Character interop) ──────────────────

(defn digits
  "Digits-only substring of a GTIN-like value."
  [gtin]
  (apply str (re-seq #"[0-9]" (str gtin))))

(defn normalize-gtin
  "Left-zero-pad any GTIN-8/12/13 to the canonical 14-digit GTIN-14.
  Strings already 14 digits are returned digits-only. >14 digits returned as-is
  digits-only (not a valid GTIN family length; caller should check-digit reject)."
  [gtin]
  (let [d (digits gtin)]
    (if (>= (count d) 14)
      d
      (str (apply str (repeat (- 14 (count d)) \0)) d))))

(defn gtin-check-digit-ok
  "Validate the GS1 mod-10 check digit of a GTIN (length 8/12/13/14).
  Rightmost body digit weighted ×3, alternating (3,1,3,1…). false for any other
  length or non-digit input. 1:1 with uchiwake gtin_check_digit_ok."
  [gtin]
  (let [d (digits gtin)]
    (if-not (contains? #{8 12 13 14} (count d))
      false
      (let [n     (count d)
            body  (subs d 0 (dec n))
            check (- (int (nth d (dec n))) (int \0))
            total (reduce +
                          (map-indexed
                           (fn [i ch]
                             (* (- (int ch) (int \0)) (if (even? i) 3 1)))
                           (reverse body)))]
        (= (mod (- 10 (mod total 10)) 10) check)))))

;; ── code-type detection (path-resolve priority gtin > jan > upc > ean) ───────

(defn detect-code-type
  "Digit-only code → :gtin-8 / :upc-12 / :jan-13 / :ean-13 / :gtin-14, else nil.
  JAN is the GS1 prefix-45/49 EAN-13 used in Japan; other 13-digit codes are EAN-13."
  [code]
  (let [d (digits code)
        n (count d)]
    (cond
      (= n 8)  :gtin-8
      (= n 12) :upc-12
      (and (= n 13) (or (str/starts-with? d "45") (str/starts-with? d "49"))) :jan-13
      (= n 13) :ean-13
      (= n 14) :gtin-14
      :else nil)))

(def ^:private product-id->code-type
  {"gtin" :gtin
   "jan"  :jan
   "upc"  :upc
   "ean"  :ean
   "prod" :prod})

(defn parse-product-id
  "uchiwake :product/id 'gtin.05449000000996' → {:code-type :gtin :code '05449000000996'}.
  prod.* (GTIN-withheld representative items) → {:code-type :prod :code <slug>}.
  Unknown prefix → nil (not a barcode-keyed product)."
  [product-id]
  (let [s    (str product-id)
        idx  (str/index-of s ".")
        pfx  (if idx (subs s 0 idx) s)
        code (when idx (subs s (inc idx)))]
    (when-let [ct (get product-id->code-type pfx)]
      (cond-> {:code-type ct}
        code (assoc :code code)))))

;; ── product DID (new scheme, identity.edn-aligned) ───────────────────────────

(def actor-did "did:web:etzhayyim.com:actor:gtin")

(defn product-did
  "code-type ∈ #{:gtin :jan :upc :ean :prod} + code-value → canonical product DID.
  e.g. :jan '4902102139496' → did:web:etzhayyim.com:actor:gtin:product:jan_4902102139496
  (CLAUDE.md Examples, new scheme; identity.edn :identity/did is the actor root)."
  [code-type code-value]
  (str actor-did ":product:" (name code-type) "_" code-value))

(defn unspsc-segment
  "8-digit (or longer) UNSPSC commodity → 2-digit segment string, or nil.
  Mirrors kotoba.unspsc.product/unspsc-segment (UNSPSC-side hierarchy resolution;
  the gtin actor carries the commodity as metadata only, never the full tree —
  ADR-2607031800 'category-as-metadata-only')."
  [code]
  (when-let [d (seq (digits code))]
    (when (>= (count d) 2) (subs (apply str d) 0 2))))
