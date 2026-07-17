;; mesh.clj — gtin KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:gtin (GS1 GTIN product-identifier registry).
;; Observatory on-kse pattern (ADR-2606230001 §4): observes gtin→product identity
;; edges as Datom assertions and derives the identifier registry via Datalog. The
;; full validate/resolve/merge surface stays in the actor's existing methods.
;;
;; Posture: a public product-identifier registry (GS1 GTIN, mod-10 validated);
;; identity facts, non-adjudicating.
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns gtin)

(defn observe []
  ;; observe — GTINs identifying trade items (public identifiers).
  (kqe-assert! "gtin" "gtin-0001" "identifies" "product-x")
  (kqe-assert! "gtin" "gtin-0002" "identifies" "product-x")
  (kqe-assert! "gtin" "gtin-0003" "identifies" "product-y")
  ;; derive — identifier → product registry (Datalog).
  (kqe-query "registry(?p) :- identifies(?p)."))

(defn run [ctx] (observe))
(defn on-kse [topic payload] (observe))
