# Subcommand syntax survey: flat table vs recursive nesting

Research notes (2026-06-11) for deciding how subcommand trees should be
declared, both in babashka.cli and in babashka's planned bb.edn `:cli` task
integration. Question: keep the flat dispatch table (`:cmds` path vectors) as
the only user-facing shape, or add a recursive shape, and if so which one?

Running example everywhere: a `deps` tool with `outdated` (has `--format`),
and a `cache` group containing `clean`.

## Clojure libraries

### babashka.cli today: flat table with path vectors

```clojure
[{:cmds ["outdated"]      :fn deps/outdated :spec {:format {:desc "edn or table"}}}
 {:cmds ["cache"]         :doc "Manage cache"}
 {:cmds ["cache" "clean"] :fn deps/clean-cache}]
```

- Every command is one self-contained entry at fixed depth; uniform shape.
- Nesting = longer path; group name repeats per descendant.
- Group doc requires a dangling `:fn`-less entry (mild wart).
- Greppable: full path on one line. Diff-friendly: one entry per change.

### cli-matic: recursive, vector of self-named maps

```clojure
{:command     "deps"
 :description "Dependency tools"
 :subcommands [{:command "outdated"
                :opts    [{:option "format" :as "edn or table" :type :string}]
                :runs    deps/outdated}
               {:command     "cache"
                :description "Manage cache"
                :subcommands [{:command "clean"
                               :runs    deps/clean-cache}]}]}
```

- `:subcommands` recursive, unlimited depth (README shows 2 levels).
- Vector preserves help order; each node a uniform map naming itself via
  `:command`. No pairs trick, no map-ordering loss.
- Own option language (`:option`/`:as`/`:type`; `:short 0` doubles as
  positional index).

### hlship/cli-tools: macro per command + namespace discovery

```clojure
(dispatch {:tool-name "deps"
           :namespaces '[tool.commands]                     ; outdated lives here
           :groups {"cache" {:doc "Manage cache"
                             :namespaces '[tool.cache-cmds] ; clean lives here
                             :groups {}}}})                 ; recursive
```

- Commands are `defcommand` macros (interface vector mixes options and
  `:args` positionals bound to locals); the tree is a recursive `:groups`
  map keyed by group name, commands discovered from namespaces.
- Different paradigm: tree = code organization, not one data literal.

### lambdaisland.cli: recursive pairs-vector

```clojure
{:commands ["outdated" {:command ops/outdated
                        :flags ["--format <fmt>" "edn or table"]}
            "cache"    {:doc "Manage cache"
                        :commands ["clean" #'ops/clean-cache]}]}
```

- `:commands` = vector of alternating name/config pairs (vector chosen to
  preserve order, since EDN maps do not). Config can be a map, var (doc +
  flags from var meta), or doc string.
- Flags are flagstrings (`"-v, --verbose"`, `"--input FILE"`): a second
  option mini-language. MPL-2.0: ideas only, never copy code.

## World libraries

Everything else is imperative chaining (commander.js, cac, cliffy),
filesystem-as-tree (oclif), or decorators/types (click, typer, clap derive,
Swift ArgumentParser). The two DATA shapes that exist:

### Recursive map keyed by name: citty (unjs) and gunshi

The modern data-driven JS libs; near-identical shape, `defineCommand` is an
identity function for typing:

```javascript
defineCommand({
  meta: { name: "deps", description: "Dependency tools" },
  args: { format: { type: "string", description: "edn or table" } },
  subCommands: {
    outdated: defineCommand({ run({args}) { /* ... */ } }),
    cache: defineCommand({
      meta: { description: "Manage cache" },
      subCommands: { clean: defineCommand({ run() { /* ... */ } }) },
    }),
  },
})
```

- Fully recursive plain object; `run` is a sibling key; args map covers both
  flags and positionals (`type: "positional"`); lazy subcommands via thunks.
- EDN caveat: `subCommands` keyed by name relies on JS object insertion
  order for help ordering. EDN maps lose that. A faithful Clojure port needs
  a vector somewhere (cli-matic's self-named maps, or lambdaisland's pairs).

