(ns gtin.catalog
  "In-memory lookup / register / resolve-alias over the generated 4-collection
  EDN catalog (data/{product,alias,brand,category}.edn).

  Standalone-runnable: reads only the committed data/*.edn (never the
  superproject-relative generator inputs). The k8s-langserver runtime mirrors
  these collections into a graph DB and the manifest.edn pipelines express
  lookup/register via graph.query Cypher — this namespace is the local, testable
  face of the same product-identity surface, matching the XRPC shapes in
  openapi/gtin.openapi.json."
  (:require [clojure.edn :as edn]
            [gtin.identifier :as id]))

(defn- read-file [path]
  #?(:clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))

(defn- load-col [name]
  (edn/read-string (read-file (str "data/" name ".edn"))))

(defonce ^:private catalog (atom nil))

(defn load-catalog!
  "Load (or reload) the 4 collections into the in-memory catalog atom. Idempotent."
  ([] (load-catalog! nil))
  ([_opts]
   (reset! catalog
           {:product  (load-col "product")
            :alias    (load-col "alias")
            :brand    (load-col "brand")
            :category (load-col "category")})
   @catalog))

(defn- ensure-loaded []
  (when-not @catalog (load-catalog!)))

(defn snapshot
  "Current in-memory catalog (4 collections). Loads on first call."
  []
  (ensure-loaded) @catalog)

(defn lookup-product
  "Resolve a canonical product by :productId, :gtin (GTIN-14), or :code (any
  alias code value — GTIN-14 / 13-digit EAN / JAN / UPC). Returns the product
  row map or nil. Implements /xrpc/com.etzhayyim.gtin.lookupProduct locally."
  [{:keys [productId gtin code] :as _q}]
  (ensure-loaded)
  (let [ps (:product @catalog)]
    (or (when productId
          (some #(when (= (:gtin.product/productId %) productId) %) ps))
        (when gtin
          (let [g14 (id/normalize-gtin gtin)]
            (some #(when (= (:gtin.product/gtin %) g14) %) ps)))
        (when code
          (let [aliases (:alias @catalog)
                g14 (id/normalize-gtin code)
                pid (some #(when (= (:gtin.alias/codeValue %) g14)
                             (:gtin.alias/canonicalProductId %)) aliases)]
            (when pid
              (some #(when (= (:gtin.product/productId %) pid) %) ps)))))))

(defn resolve-alias
  "{:codeType :gtin|:jan|:upc|:ean  :codeValue \"...\"} →
  {:canonicalProductId :productDid}, or nil. Implements resolveAlias (CLAUDE.md)."
  [{:keys [codeType codeValue] :as _q}]
  (ensure-loaded)
  (let [aliases (:alias @catalog)
        ct (if (keyword? codeType) codeType (keyword codeType))
        cv (str codeValue)
        row (some #(when (and (= (:gtin.alias/codeType %) ct)
                              (= (:gtin.alias/codeValue %) cv)) %) aliases)]
    (when-let [pid (:gtin.alias/canonicalProductId row)]
      (let [prod (lookup-product {:productId pid})]
        {:canonicalProductId pid
         :productDid (:gtin.product/did prod)}))))

(defn register-product
  "Return tx-data for a new product (no in-memory mutation — the k8s-langserver
  runtime applies this via graph.write). Validates the GS1 mod-10 check digit
  first; throws on invalid barcode. :sourcing defaults to :synthesized (newly
  registered, not yet authoritative). Implements /xrpc/com.etzhayyim.gtin.registerProduct."
  [{:keys [productId name brand gtin jan upc ean category] :as _p}]
  (let [code (or gtin jan upc ean)
        g14  (some-> code id/normalize-gtin)]
    (when (and code (not (id/gtin-check-digit-ok code)))
      (throw (ex-info "register-product: GTIN check-digit invalid"
                      {:code code :canonicalGtin14 g14})))
    (let [ct  (cond gtin :gtin jan :jan upc :upc ean :ean :else :prod)
          pid (or productId g14)
          did (id/product-did ct (or code pid))]
      (cond-> {:gtin.product/productId pid
               :gtin.product/code-type ct
               :gtin.product/did did
               :gtin.product/name name
               :gtin.product/sourcing :synthesized}
        brand    (assoc :gtin.product/brand brand)
        category (assoc :gtin.product/category category)
        g14      (assoc :gtin.product/gtin g14)))))

(defn segment-for-product
  "UNSPSC segment (2-digit) for a product's category, resolved via the UNSPSC-side
  rule (gtin carries commodity as metadata only — ADR-2607031800)."
  [product]
  (some-> (:gtin.product/category product) id/unspsc-segment))
