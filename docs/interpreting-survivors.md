# Interpreting Surviving Mutations

When mutations survive, it means your tests didn't detect the code change. This guide explains common survivor patterns and how to fix the underlying test gaps.

## Quick Reference

| Survivor Pattern | Root Cause | Fix |
|------------------|------------|-----|
| `first` ↔ `last` | Single-element test data | Use 2+ element collections |
| `>` ↔ `>=` or `<` ↔ `<=` | Missing boundary tests | Test equality case |
| `and` ↔ `or` | Single branch coverage | Test both true/false paths |
| `+` ↔ `-` or `*` ↔ `/` | Symmetric test values | Use asymmetric values |
| `nil` return survived | Return value not checked | Assert on return values |
| `[]`/`{}`/`#{}` return | Empty collection not tested | Test empty case handling |
| `inc` ↔ `dec` | Off-by-one not caught | Test boundary values |
| `take` ↔ `drop` | Collection size masks diff | Test with varied sizes |

## Detailed Patterns

### Collection Endpoint Swaps (`first`/`last`)

**Survivor:** `first` mutated to `last` (or vice versa)

**Root cause:** Tests use single-element collections like `[42]` where `first` and `last` return the same value.

**Fix:** Use multi-element collections where order matters:
```clojure
;; Bad: single element masks the difference
(is (= 42 (get-first-item [42])))

;; Good: multiple elements expose the semantics
(is (= 1 (get-first-item [1 2 3])))
(is (= 3 (get-last-item [1 2 3])))
```

---

### Comparison Operator Swaps (`>` vs `>=`)

**Survivor:** `>` mutated to `>=` (or `<` to `<=`, etc.)

**Root cause:** Test data never hits the boundary where values are equal. If you test with `5 > 3` and `2 > 3`, the equality case `3 >= 3` is never exercised.

**Fix:** Add boundary value tests:
```clojure
;; Bad: never tests the boundary
(is (true? (above-threshold? 10 5)))   ; 10 > 5
(is (false? (above-threshold? 3 5)))   ; 3 > 5

;; Good: explicitly test the boundary
(is (true? (above-threshold? 10 5)))   ; clearly above
(is (false? (above-threshold? 5 5)))   ; exactly equal - the boundary!
(is (false? (above-threshold? 3 5)))   ; clearly below
```

---

### Boolean Operator Swaps (`and`/`or`)

**Survivor:** `and` mutated to `or` (or vice versa)

**Root cause:** Tests only exercise one logical branch. If all tests have both conditions true (or both false), `and` and `or` behave identically.

**Fix:** Test all truth table combinations:
```clojure
;; Bad: both conditions always true
(is (allowed? {:admin true :active true}))

;; Good: test the distinguishing cases
(is (true? (allowed? {:admin true :active true})))    ; both true
(is (false? (allowed? {:admin true :active false})))  ; one false
(is (false? (allowed? {:admin false :active true})))  ; other false
```

---

### Arithmetic Operator Swaps (`+`/`-`/`*`/`/`)

**Survivor:** `+` mutated to `-` (or `*` to `/`, etc.)

**Root cause:** Symmetric or identity values where operations produce same result:
- `0 + x = 0 - (-x)` when negation happens elsewhere
- `x * 1 = x / 1`
- `x + 0 = x - 0`

**Fix:** Use asymmetric, non-identity values:
```clojure
;; Bad: identity values
(is (= 5 (calculate 5 1)))  ; 5 * 1 = 5 / 1

;; Good: asymmetric values that distinguish operations
(is (= 15 (calculate 5 3)))  ; 5 * 3 = 15, but 5 / 3 ≠ 15
```

---

### Return Value Mutations (`nil`, empty collections)

**Survivor:** Function return mutated to `nil`, `[]`, `{}`, or `""`

**Root cause:** Tests verify side effects but not return values, or don't test what happens when the function returns empty/nil.

**Fix:** Assert on return values explicitly:
```clojure
;; Bad: only checks side effect
(process-items items)
(is (= 3 @processed-count))

;; Good: also verify return value
(let [result (process-items items)]
  (is (= 3 @processed-count))
  (is (= [:a :b :c] result)))  ; catches nil/[] mutation
```

---

### Increment/Decrement Swaps (`inc`/`dec`)

**Survivor:** `inc` mutated to `dec` (or vice versa)

**Root cause:** Tests don't verify exact numeric results, or boundary values aren't tested.

**Fix:** Test exact values and boundaries:
```clojure
;; Bad: only tests general behavior
(is (pos? (next-index 5)))

;; Good: test exact increment behavior
(is (= 6 (next-index 5)))
(is (= 1 (next-index 0)))  ; boundary
```

---

### Sequence Operation Swaps (`take`/`drop`, `rest`/`next`)

**Survivor:** `take` mutated to `drop` or `rest` to `next`

**Root cause:** Collection sizes or nil handling masks the difference.

**Fix:** Use collections where the operations produce visibly different results:
```clojure
;; Bad: small collection masks difference
(is (seq (get-items [1])))

;; Good: larger collection distinguishes operations
(is (= [1 2] (take-first-two [1 2 3 4 5])))  ; take 2
;; drop 2 would give [3 4 5] - clearly different
```

For `rest` vs `next`, test with empty collections:
```clojure
;; rest returns () for empty, next returns nil
(is (= () (safe-tail [])))   ; if using rest
(is (nil? (safe-tail [])))   ; if using next
```

---

### Threading Macro Swaps (`->` vs `->>`)

**Survivor:** `->` mutated to `->>` (or vice versa)

**Root cause:** Functions in the thread are single-arity or the position doesn't matter for the specific data.

**Fix:** Ensure threaded functions are sensitive to argument position:
```clojure
;; Bad: str is variadic, position doesn't matter for single arg
(-> x str)

;; Good: position matters
(-> {:a 1} (assoc :b 2) (dissoc :a))
;; vs
(->> {:a 1} (assoc :b 2) (dissoc :a))  ; different result
```

---

## Systematic Approach

When you see survivors, ask:

1. **What's the mutation?** Identify the operator change
2. **Why didn't tests catch it?** What test data would make original and mutant behave identically?
3. **What's the distinguishing case?** Find inputs where original and mutant differ
4. **Add that test case**

## Test Data Checklist

For robust tests, ensure your test data includes:

- [ ] **Multi-element collections** (not just `[x]`)
- [ ] **Boundary values** (0, 1, -1, empty, exactly equal)
- [ ] **Asymmetric values** (avoid `x op x` patterns)
- [ ] **Both truthy and falsy paths** for conditionals
- [ ] **Empty and nil cases** for optional values
- [ ] **Return value assertions** (not just side effects)

## Common Anti-Patterns

### The "Happy Path Only" Problem
```clojure
;; Only tests success case
(is (= "ok" (process valid-input)))
;; Missing: invalid input, edge cases, empty input
```

### The "Truthy is Good Enough" Problem
```clojure
;; Only checks truthiness
(is (some? (get-result)))
;; Missing: actual value assertion
```

### The "One Size Fits All" Problem
```clojure
;; Same test data for everything
(def test-data [1])
;; Missing: varied sizes, empty, large collections
```

## See Also

- [Mutation Testing Best Practices](https://pitest.org/quickstart/mutators/) (Java-focused but concepts apply)
- [Boundary Value Analysis](https://en.wikipedia.org/wiki/Boundary-value_analysis)
