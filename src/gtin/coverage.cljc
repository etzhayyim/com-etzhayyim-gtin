(ns gtin.coverage
  "Coverage metrics over the committed catalog.

  Mirrors the manifest's cron coverageSnapshot pipeline (graph.query nodeCount
  -> graph.write ActorCoverageSnapshot) as a local, testable read. Counts
  products/brands/aliases/categories, the UNSPSC segments the catalog reaches,
  and honest gaps (brands whose owner DID is not yet attested, segments whose
  commodity name is still :missing). This is the basis for deciding where to
  expand coverage next (OFF ingest, operator-gated GS1/UNSPSC codeset)."
  (:require [gtin.catalog :as cat]
            [gtin.identifier :as id]))

(defn snapshot
  "Coverage snapshot of the catalog. Pure read over (cat/snapshot)."
  ([]
   (let [c        (cat/snapshot)
         products (:product c)
         brands   (:brand c)
         aliases  (:alias c)
         cats     (:category c)
         comms    (filter #(= :commodity (:gtin.category/level %)) cats)
         segs     (filter #(= :segment (:gtin.category/level %)) cats)
         product-segs (into #{} (keep #(some-> (:gtin.product/category %) id/unspsc-segment)) products)]
     {:products              (count products)
      :brands                (count brands)
      :aliases               (count aliases)
      :categories            (count cats)
      :commodities           (count comms)
      :segments-reached      (count product-segs)
      :segments-named        (count (filter :gtin.category/name segs))
      :brands-unverified     (count (filter #(not (:gtin.brand/owner-did-verified %)) brands))})))

(defn segment-coverage
  "Map of UNSPSC segment (2-digit) -> product count, sorted by segment."
  ([]
   (let [products (:product (cat/snapshot))]
     (into (sorted-map)
           (->> products
                (keep #(when-let [s (some-> (:gtin.product/category %) id/unspsc-segment)]
                         s))
                (frequencies))))))

(defn sourcing-breakdown
  "Counts by :sourcing provenance over products."
  ([]
   (let [products (:product (cat/snapshot))]
     (frequencies (map :gtin.product/sourcing products)))))

(defn brands-without-verified-owner
  "Seq of brand rows whose owner DID is not yet attested by a live kakuto actor
  (owner-did-verified false). Honest gap list for follow-up verification."
  ([]
   (filter #(not (:gtin.brand/owner-did-verified %)) (:brand (cat/snapshot)))))
