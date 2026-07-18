(ns gtin.catalog-test
  "Referential-integrity and shape tests for the generated 4-collection catalog
  (data/{product,alias,brand,category}.edn). bb-runnable from a standalone
  checkout (run_tests.clj); reads only the committed data/*.edn — never the
  superproject-relative generator inputs."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [gtin.identifier :as id]))

(defn- load-col [name]
  (edn/read-string (slurp (str "data/" name ".edn"))))

(deftest four-collections-present-and-non-empty
  (doseq [c ["product" "alias" "brand" "category"]]
    (let [rows (load-col c)]
      (is (vector? rows))
      (is (pos? (count rows)) (str c ".edn should be non-empty")))))

(deftest coca-cola-resolves-across-all-collections
  (let [products   (load-col "product")
        aliases    (load-col "alias")
        brands     (load-col "brand")
        cola       (some #(when (= (:gtin.product/productId %) "05449000000996") %) products)
        cola-brand (some #(when (= (:gtin.brand/brandId %) "coca-cola") %) brands)]
    (is cola "Coca-Cola product present")
    (is (true? (id/gtin-check-digit-ok (:gtin.product/gtin cola)))
        "ingested GTIN passes GS1 mod-10")
    (is (= "did:web:etzhayyim.com:actor:gtin:product:gtin_05449000000996")
        (:gtin.product/did cola))
    (is (= "50202301") (:gtin.product/category cola))
    (testing "GTIN-14 + derived 13-digit EAN alias both present"
      (is (some #(and (= (:gtin.alias/canonicalProductId %) "05449000000996")
                      (= (:gtin.alias/codeType %) :gtin)
                      (= (:gtin.alias/codeValue %) "05449000000996")) aliases))
      (is (some #(and (= (:gtin.alias/canonicalProductId %) "05449000000996")
                      (= (:gtin.alias/codeType %) :ean)
                      (= (:gtin.alias/codeValue %) "5449000000996")) aliases)))
    (is (= "org.corp.us.coca-cola" (:gtin.brand/owner-handle cola-brand))
        "brand-owner carried through (not clobbered by merged rows lacking it)")))

(deftest referential-integrity-across-collections
  (let [products  (load-col "product")
        brands    (load-col "brand")
        categories (load-col "category")
        brand-ids (into #{} (map :gtin.brand/brandId) brands)
        cat-ids   (into #{} (map :gtin.category/categoryId) categories)]
    (testing "every product brand FK resolves to a brand row"
      (doseq [p products
              :when (:gtin.product/brand p)]
        (is (contains? brand-ids (:gtin.product/brand p))
            (str "dangling brand FK: " (:gtin.product/brand p)))))
    (testing "every product category FK resolves to a category row"
      (doseq [p products
              :when (:gtin.product/category p)]
        (is (contains? cat-ids (:gtin.product/category p))
            (str "dangling category FK: " (:gtin.product/category p)))))))

(deftest category-is-metadata-mirror-not-a-full-tree
  ;; ADR-2607031800: gtin.category is metadata-only. The full segment→family→class→
  ;; commodity tree lives in kotoba-lang/unspsc; family/class are intentionally
  ;; absent here (honest :missing-family-class), commodity parentId points directly
  ;; at a segment node.
  (let [categories (load-col "category")
        comms      (filter #(= :commodity (:gtin.category/level %)) categories)
        seg-ids    (into #{} (map :gtin.category/categoryId)
                         (filter #(= :segment (:gtin.category/level %)) categories))]
    (is (empty? (filter #(#{:family :class} (:gtin.category/level %)) categories))
        "no family/class intermediate nodes — hierarchy is UNSPSC-side responsibility")
    (doseq [c comms]
      (is (contains? seg-ids (:gtin.category/parentId c))
          (str "commodity " (:gtin.category/categoryId c) " parentId must be a known segment")))))

(deftest sourcing-honesty-on-every-row
  ;; uchiwake G5 carried through: every row declares its sourcing provenance.
  (let [all-rows (mapcat load-col ["product" "alias" "brand" "category"])]
    (is (seq all-rows))
    (doseq [r all-rows]
      (is (contains? #{:authoritative :representative :synthesized}
                     (get r :gtin.product/sourcing
                          (get r :gtin.alias/sourcing
                               (get r :gtin.brand/sourcing
                                    (get r :gtin.category/sourcing)))))
          (str "row missing sourcing: " (keys r))))))
