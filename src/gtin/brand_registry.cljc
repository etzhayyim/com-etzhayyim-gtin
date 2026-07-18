(ns gtin.brand-registry
  "Brand registry: register / match canonical brands.

  Implements the manifest's Brand Registry capability (registerBrand / matchBrand)
  over the committed data/brand.edn (loaded via gtin.catalog/snapshot). register
  returns tx-data (the k8s-langserver runtime applies it via graph.write; no
  in-memory mutation here). match resolves by exact name or slug (case-insensitive).
  Brand-owner DIDs are NEVER fabricated — owner-handle carries the kabuto handle
  and owner-did-verified defaults false until a live kakuto actor attests it."
  (:require [clojure.string :as str]
            [gtin.catalog :as cat]))

(defn- slug
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn register-brand
  "Return tx-data for a new brand row. {:name} required; owner-handle/country
  optional. :sourcing defaults :synthesized (newly registered, not yet
  authoritative). Throws if name is blank."
  [{:keys [name owner-handle country owner-did-verified]
    :or {owner-did-verified false}}]
  (when (str/blank? (str name))
    (throw (ex-info "register-brand: name is required" {:name name})))
  (let [bid (slug name)]
    (cond-> {:gtin.brand/brandId bid
             :gtin.brand/name (str name)
             :gtin.brand/owner-did-verified owner-did-verified
             :gtin.brand/sourcing :synthesized}
      owner-handle (assoc :gtin.brand/owner-handle owner-handle)
      country      (assoc :gtin.brand/country country))))

(defn match-brand
  "Find brand row(s) by exact name or slug (case-insensitive). Returns a seq."
  ([query] (match-brand (cat/snapshot) query))
  ([catalog query]
   (let [q      (str/lower-case (str/trim (str query)))
         q-slug (slug query)]
     (filter (fn [b]
               (let [nm  (some-> (:gtin.brand/name b) str/lower-case)
                     bid (:gtin.brand/brandId b)]
                 (or (= nm q) (= bid q-slug))))
             (:brand catalog)))))

(defn brand-by-id
  "Look up a brand by brandId. Returns the row or nil."
  ([brand-id] (brand-by-id (cat/snapshot) brand-id))
  ([catalog brand-id]
   (some #(when (= (:gtin.brand/brandId %) brand-id) %)
         (:brand catalog))))

(defn owner-verified?
  "True if the brand's owner DID has been attested by a live kakuto actor
  (owner-did-verified true). False for seed/synthesized rows."
  ([brand-id] (owner-verified? (cat/snapshot) brand-id))
  ([catalog brand-id]
   (some-> (brand-by-id catalog brand-id) :gtin.brand/owner-did-verified boolean)))
