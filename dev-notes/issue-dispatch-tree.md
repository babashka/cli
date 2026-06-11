# dispatch: accept the (already public) tree format directly

## Problem

`table->tree` is public API (#150): its output shape (nested `:cmd` maps) is
documented in API.md, `format-command-help` accepts "a `dispatch` table, or a
tree from `table->tree`" (README:708), and custom `:help-fn`/`:error-fn`
receive the tree as `:tree`. But `dispatch` itself rejects it:

```clojure
(cli/dispatch {:cmd {"add" {:fn add}}} args {:help true})
;; => clojure.lang.MapEntry cannot be cast to clojure.lang.IPersistentMap
```

because it calls `(table->tree table)` unconditionally.

The tree is the natural shape for config-style CLIs (single file, written by
hand): group docs live on the group node, no path repetition. It is also the
shape the babashka bb.edn `:tasks` integration wants to put under a task's
`:cli` key, verbatim. Structurally it matches what modern data-driven CLI
libs converge on (citty/gunshi `subCommands: {name: cmd}`). See
`dev-notes/subcommand-syntax-survey.md` for the full survey.

## Proposal

### 1. `dispatch` accepts a tree

Same check `format-command-help` already does: `(if (map? table) table
(table->tree table))`. The hidden completions command and `:help` injection
already operate on the converted tree, so they should fall out; cover with
tests anyway.

```clojure
(cli/dispatch
 {:spec {:format {:desc "edn or table"}}        ; root-level options
  :cmd  {"outdated" {:fn deps/outdated}
         "cache"    {:doc "Manage cache"
                     :cmd {"clean" {:fn deps/clean-cache}}}}}
 *command-line-args*
 {:help true :prog "deps"})
```

### 2. Document the tree as an input format

README subcommands section: show the tree next to the table, state that
`dispatch` and `format-command-help` accept either, and that `table->tree`
converts. Document the node keys (`:cmd`, `:fn`, `:spec`, `:doc`, plus the
per-entry parse opts that tables already allow).

Must include the map-ordering gotcha: EDN map literals with more than 8
entries become hash-maps, so command order in help/completions is arbitrary
beyond 8 children (same caveat `:spec` documents today).

### 3. Subcommand display order

For nodes with more than 8 children (or anyone wanting explicit order).
Two candidate mechanisms, decide one (or both):

- A node-level order vector of child command names, mirroring how option
  display order works. NAMING CONFLICT: `:order` is taken - it already means
  option display order on a command entry/node (vector of option keyword
  keys). A node can legitimately want both. So either:
  - a new key, e.g. `:cmd-order` (vector of child name strings), or
  - overload `:order` to accept strings (commands) mixed with keywords
    (options) - rejected on sight, too clever.
- Accept `:cmd` as a vec-of-pairs, mirroring vec-of-pairs `:spec`
  (cli.cljc:1506 already points users at "a vec-of-pairs spec or :order"):

  ```clojure
  {:cmd [["enable"  {:fn ops/enable}]
         ["disable" {:fn ops/disable}]]}
  ```

  Keeps order by construction, no new key, no `:order` collision.
  Normalize recursively at the `dispatch`/`format-command-help` boundary;
  the internal canonical form stays the map (the walker looks children up
  by name at every step).

Lean: vec-of-pairs (consistency with `:spec`, no naming problem); add
`:cmd-order` only if pairs prove insufficient.

Wherever order is known it must reach BOTH help rendering
(`visible-command-names` currently just iterates the `:cmd` map) and shell
completions (subcommand candidates should complete in display order).

## Out of scope

- Renaming the `:cmd` key (e.g. to `:commands`): shape shipped in
  0.10.69/0.11.70 API.md + README; map-by-name is the right internal
  structure; rename buys aesthetics only.
- A new cli-matic-style `:subcommands [{:command "x" ...}]` shape: the tree
  already covers recursive declaration (survey doc, "Resolution" section).

## Tests

- dispatch with tree: nested (2 levels), root `:spec` options, `:help true`
  (--help at every level), error paths (unknown subcommand, flag error),
  `:inherit` options declared on tree nodes.
- format-command-help/format-command-error parity table vs tree (same
  output for `table->tree`-equivalent inputs).
- completions against a tree-built dispatch, including order.
- vec-of-pairs `:cmd`: order in help + completions, >8 children.
- cljc: run in both clj and cljs suites.

## Motivation / context

Feeds babashka bb.edn task integration (`:cli` key on tasks holds a tree
verbatim; plan in borkdude's notes). Survey of 16 CLI libs and the decision
trail: `dev-notes/subcommand-syntax-survey.md`.
