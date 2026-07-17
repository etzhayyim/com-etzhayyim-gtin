(ns gtin.repository-contract-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- read-edn [path] (edn/read-string (slurp path)))

(deftest canonical-metadata-contract
  (let [manifest (read-edn "manifest.edn")
        identity (read-edn "identity.edn")
        contracts (read-edn "repository-contracts.edn")]
    (is (= "manifest.edn" (:canonical-actor-metadata contracts)))
    (is (= "identity.edn" (:canonical-identity-metadata contracts)))
    (is (= (:actor/did manifest) (:identity/did identity)))
    (is (not (.exists (io/file "actor-manifest.jsonld"))))))

(deftest did-json-is-an-external-wire-artifact
  (let [identity (read-edn "identity.edn")
        wire (json/parse-string (slurp ".well-known/did.json"))]
    (testing "the published DID document is derived from canonical identity values"
      (is (= (:identity/did identity) (get wire "id")))
      (is (= (set (:identity/also-known-as identity))
             (set (get wire "alsoKnownAs")))))))
