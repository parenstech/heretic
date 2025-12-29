# Worktree-Based Mutation Testing

Heretic tests itself using a git worktree to avoid the mutation engine corrupting its own source code during testing.

## Why Worktree?

When mutation testing runs, it:
1. Modifies source files (applies mutations)
2. Runs tests
3. Reverts modifications

If heretic mutates its own code while running, it can corrupt itself mid-execution. A worktree provides an isolated copy that can be safely mutated.

## Setup

### 1. Create the Worktree

```bash
cd ~/lab/projects/heretic
git worktree add ../heretic-under-test HEAD
```

This creates a copy at `~/lab/projects/heretic-under-test` sharing git history but with independent working files.

### 2. Start Worktree REPL

```bash
cd ~/lab/projects/heretic-under-test
clojure -M:dev:test
```

Note the nREPL port (e.g., 34459). This REPL has:
- The code being tested loaded
- ClojureStorm for instrumentation
- Test framework available

### 3. Start Main Heretic REPL

```bash
cd ~/lab/projects/heretic
clojure -M:dev:clojurestorm
```

This REPL has the mutation engine.

## Running Mutation Tests

### In Worktree REPL (port 34459)

Set up the test runner:

```clojure
(require '[clojure.test :as test])
(require '[heretic.equivalent-test] :reload)

(defn run-tests! []
  (let [results (test/run-tests 'heretic.equivalent-test)]
    {:pass (:pass results)
     :fail (:fail results)
     :error (:error results)
     :passed? (and (zero? (:fail results)) (zero? (:error results)))}))
```

### In Main REPL (port 44444)

```clojure
(require '[heretic.mutation-engine :as engine])
(require '[heretic.operators :as ops])
(require '[nrepl.core :as nrepl])

;; Connect to worktree REPL
(def worktree-conn (nrepl/connect :port 34459))
(def worktree-client (nrepl/client worktree-conn 30000))

(defn run-worktree-tests! []
  (let [response (nrepl/message worktree-client
                   {:op "eval"
                    :code "(do (require '[heretic.equivalent :reload])
                              (require '[heretic.equivalent-test :reload])
                              (run-tests!))"})]
    (-> response nrepl/response-values first read-string)))

;; Generate mutations
(def worktree-file "/home/yenda/lab/projects/heretic-under-test/src/heretic/equivalent.clj")
(def mutations (engine/mutations-for-file worktree-file ops/all-operators))

;; Test a single mutation
(defn test-mutation [m]
  (let [m-with-backup (engine/apply-mutation! m)
        result (run-worktree-tests!)]
    (engine/revert-mutation! m-with-backup)
    (assoc m :status (if (:passed? result) :survived :killed))))

;; Run all mutations
(def results
  (doall
    (map-indexed
      (fn [i m]
        (when (zero? (mod i 50))
          (println "Progress:" i "/" (count mutations)))
        (test-mutation m))
      mutations)))

;; Summary
(let [killed (count (filter #(= :killed (:status %)) results))
      total (count results)]
  (println "Score:" (format "%.1f%%" (* 100.0 (/ killed total)))))
```

## Workflow

```
┌─────────────────────────────────────────────────────────────┐
│                      MAIN BRANCH                            │
│  ~/lab/projects/heretic                                     │
│                                                             │
│  1. Write/improve tests here                                │
│  2. Run mutation engine from here                           │
│  3. Analyze survivors, improve tests                        │
│  4. Commit improvements                                     │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ git worktree add
                           │ (or git pull to sync)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                      WORKTREE                               │
│  ~/lab/projects/heretic-under-test                          │
│                                                             │
│  • Isolated copy for mutation testing                       │
│  • Gets mutated during testing                              │
│  • Can be reset/destroyed without losing work               │
│  • Runs tests via its own REPL                              │
└─────────────────────────────────────────────────────────────┘
```

### After Improving Tests on Main

Sync changes to worktree:

```bash
cd ~/lab/projects/heretic-under-test
git checkout src/  # Reset any leftover mutations
git pull origin main --rebase  # Or: git reset --hard origin/main
```

Then reload in worktree REPL:

```clojure
(require '[heretic.equivalent] :reload)
(require '[heretic.equivalent-test] :reload)
```

## Cleanup

Remove worktree when done:

```bash
cd ~/lab/projects/heretic
git worktree remove ../heretic-under-test
```

## Performance Notes

- **nREPL approach**: ~1-2s per mutation (fast)
- **Subprocess approach**: ~15-20s per mutation (slow - JVM startup)

Always use nREPL to trigger test runs in the worktree REPL rather than spawning new JVM processes.

## Troubleshooting

### Worktree has corrupted files

```bash
cd ~/lab/projects/heretic-under-test
git checkout .  # Reset all files to HEAD
```

### Mutation not reverted properly

The mutation engine stores backup in the mutation record. Always use the returned value from `apply-mutation!`:

```clojure
;; WRONG - original mutation has no backup
(engine/apply-mutation! m)
(engine/revert-mutation! m)  ; Fails!

;; RIGHT - use returned mutation with backup
(let [m-with-backup (engine/apply-mutation! m)]
  (engine/revert-mutation! m-with-backup))  ; Works
```

### REPL has stale code

After modifying files, always reload:

```clojure
(require '[the.namespace] :reload)
```
