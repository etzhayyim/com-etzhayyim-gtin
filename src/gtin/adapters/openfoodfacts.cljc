(ns gtin.adapters.openfoodfacts
  "Open Food Facts record → GTIN actor 4-collection tx-data.

  OFF is CC-BY-SA, crowd-sourced (~3M+ food/beverage trade items, each with a
  real GTIN barcode + brand). This adapter turns one OFF record (a string-keyed
  map: code / product_name / brands / ...) into gtin.product / gtin.alias /
  gtin.brand rows — the trade-item-identity surface the gtin actor owns.

  BOM (materials, bom.edge) is OUT OF SCOPE here: product → material
  decomposition is uchiwake's responsibility (ADR-2606081800). This namespace
  emits identity rows only.

  HONESTY (uchiwake G5): every row is :sourcing :representative — OFF is
  crowd-sourced, never :authoritative. The GTIN is validated against the GS1
  mod-10 check digit; a record with a bad/missing check digit returns {} — it is
  SKIPPED, not admitted. LIVE OFF network fetch stays operator-gated
  (uchiwake G7-equivalent); this namespace is a pure transform over a record
  map and is import-safe."
  (:require [clojure.string :as str]
            [gtin.identifier :as id]))

(defn- slug
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- brand-id
  [brand-raw]
  (let [b (str/trim (or brand-raw ""))]
    (when-not (or (str/blank? b) (= b "(unknown)"))
      (let [sl (slug b)] (when-not (str/blank? sl) sl)))))

(defn- code-type-kw
  "Map identifier.detect-code-type (:gtin-8/:gtin-14/:jan-13/:ean-13/:upc-12) to
  the alias codeType vocabulary (:gtin/:jan/:upc/:ean)."
  [ct]
  (case ct
    (:gtin-8 :gtin-14) :gtin
    :jan-13 :jan
    :upc-12 :upc
    :ean-13 :ean
    :ean))

(defn normalize-record
  "OFF record (string-keyed map) → {:product [...] :alias [...] :brand [...]} or
  {} if the GTIN check digit is invalid/missing. category is omitted — OFF
  carries no UNSPSC; the gtin actor attaches UNSPSC via other paths."
  [rec]
  (let [raw (str/trim (str (get rec "code")))]
    (if (or (str/blank? raw) (not (id/gtin-check-digit-ok raw)))
      {}
      (let [g14 (id/normalize-gtin raw)
            ct  (id/detect-code-type raw)
            code-kw (code-type-kw ct)
            pid g14
            did (id/product-did code-kw pid)
            brand-raw (-> (str (or (get rec "brands") ""))
                          (str/split #",") first str/trim)
            bid (brand-id brand-raw)
            product (cond-> {:gtin.product/productId pid
                             :gtin.product/code-type code-kw
                             :gtin.product/did did
                             :gtin.product/gtin g14
                             :gtin.product/source-format ct
                             :gtin.product/name (or (get rec "product_name") pid)
                             :gtin.product/sector :food-beverage
                             :gtin.product/sourcing :representative}
                      bid (assoc :gtin.product/brand bid))
            aliases (let [src :representative
                          base [{:gtin.alias/canonicalProductId pid
                                 :gtin.alias/codeType code-kw
                                 :gtin.alias/codeValue raw
                                 :gtin.alias/sourcing src}
                                {:gtin.alias/canonicalProductId pid
                                 :gtin.alias/codeType :gtin
                                 :gtin.alias/codeValue g14
                                 :gtin.alias/sourcing src}]
                          with-jan (if (= code-kw :jan)
                                     (conj base {:gtin.alias/canonicalProductId pid
                                                 :gtin.alias/codeType :ean
                                                 :gtin.alias/codeValue raw
                                                 :gtin.alias/sourcing src})
                                     base)
                          thirteen (when (and (= (count g14) 14) (= \0 (first g14)))
                                     (subs g14 1))
                          ct13 (some-> thirteen id/detect-code-type)]
                      (if (and thirteen ct13)
                        (conj with-jan {:gtin.alias/canonicalProductId pid
                                        :gtin.alias/codeType (case ct13 :jan-13 :jan :ean-13 :ean :gtin)
                                        :gtin.alias/codeValue thirteen
                                        :gtin.alias/sourcing src})
                        with-jan))
            brand (when bid
                    {:gtin.brand/brandId bid
                     :gtin.brand/name brand-raw
                     :gtin.brand/owner-did-verified false
                     :gtin.brand/sourcing :representative})]
        (cond-> {:product [product] :alias aliases}
          brand (assoc :brand [brand]))))))

(defn normalize-dataset
  "Vector of OFF records → flat {:product [...] :alias [...] :brand [...]}.
  Invalid records (bad/missing check digit) are skipped (not admitted)."
  [records]
  (reduce (fn [acc rec]
            (let [r (normalize-record rec)]
              (if (empty? r) acc
                  (cond-> acc
                    true            (update :product #(vec (concat % (:product r))))
                    true            (update :alias   #(vec (concat % (:alias r))))
                    (:brand r)      (update :brand   #(vec (concat % (:brand r))))))))
          {:product [] :alias [] :brand []}
          records))
