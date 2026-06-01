# Spike status: auto-help for babashka.cli subcommands

Tracks the `scratch/` spike on branch `help-spike`. Goal: conventional
auto-generated `--help` for `dispatch`-based subcommand CLIs, plus the parsing
features it needs. Motivated by ductile bb-task restructure (nextjournal/ductile#7961).

## Merged to babashka.cli main

These started as spike findings and landed as real library changes:

- #150 - `table->tree` made public; flag-level error data carries `:dispatch`
  (the matched subcommand path) so an `:error-fn` can render help for the right level.
- #151 - `:restrict` composes with shared/parent options (inherited opts passed
  via `::dispatch-inherited`, not flagged as unknown at child levels).
- #152 - `:inherit` options: per-option `{:x {:inherit true}}` and dispatch-level
  `{:inherit true}` / `{:inherit #{ks}}`. An inherited option is accepted before
  and after its subcommand, coerced + restrict-checked wherever it appears;
  a descendant spec may redefine it (descendant wins).

## In the spike (not yet in the library)

`scratch/help_spike.clj`:

- `format-help` - renders conventional `--help` for any dispatch-tree node:
  `Usage:` / description / `Commands:` / `Options:` / `Inherited options:`
  (for `:inherit` flags usable at that level) / a "run parent --help" pointer
  for non-inherited parent options that must precede the subcommand.
  Man-page section convention; no code taken from other CLI libs.
- `dispatch+help` - opt-in wrapper over `cli/dispatch`: auto `--help`/`-h` at
  every level, `:restrict true` default, help-on-error (unknown command / bad flag).
- `wrap`/`prep` inject a `--help` flag and wrap each `:fn`.

`scratch/duct` - runs the spike as a real CLI under bb (reloads local
`babashka.cli` over bb's builtin, since the builtin shadows the classpath).

## Run it

    cd ~/dev/cli
    ./scratch/duct deps outdated --help     # Options + Inherited options
    ./scratch/duct deps outdated --registry X --format edn   # inherited flag parses
    ./scratch/duct dev -tp                  # short flags
    ./scratch/duct nope                     # unknown command -> help

(Direct calls only; `for a in "multi word"` mangles argv.)

## Open / next

- Promote `format-help` + `dispatch+help` into the library proper (tests,
  README, CHANGELOG). Decide the public API surface (a `:helpful` opt on
  `dispatch`, or a separate fn, or a documented recipe).
- Decide whether dispatch-level `:inherit` should also drive the help display
  in the library version (spike already reflects it).
- Apply to ductile `bb.edn`: `bb dev` + flags, `bb maintenance enable`,
  `bb deps outdated`, etc. (the original goal in plan-cli.md).
