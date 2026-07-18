#!/usr/bin/env bb
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[clojure.test :as t])

(let [root (fs/parent (fs/absolutize *file*))]
  (cp/add-classpath (str root "/src"))
  (cp/add-classpath (str root "/test")))

(def suites '[gtin.murakumo-test
              gtin.mesh-manifest-test
              gtin.repository-contract-test
              gtin.identifier-test
              gtin.catalog-test
              gtin.methods-test
              gtin.adapters.openfoodfacts-test
              gtin.e2e-test
              gtin.brand-registry-test
              gtin.quality-test])
(apply require suites)
(let [{:keys [fail error]} (apply t/run-tests suites)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