### Flat path vectors: clipanion (powers yarn)

```typescript
class CleanCommand extends Command {
  static paths = [['cache', 'clean']];   // router assembles the tree
  url = Option.String('--url', { description: 'remote url' });
  async execute() { /* ... */ }
}
cli.register(CleanCommand);
```

- Literally babashka.cli's `:cmds ["cache" "clean"]`. Multiple paths per
  command = aliases as data. Favored where commands register from many
  files; the router derives the tree.

### Brief notes, other languages

- yargs: command-module object `{command, describe, builder, handler}` is
  data for ONE level; nesting forces a builder function or `commandDir`
  (filesystem). Positionals encoded in the command string. Avoid this split.
- oclif: filesystem dirs = tree; flags are a data map on the class. Lesson:
  topic (group) descriptions live in package.json, far from the commands,
  universally disliked. Group docs must live with the data. Also notable:
  configurable topic separator (colon, Heroku-style, vs space) where both
  keep working - relevant precedent for bb task naming (`maintenance:enable`
  vs `maintenance enable`).
- cobra (Go): `&cobra.Command{Use, Short, Run}` struct literal is map-like
  with `Run` as sibling key (citty-shaped), but tree assembly
  (`AddCommand`) and flag wiring are imperative. Persistent (inherited)
  flags = our `:inherit`.
- clap derive (Rust): tree as nested types (enum of structs), doc comments
  become help. Shape idea = sum type; not portable as syntax.
- click/typer (Python): decorator/group nesting, tree is code.
- Swift ArgumentParser: fully declarative; `subcommands: [Add.self, ...]`
  literal, `defaultSubcommand:` as data, `@OptionGroup` for shared option
  bundles. Concepts worth borrowing even though types are not.

## Comparison

| Library        | Tree shape                          | Options shape            | EDN fit |
|----------------|-------------------------------------|--------------------------|---------|
| babashka.cli   | flat entries, `:cmds` path vectors  | spec maps                | native  |
| cli-matic      | recursive `:subcommands` vector of self-named maps | own opt maps | clean   |
| cli-tools      | recursive `:groups` + ns discovery  | macro interface vector   | partial |
| lambdaisland   | recursive pairs-vector              | flagstrings              | awkward |
| citty/gunshi   | recursive obj keyed by name         | data map per arg         | needs order fix |
| clipanion      | flat path vectors                   | class props              | path idea = ours |
| yargs          | object per command, nesting breaks data | data map + cmd string | one level only |
| oclif          | filesystem                          | data map                 | no      |
| cobra          | struct literals + imperative wiring | imperative               | half    |
| clap/Swift/click | types/decorators                  | typed fields             | no      |

## Takeaways

1. Ecosystem converges on recursive data for config-style CLIs (citty,
   gunshi, cli-matic, Swift, cobra literals). Flat-with-paths is the
   minority but ships in yarn (clipanion); it suits registration spread
   across files - the opposite of the single-file bb.edn/config case.
2. The citty shape keyed-by-name does not port to EDN directly (map
   ordering). The EDN-correct equivalent is cli-matic's vector of
   self-named maps.
3. Group docs must live on the group node (oclif anti-lesson). Flat tables
   put them in dangling `:fn`-less entries.
4. Flat vs recursive editing tradeoffs (from earlier bb.edn discussion):
   flat = uniform shallow entries, greppable full paths, clean diffs, name
   repetition per descendant; recursive = mirrors the tree, no repetition,
   but deeper brackets (bb.edn already starts at depth 3) and diffs show
   indentation churn. Task CLIs are usually 1 level deep, where the shapes
   barely differ.

## babashka.cli's EXISTING nested format: the tree

