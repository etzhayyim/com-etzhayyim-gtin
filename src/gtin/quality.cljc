(ns gtin.quality
  "Quality: detect-duplicate / review-packaging-split.

  Implements the manifest's Quality capability over the committed catalog. These
  are data-integrity checks — the catalog should never have two rows claiming the
  same canonical GTIN-14, and a barcode must not resolve to two trade items. The
  packaging-split rule encodes CLAUDE.md path-resolve rule 5: different pack
  sizes split the DID even when brand + model match."
  (:require [gtin.catalog :as cat]))

(defn detect-duplicate-gtins
  "Find products sharing the same canonical GTIN-14. Returns a seq of
  {:gtin ... :productIds [...]} for each GTIN claimed by >1 product, or empty if
  the catalog is clean."
  ([]
   (detect-duplicate-gtins (cat/snapshot)))
  ([catalog]
   (let [products (:product catalog)]
     (->> products
          (filter :gtin.product/gtin)
          (group-by :gtin.product/gtin)
          (keep (fn [[g ps]]
                  (when (> (count ps) 1)
                    {:gtin g :productIds (map :gtin.product/productId ps)})))
          seq))))

(defn detect-duplicate-aliases
  "Find alias (codeType, codeValue) pairs claimed by more than one canonical
  product — a real integrity violation (one barcode resolving to two trade
  items). Returns seq of {:codeType :codeValue :productIds [...]}."
  ([]
   (let [aliases (:alias (cat/snapshot))]
     (->> aliases
          (group-by (juxt :gtin.alias/codeType :gtin.alias/codeValue))
          (keep (fn [[k as]]
                  (when (> (count as) 1)
                    {:codeType   (first k)
                     :codeValue  (second k)
                     :productIds (map :gtin.alias/canonicalProductId as)})))
          seq))))

(defn review-packaging-split
  "Decide whether two products must keep separate DIDs. Distinct GTINs are
  always distinct trade items; same GTIN but different net-content/packSize
  requires a split (path-resolve rule 5). Same GTIN + same pack = same item.
  Returns {:split-required? bool :reason}."
  ([product-id-a product-id-b]
   (review-packaging-split (cat/snapshot) product-id-a product-id-b))
  ([_catalog product-id-a product-id-b]
   (let [a (cat/lookup-product {:productId product-id-a})
         b (cat/lookup-product {:productId product-id-b})]
     (cond
       (not (and a b))
       {:split-required? false :reason "one or both products not found"}
       (not= (:gtin.product/gtin a) (:gtin.product/gtin b))
       {:split-required? true :reason "distinct GTINs are always distinct DIDs"}
       (not= (:gtin.product/net-content a) (:gtin.product/net-content b))
       {:split-required? true :reason "different net-content/packSize requires a split"}
       :else
       {:split-required? false :reason "same GTIN and pack size — same trade item"}))))
