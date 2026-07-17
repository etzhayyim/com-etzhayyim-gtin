#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec bb --classpath src:test -e '(require (quote clojure.test) (quote gtin.murakumo-test) (quote gtin.mesh-manifest-test)) (let [r (clojure.test/run-tests (quote gtin.murakumo-test) (quote gtin.mesh-manifest-test))] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))'