Overlooked at first: bb.cli already has a recursive format - the tree,
output of the public `table->tree` (#150, cli.cljc:1008). Children nest
under `:cmd` as a map keyed by command name:

```clojure
{:doc  "Dependency tools"
 :spec {:format {:desc "edn or table"}}
 :cmd  {"outdated" {:fn deps/outdated}
        "cache"    {:doc "Manage cache"
                    :cmd {"clean" {:fn deps/clean-cache}}}}}
```

Status: `format-command-help` accepts table OR tree (map? check,
cli.cljc:1513), but `dispatch` calls `(table->tree table)` unconditionally
(cli.cljc:1882) so it does NOT accept a tree yet - a two-line fix. The
walker `dispatch-tree` is private.

Contrast:
- vs citty/gunshi: structurally identical (`subCommands: {name: cmd}` =
  `:cmd {"name" node}`). bb.cli already has the modern JS shape.
- vs cli-matic: cli-matic's `:subcommands` vector preserves help order;
  the tree's `:cmd` map does not - `visible-command-names` iterates the map
  (cli.cljc:1621), and EDN map literals >8 entries silently become
  hash-maps with arbitrary order. cli-matic nodes self-name (`:command`),
  tree nodes are named by their key (terser, no repetition).
- vs lambdaisland pairs: pairs exist precisely to fix map ordering, at the
  cost of awkward alternating name/config EDN.
- vs the flat table: group doc lives on the group node (no dangling
  `:fn`-less entry), no path repetition; loses one-line greppable paths
  and uniform shallow entry depth.

If the tree is blessed as user-facing input, the gaps are:
1. `dispatch` accepting a map as `table` (same check format-command-help
   does).
2. Ordering: document the >8-children hash-map promotion, or add a node
   `:order` vector (precedent: option `:order` already exists in help
   rendering), or accept vector-of-pairs for `:cmd`.
3. The `:cmd` key collision with the candidate shape below becomes moot if
   the existing tree is adopted instead.

### Resolution (2026-06-11, Michiel): bless the tree

Document the tree as user-facing input and fix ordering the way `:spec`
already does (cli.cljc:1506 documents map order as unreliable beyond a few
keys, pointing at vec-of-pairs or `:order`):

- `:cmd` accepts a map OR a vec-of-pairs
  (`:cmd [["enable" {...}] ["disable" {...}]]` keeps order);
- node-level `:order` vector sets command display order (mirrors option
  `:order`);
- normalize at the `dispatch`/`format-command-help` ingestion boundary
  (recursively); internal canonical form stays the map - the walker looks
  children up by name at each step;
- `dispatch` gets the same map?-as-tree check `format-command-help` has.

Keep the `:cmd` key name. The tree shape is already semi-public
(`table->tree` public output since #150, `:tree` threaded into custom
`:help-fn`/`:error-fn` data, `format-command-help` input), shipped in
0.10.69/0.11.70. Adoption is days old so a rename WAS still cheap, but no
change is worth it: map-by-name is the right internal structure and the
name buys only aesthetics.

This supersedes the cli-matic-style candidate below (kept for the record).

## Candidate: recursive shape for babashka.cli (proposal, undecided)

Merge cli-matic's structure with babashka.cli's existing spec language.
Every node has the same keys; `:spec` at a level = that level's options:

```clojure
{:spec {:format {:desc "edn or table"}}        ; this level's options
 :subcommands [{:cmd "outdated"
                :fn  deps/outdated}
               {:cmd "cache"
                :doc "Manage cache"
                :subcommands [{:cmd "clean"
                               :fn  deps/clean-cache}]}]}
```

- Ordered (vector), uniform node shape, recursive, group doc on the group.
- `dispatch` could accept it natively via a `subcommands->table` converter
  next to the existing `table->tree`; the flat table stays supported and
  canonical (programmatic generation, multi-file registration), recursive
  is sugar. World precedent for exactly this split: clipanion's path router
  under tree-shaped APIs.
- Name collision check: `table->tree` produces nodes with `:cmd` as a MAP
  of children (`{:cmd {"add" {...}}}`); the proposal uses `:cmd` as the
  node's own name string. Same keyword, different meaning - resolve before
  shipping (rename one: e.g. proposal could use `:command` like cli-matic).

Context: this feeds the bb.edn `:cli` task integration plan in
`~/Dropbox/notes/ductile/plan-cli.md` ("bb tasks port" section). Decision on
the recursive shape: OPEN as of 2026-06-11.
