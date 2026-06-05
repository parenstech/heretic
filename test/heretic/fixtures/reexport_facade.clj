(ns heretic.fixtures.reexport-facade
  "Fixture: re-exports reexport-core/answer BY VALUE via a top-level `def`. The
   `def` captures the function at load-time, so reloading ONLY reexport-core
   leaves `answer` here pointing at the pre-mutation function — the exact
   stale-re-export shape that produced heretic false survivors. A correct reload
   must also reload this namespace so the `def` re-evaluates."
  (:require [heretic.fixtures.reexport-core :as core]))

(def answer core/answer)
