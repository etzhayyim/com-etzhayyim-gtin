(ns gtin.build-catalog
  "Generate the gtin actor's 4-collection EDN catalog by integrating uchiwake
  (GTIN ↔ UNSPSC commodity, G5 sourcing) and kotoba-lang/unspsc (segment SSoT).
  Output: data/{product,alias,brand,category}.edn.

  DEVELOPMENT-TIME ONLY — reads superproject-relative paths (uchiwake,
  kotoba-lang/unspsc). The RUNTIME reads only the generated data/*.edn, keeping
  the standalone repository contract (CLAUDE.md L149-156). Run from superproject root:

    GTIN_DATA_OUT=<repo>/data nbb --classpath <repo>/src -m gtin.build-catalog"
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [gtin.identifier :as id]
            #?(:cljs ["fs" :as fs])))

;; ── config (env or superproject-relative defaults) ───────────────────────────

(defn- getenv
  "Env var, bb/clj + nbb/cljs portable."
  [k]
  #?(:clj (System/getenv k)
     :cljs (aget (.-env js/process) k)))

(def uchiwake-sources
  "EDN sources of :product/* maps. uchiwake seed+merged (GTIN/BOM) is primary;
  kakaku kotoba/seed.edn adds JAN retail trade items (broader segment coverage).
  Env overrides UCHIWAKE_SEED/MERGED; KAKAKU_SEED replaces the kakaku path."
  (->> [(getenv "UCHIWAKE_SEED")
        (getenv "UCHIWAKE_MERGED")
        (getenv "KAKAKU_SEED")
        "orgs/etzhayyim/com-etzhayyim-uchiwake/data/seed-products.kotoba.edn"
        "orgs/etzhayyim/com-etzhayyim-uchiwake/data/products.merged.kotoba.edn"
        "orgs/etzhayyim/com-etzhayyim-kakaku/kotoba/seed.edn"]
       (filter some?) distinct))

(def unspsc-registry-path
  (or (getenv "UNSPSC_REGISTRY")
      "orgs/kotoba-lang/unspsc/resources/kotoba/unspsc/registry.edn"))

(def out-dir (or (getenv "GTIN_DATA_OUT") "data"))

;; ── EDN I/O ──────────────────────────────────────────────────────────────────

(defn- read-file [path]
  #?(:clj (slurp path)
     :cljs (fs/readFileSync path "utf8")))

(defn- write-file [path content]
  #?(:clj (spit path content)
     :cljs (fs/writeFileSync path content)))

(defn- read-edn-file [path]
  (edn/read-string (read-file path)))

(defn- read-uchiwake-products [path]
  (try
    (let [entities (read-edn-file path)]
      (->> entities
           (filter #(and (map? %) (contains? % :product/id)))
           ;; normalize :product/id separators ('jan_4901...' -> 'jan.4901...')
           ;; so parse-product-id handles both uchiwake (dot) and kakaku (underscore)
           (map #(update % :product/id (fn [id] (str/replace (str id) #"_" "."))))))
    (catch :default e
      (println "  skip (absent/unreadable):" path "—" (ex-message e))
      [])))

(defn- read-unspsc-segments [path]
  ;; registry.edn: [{:db/id -1 :kotoba.unspsc/unspsc "<embedded EDN string>"}]
  (try
    (let [reg (read-edn-file path)
          embedded (some :kotoba.unspsc/unspsc reg)]
      (if embedded (edn/read-string embedded)
          (do (println "  WARN: no :kotoba.unspsc/unspsc in" path) [])))
    (catch :default e
      (println "  skip UNSPSC registry:" path "—" (ex-message e)) [])))

;; ── helpers ──────────────────────────────────────────────────────────────────

(defn- slug [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- sourcing-of [p] (or (:product/sourcing p) :representative))

(defn- dedupe-by [f xs]
  (->> xs (reduce (fn [acc x] (assoc acc (f x) x)) {}) vals))

(defn- dedupe-brand
  "Merge brand rows by brandId, preferring non-nil field values — so a seed row
  carrying owner-handle/country is not clobbered by a merged row that lacks them."
  [brands]
  (->> brands
       (reduce (fn [acc b]
                 (let [bid (:gtin.brand/brandId b)]
                   (assoc acc bid (merge-with #(if (nil? %1) %2 %1) (get acc bid) b))))
               {})
       vals))

;; ── product → 4-collection rows ──────────────────────────────────────────────

(defn- product-rows [p]
  (let [{:keys [code-type code]} (or (id/parse-product-id (:product/id p))
                                     {:code-type :prod :code (str (:product/id p))})
        barcoded? (contains? #{:gtin :jan :upc :ean} code-type)
        gtin14 (when barcoded? (id/normalize-gtin code))
        pid (str code)
        src-fmt (or (:product/gtin-format p) (when barcoded? (id/detect-code-type code)))
        did (id/product-did code-type pid)
        bname (:product/brand p)
        bid (when (and bname (not= bname "(representative)")) (slug bname))
        prod (cond-> {:gtin.product/productId pid
                      :gtin.product/code-type code-type
                      :gtin.product/did did
                      :gtin.product/name (:product/name p)
                      :gtin.product/source-format src-fmt
                      :gtin.product/sourcing (sourcing-of p)}
               gtin14 (assoc :gtin.product/gtin gtin14)
               bid (assoc :gtin.product/brand bid)
               (or (:product/unspsc p) (:product/category p)) (assoc :gtin.product/category (or (:product/unspsc p) (:product/category p)))
               (:product/gs1-prefix p) (assoc :gtin.product/gs1-prefix (:product/gs1-prefix p))
               (:product/gs1-prefix-country p) (assoc :gtin.product/gs1-prefix-country (:product/gs1-prefix-country p))
               (:product/sector p) (assoc :gtin.product/sector (:product/sector p))
               (:product/hs-code p) (assoc :gtin.product/hs-code (:product/hs-code p))
               (:product/net-content p) (assoc :gtin.product/net-content (:product/net-content p))
               (:product/net-content-unit p) (assoc :gtin.product/net-content-unit (:product/net-content-unit p)))
        aliases (when barcoded?
                  (let [src (sourcing-of p)
                        base [{:gtin.alias/canonicalProductId pid
                               :gtin.alias/codeType code-type
                               :gtin.alias/codeValue code
                               :gtin.alias/sourcing src}
                              {:gtin.alias/canonicalProductId pid
                               :gtin.alias/codeType :gtin
                               :gtin.alias/codeValue gtin14
                               :gtin.alias/sourcing src}]
                        with-jan (if (= :jan code-type)
                                   (conj base {:gtin.alias/canonicalProductId pid
                                               :gtin.alias/codeType :ean
                                               :gtin.alias/codeValue code
                                               :gtin.alias/sourcing src})
                                   base)
                        ;; GTIN-14 left-zero-padded from a 13-digit code → also emit the 13-digit alias
                        thirteen (when (and (= (count gtin14) 14) (= \0 (first gtin14)))
                                   (subs gtin14 1))
                        ct13 (some-> thirteen id/detect-code-type)]
                    (if (and thirteen ct13)
                      (conj with-jan {:gtin.alias/canonicalProductId pid
                                      :gtin.alias/codeType (case ct13 :jan-13 :jan :ean-13 :ean :gtin)
                                      :gtin.alias/codeValue thirteen
                                      :gtin.alias/sourcing src})
                      with-jan)))
        brand (when bid
                {:gtin.brand/brandId bid
                 :gtin.brand/name bname
                 :gtin.brand/owner-handle (:product/brand-owner p)
                 :gtin.brand/owner-did-verified false
                 :gtin.brand/country (:product/gs1-prefix-country p)
                 :gtin.brand/sourcing :representative})]
    {:product prod :aliases aliases :brand brand}))

(defn- dedupe-products [ps]
  ;; last-wins by :product/id (sources are ordered seed → merged, so merged wins)
  (->> ps (reduce (fn [acc p] (assoc acc (:product/id p) p)) {}) vals))

(defn- build-categories [product-rows segment-by-id]
  ;; The gtin actor carries commodity as METADATA ONLY (ADR-2607031800). The full
  ;; segment→family→class→commodity tree lives in kotoba-lang/unspsc; family/class
  ;; are intentionally absent here (honest :missing-family-class).
  (let [commodities (into #{} (keep :gtin.product/category) product-rows)
        comm (for [c (sort commodities) :let [seg (id/unspsc-segment c)]]
               (cond-> {:gtin.category/categoryId c
                        :gtin.category/level :commodity
                        :gtin.category/parentId seg
                        :gtin.category/status :missing-family-class
                        :gtin.category/sourcing :representative}
                 (get segment-by-id seg) (assoc :gtin.category/segment-name (get-in segment-by-id [seg :name]))))
        reached (into #{} (keep :gtin.category/parentId) comm)
        ;; ALL registry segments become category rows (name-carrying) so the gtin
        ;; actor can resolve ANY UNSPSC segment, not only ones products reach.
        ;; :gtin.category/product-reached? marks segments actually touched by a product.
        segrows (for [s (sort (keys segment-by-id))]
                  (cond-> {:gtin.category/categoryId s
                           :gtin.category/level :segment
                           :gtin.category/parentId nil
                           :gtin.category/sourcing :representative
                           :gtin.category/product-reached? (contains? reached s)}
                          (get segment-by-id s) (assoc :gtin.category/name (get-in segment-by-id [s :name]))))]
    (concat segrows comm)))

;; ── main ─────────────────────────────────────────────────────────────────────

(defn- spit-edn [path rows]
  (let [f (str out-dir "/" path)]
    (write-file f (prn-str (vec rows)))
    (println "  wrote" f "—" (count rows) "rows")))

(defn -main [& _args]
  (println ";; gtin build-catalog")
  (println ";;   uchiwake:" uchiwake-sources)
  (println ";;   unspsc registry:" unspsc-registry-path)
  (println ";;   out dir:" out-dir)
  (let [seg-entries (read-unspsc-segments unspsc-registry-path)
        segment-by-id (into {} (map (fn [e] [(:segment e) e])) seg-entries)
        products (->> (mapcat read-uchiwake-products uchiwake-sources) dedupe-products)
        _ (println ";;   products ingested:" (count products)
                   "| segments:" (count segment-by-id))
        rows (map product-rows products)
        product-edn (map :product rows)
        alias-edn (dedupe-by (juxt :gtin.alias/canonicalProductId :gtin.alias/codeType :gtin.alias/codeValue)
                             (mapcat :aliases rows))
        brand-edn (dedupe-brand (keep :brand rows))
        category-edn (build-categories product-edn segment-by-id)]
    (spit-edn "product.edn" product-edn)
    (spit-edn "alias.edn" alias-edn)
    (spit-edn "brand.edn" brand-edn)
    (spit-edn "category.edn" category-edn)
    (println ";; done.")))

;; nbb: `nbb -m gtin.build-catalog` resolves -main directly (no *main-cli-fn*).
