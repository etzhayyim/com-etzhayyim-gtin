(ns gtin.mesh-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- read-edn [path]
  (edn/read-string (slurp (io/file path))))

(deftest canonical-edn-deployment-contract
  (let [manifest (read-edn "kotoba.app.edn")
        component (first (:kotoba.app/components manifest))
        schema (read-edn "schema.edn")]
    (testing "the independent repository owns its mesh source"
      (is (= "gtin" (:kotoba.app/name manifest)))
      (is (= "methods/mesh.clj" (:src component)))
      (is (.isFile (io/file (:src component)))))
    (testing "KQE is an explicit capability dependency"
      (is (= #{:cap/kqe} (:requires component)))
      (is (= [{:type :kse :topic "etzhayyim/actor/gtin"}]
             (:triggers component))))
    (testing "canonical deployment EDN is structured, not EDN encoded in strings"
      (is (vector? (:kotoba.app/components manifest)))
      (is (map? (:kotoba.app/placement manifest))))
    (testing "the repository owns the schema needed to load the app manifest"
      (is (= #{:kotoba.app/components
               :kotoba.app/name
               :kotoba.app/placement
               :kotoba.app/version}
             (set (map :db/ident schema)))))))

(deftest mesh-keeps-the-observatory-boundary
  (let [source (slurp (io/file "methods/mesh.clj"))]
    (is (re-find #"kqe-assert!" source))
    (is (re-find #"kqe-query" source))
    (is (re-find #"defn on-kse" source))))
