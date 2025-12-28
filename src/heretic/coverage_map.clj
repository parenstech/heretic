(ns heretic.coverage-map
  "Coverage index building and queries. Facade namespace.

   This namespace re-exports the public API from sub-modules:
   - heretic.coverage-map.registry - ClojureStorm FormRegistry access
   - heretic.coverage-map.index - Pure index building and queries
   - heretic.coverage-map.workflow - Collection workflow orchestration

   Storage layout:
   .heretic/
   ├── meta.edn           # Global metadata + form registry
   ├── coverage/
   │   ├── ns1-test.edn   # Per-namespace coverage
   │   └── ns2-test.edn
   └── index.edn          # Derived inverse index"
  (:require [heretic.coverage-map.index :as index]
            [heretic.coverage-map.registry :as registry]
            [heretic.coverage-map.workflow :as workflow]
            [heretic.form-bridge :as bridge]
            [heretic.persistence :as persist]))

;; =============================================================================
;; Re-exports from registry
;; =============================================================================

(def get-form-registry
  "Get all forms from ClojureStorm's FormRegistry.
   See heretic.coverage-map.registry/get-form-registry for details."
  registry/get-form-registry)

;; =============================================================================
;; Re-exports from form-bridge
;; =============================================================================

(def build-form-location-index
  "Build a lookup from [file line] -> form-id for bridging mutation sites to coverage.
   See heretic.form-bridge/build-form-location-index for details."
  bridge/build-form-location-index)

;; =============================================================================
;; Re-exports from index
;; =============================================================================

(def build-inverse-index
  "Build form+coord -> tests index from coverage data.
   See heretic.coverage-map.index/build-inverse-index for details."
  index/build-inverse-index)

(def tests-for-location
  "Given a form-id and optional coord, return tests that hit it.
   See heretic.coverage-map.index/tests-for-location for details."
  index/tests-for-location)

(def uncovered-coords
  "Find coordinates that have no test coverage.
   See heretic.coverage-map.index/uncovered-coords for details."
  index/uncovered-coords)

;; =============================================================================
;; Re-exports from workflow
;; =============================================================================

(def extract-source-deps
  "Given coverage data, determine which source files were touched.
   See heretic.coverage-map.workflow/extract-source-deps for details."
  workflow/extract-source-deps)

(def collect-test-namespace!
  "Collect coverage for a single test namespace.
   See heretic.coverage-map.workflow/collect-test-namespace! for details."
  workflow/collect-test-namespace!)

(def rebuild-index!
  "Rebuild inverse index from all coverage files.
   See heretic.coverage-map.workflow/rebuild-index! for details."
  workflow/rebuild-index!)

(def collect-and-persist!
  "Full collection workflow: collect coverage and persist to disk.
   See heretic.coverage-map.workflow/collect-and-persist! for details."
  workflow/collect-and-persist!)

;; =============================================================================
;; Convenience functions (thin wrappers)
;; =============================================================================

(defn load-index
  "Load the inverse index from disk."
  [heretic-dir]
  (persist/load-index heretic-dir))
