(ns heretic.coverage-map.registry
  "ClojureStorm FormRegistry access.

   This module interfaces with ClojureStorm's FormRegistry to retrieve
   form metadata needed for coverage mapping.

   Requires ClojureStorm to be on the classpath with instrumentation enabled."
  (:import [clojure.storm FormRegistry]))

(defn get-form-registry
  "Get all forms from ClojureStorm's FormRegistry.

   Returns {form-id -> {:form/ns, :form/emitted-coords, ...}}

   ClojureStorm stores emitted-coords in the metadata of :form/form
   under :clojure.storm/emitted-coords as a java.util.HashSet.
   This function extracts and converts it to a Clojure set for each form.

   Note: :form/form is excluded from the result as it may contain unserializable
   data (regex literals, reader macros) that can't be round-tripped through EDN.

   Form entry structure from FormRegistry:
   {:form/id       Long (hash)
    :form/ns       String
    :form/def-kind Keyword (:defn, :def, etc.)
    :form/file     String (relative path)
    :form/line     Integer
    :form/emitted-coords Set of coord strings}"
  []
  (into {}
        (for [form (FormRegistry/getAllForms)]
          [(:form/id form)
           (-> form
               (assoc :form/emitted-coords
                      (-> form :form/form meta :clojure.storm/emitted-coords set))
               ;; Remove :form/form as it may contain unserializable data
               (dissoc :form/form))])))
