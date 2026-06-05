(ns heretic.fixtures.reexport-core
  "Fixture for the re-export false-survivor regression (see
   heretic.reloader-test). `answer` is the function the test mutates on disk to
   simulate a mutation in a namespace whose var is re-exported elsewhere.")

(defn answer [] :original)
