# Changelog

For breaking changes, check [here](#breaking-changes).

[Babashka CLI](https://github.com/babashka/cli): turn Clojure functions into CLIs!

## Unreleased

- Support `:doc` and `:epilog` as a vector of lines, joined with newlines

## v0.12.79

- Verify that `:cmd` is a string, keyword or symbol

## v0.12.78

- Fix `:restrict-args` with valid subcommands

## v0.12.77

- [#206](https://github.com/babashka/cli/pull/206): shell completion: limit option candidates to dash-prefixed words and option-only commands, sort set-valued `:validate` candidates, fix zsh column layout, and prefer nushell candidates over file completion

## v0.12.76 (2026-07-13)

Highlights:

- [#197](https://github.com/babashka/cli/pull/197): [`:positional`](https://github.com/babashka/cli#positional) spec marker: positional args get their own `Arguments:` help section and may not be passed as options
- [#197](https://github.com/babashka/cli/pull/197): [`:restrict-args`](https://github.com/babashka/cli#restrict-args): error on positional args not consumed by `:args->opts`

Minor bugfixes and enhancements:

- [#174](https://github.com/babashka/cli/issues/174): `:edn` `:coerce` option must provide an explicit value
([@lread](https://github.com/lread))
- Introduce `:exec-fn` option in dispatch that passes options directly
- [#194](https://github.com/babashka/cli/pull/194): a var `:fn` / `:exec-fn` contributes its spec and docstring to the command
- [#195](https://github.com/babashka/cli/pull/195): set-valued `:validate` of any primitive: completion + valid values in the error message
- [#197](https://github.com/babashka/cli/pull/197): put brackets around optional args in help output
- [#198](https://github.com/babashka/cli/pull/198): `:cmd` may be a [vector of `[name command]` pairs](https://github.com/babashka/cli#command-formats), preserving command order without `:cmd-order`
- [#199](https://github.com/babashka/cli/pull/199): fix hang on variadic arguments that weren't "collected" (e.g. `(repeat :k)`)
- [#201](https://github.com/babashka/cli/pull/201): `:order` is honored for a vec-of-pairs spec in help output
- [#202](https://github.com/babashka/cli/pull/202): an option token no longer occupies a positional `:args->opts` slot (it used to leave the key with an implicit `true`)
- [#203](https://github.com/babashka/cli/pull/203): `parse-opts*` resolves `:spec` so its `:coerce`/`:collect` entries steer parsing like in `parse-opts` (values still raw)
- [#196](https://github.com/babashka/cli/pull/196): allow symbol or keyword `:cmd` keys, stringified during normalization

## v0.12.75 (2026-06-25)

- Squint support (v0.14.196+)
- Published to npm as [`@babashka/cli`](https://www.npmjs.com/package/@babashka/cli) for [JavaScript](https://github.com/babashka/cli#javascript). Also runs in the browser, e.g. the [squint playground](https://squint-cljs.github.io/squint/?src=gzip%3AH4sIAAAAAAAAEwXBMQ6AMAgAwN1XEBZ1qOxO%2FsM4YENaYtUK1fd7N5g8r5pAv2JurfpMJH5OnmnZeWfPB1MsijCzQyy6jV03xKJU2VwCW3JYkS0hYAh3bXpfCPhxeQW38QcTP68IYAAAAA%3D%3D)
- [#166](https://github.com/babashka/cli/issues/166): trigger negation invalid error for `--no-foo` when `:foo`'s specified `:coerce` is not `:boolean`
([@lread](https://github.com/lread))

## v0.11.74 (2026-06-23)

- [#180](https://github.com/babashka/cli/issues/180): ClojureDart support
- Readability improvements in code ([@lread](https://github.com/lread))

## v0.11.73 (2026-06-19)

- Shell completions: register the running script's file name (from `babashka.file`) in addition to `:prog`, so path invocations (e.g. `./script.clj`) complete without a `:prog`-named symlink. `--prog` may be repeated to register alias names. The program name is no longer restricted to ASCII; non-Latin names are supported.
- [#161](https://github.com/babashka/cli/issues/161), [#163](https://github.com/babashka/cli/issues/163): docs: review and update.
Nomenclature change: subcommand->command.
For babashka cli library users, we used the term "subcommand".
For users of clis created with babashka cli we use the term "command".
They are the same thing.
We now use the term "command" for both audiences.
([@lread](https://github.com/lread))
- [#168](https://github.com/babashka/cli/issues/168): Hide `number-char?` from the public API, it was accidentally included.
([@lread](https://github.com/lread))

## v0.11.72 (2026-06-11)

- [#131](https://github.com/babashka/cli/issues/131): Exclude `scratch.clj` from released jar

## v0.11.71 (2026-06-11)

- `dispatch` now accepts a tree directly (as returned by `table->tree`). See [Tree format](https://github.com/babashka/cli#tree-format)
- Subcommand table order is now preserved in printed help and completions

## v0.11.70 (2026-06-09)

- [#24](https://github.com/babashka/cli/issues/24) / [#95](https://github.com/babashka/cli/pull/95): shell completions for `dispatch` CLIs (`bash`/`zsh`/`fish`/`powershell`/`nushell`): subcommands, options, option and positional values, and file arguments. Based on initial work from 2024 by [@sohalt](https://github.com/sohalt). See [Completions](https://github.com/babashka/cli#completions)
- `:no-doc` now hides a spec option too, not just a subcommand. See [Subcommands](https://github.com/babashka/cli#subcommands)
- `--opt=val` splits on the first `=` only: `--header=k=v` parses as `"k=v"` (everything after the second `=` was dropped before). `--opt=` now parses as an explicit empty-string value instead of a flag

## v0.10.69 (2026-06-06)

- [#112](https://github.com/babashka/cli/issues/112): auto-generated `--help` for `dispatch` CLIs (`Usage` / `Commands` / `Options`, `--help`/`-h` on every (sub)command, terse errors). See [Help](https://github.com/babashka/cli#help)
- `format-opts` two-column help layout, plus terminal-width description wrapping. See [Printing options](https://github.com/babashka/cli#printing-options) and [Terminal width](https://github.com/babashka/cli#terminal-width)
- `:inherit` in `dispatch`: an option usable before or after its subcommand. See [Subcommands](https://github.com/babashka/cli#subcommands)
- `:restrict` no longer flags `:exec-args` keys or parent-level options. See [Restrict](https://github.com/babashka/cli#restrict)
- An option given without a value now reports `Missing value for option --foo`

## v0.9.68 (2026-05-23)

- [#141](https://github.com/babashka/cli/issues/141): docs: briefly cover adding production polish to a cli
([@lread](https://github.com/lread))
- [#144](https://github.com/babashka/cli/issues/144): deployed pom now reflects min supported clojure version & doc supported platforms/versions
([@lread](https://github.com/lread))
- [#147](https://github.com/babashka/cli/issues/147): `opts->table` accepts `:columns` to override auto-detected columns ([@jeeger](https://github.com/jeeger))
- Expose `parse-opts*`: parses args to raw map, no coercion / defaults / validation
- Expose `coerce-opts`: standalone coerce step
- Expose `validate-opts`: standalone `:restrict` / `:require` / `:validate` step
- Add `apply-defaults`: fills missing keys from `:exec-args` or spec `:default`
- Coerce error data includes `:implicit-true true` when the failure was an implicit `--foo` with no value

## v0.8.67 (2025-11-21)

- [#126](https://github.com/babashka/cli/issues/126): `-` value accidentally parsed as option, e.g. `--file -`
- [#124](https://github.com/babashka/cli/issues/124): Specifying exec fn that starts with hyphen is treated as option
- Drop Clojure 1.9 support. Minimum Clojure version is now 1.10.3.

## v0.8.66 (2025-07-12)

- [#122](https://github.com/babashka/cli/issues/122): introduce new
  `:repeated-opts` option to enforce repeating the option for accepting multiple
  values (e.g. `--foo 1 --foo 2` rather than `--foo 1 2`)

## v0.8.65 (2025-04-14)

- [#119](https://github.com/babashka/cli/issues/119): `format-table` now formats multiline cells appropriately
([@lread](https://github.com/lread))

## v0.8.64

- Remove `pom.xml` and `project.clj` for cljdoc

## v0.8.63

- [#116](https://github.com/babashka/cli/issues/116): Un-deprecate `:collect` option to support custom transformation of arguments to collections ([@lread](https://github.com/lread))
- Support `:collect` in `:spec`

## v0.8.62 (2024-12-22)

- Fix [#109](https://github.com/babashka/cli/issues/109): allow options to start with a number

## v0.8.61 (2024-11-15)

- Fix [#102](https://github.com/babashka/cli/issues/102): `format-table` correctly pads cells containing ANSI escape codes
- Fix [#106](https://github.com/babashka/cli/issues/106): Multiple options before subcommand conflict with subcommand
- Fix [#104](https://github.com/babashka/cli/issues/104): Allow extra arguments to be passed before options in exec function

## v0.8.60 (2024-07-23)

- Fix [#98](https://github.com/babashka/cli/issues/98): internal options should not interfere with :restrict

## v0.8.59 (2024-04-30)

- Fix [#96](https://github.com/babashka/cli/issues/96): prevent false defaults from being removed/ignored
- Fix [#91](https://github.com/babashka/cli/issues/91): keyword options and hyphen options should not mix

## v0.8.58 (2024-03-12)

Fix [#89](https://github.com/babashka/cli/issues/89): long option never represents alias

## v0.8.57 (2024-02-22)

Fix [#82](https://github.com/babashka/cli/issues/82): prefer alias over composite option

## v0.8.56 (2024-02-13)

- Add `:opts` to `:error-fn` input
- Fix command line args for test runner `--dirs`, `--only`, etc

## v0.8.55 (2024-01-04)

- Fix `--no-option` (`--no` prefix) in combination with subcommands

## v0.8.54 (2024-01-04)

- Prioritize `:exec-args` over spec `:default`s
- `dispatch` improvements ([@Sohalt](https://github.com/Sohalt), [@borkdude](https://github.com/borkdude)):
  - The `:cmds` order of entries in the table doesn't matter
  - Support parsing intermediate options: `foo --opt1=never bar --opt2=always`

## v0.7.53 (2023-09-28)

- [#72](https://github.com/babashka/cli/issues/72): add possibility to add a header to format-opts ([@Sohalt](https://github.com/Sohalt))

## v0.7.52 (2023-06-20)

- [#68](https://github.com/babashka/cli/issues/68): alternative to shutdown-agents similar to clojure CLI's `-X` and `-T` behavior

## v0.7.51 (2023-04-17)

- [#64](https://github.com/babashka/cli/issues/64): Support combined short options: `-abc` => `{:a true :b true :c true}`
- [#17](https://github.com/babashka/cli/issues/17): Support `--no-` prefix for negative flags: `--no-colors` => `{:colors false}`

## v0.6.50 (2023-03-18)

- Improve `auto-coerce`: coerce `"nil"` to `nil` ([@teodorlu](https://github.com/teodorlu))

## v0.6.49 (2023-03-10)

- Improve `auto-coerce` for keywords ([@teodorlu](https://github.com/teodorlu))

## v0.6.48 (2023-03-07)

- Make `babashka.exec` compatible with clojure CLI `1.11.1.1152`+

## v0.6.46 (2023-02-19)

- [#58](https://github.com/babashka/cli/issues/58): implicit true should not be transformed to string value

## v0.6.45 (2023-01-27)

- Preserve exception cause in coercion for better error messages

## v0.6.44 (2023-01-18)

- [#56](https://github.com/babashka/cli/issues/56): `:exec-args` should be replaced, not merged

## v0.6.43 (2023-01-13)

- [#55](https://github.com/babashka/cli/issues/55): Last keyword option not parsed when previous value is implicit boolean

## v0.6.41 (2022-12-11)

- [#52](https://github.com/babashka/cli/issues/52): require value specified as non-boolean to be supplied
- [#53](https://github.com/babashka/cli/issues/53): support parsing negative numbers

## v0.5.40 (2022-10-12)

- Add `:org.babashka/cli {:exec true}` to arg map's metadata when invoking
  functions with `babashka.cli.exec`.

## v0.4.39

- [#48](https://github.com/babashka/cli/issues/48): allow overriding `:exec-fn` on command line

## v0.4.38

- [#46](https://github.com/babashka/cli/issues/46): fix trailing boolean option coercion

## v0.4.37

-  [#39](https://github.com/babashka/cli/issues/39): handle `:exec-args` with `false` default

## v0.4.36

- Be tolerant of tags in `clojure.basis`

## v0.3.35

- Added `:error-fn` to handle errors ([@jmglov](https://github.com/jmglov))
- Merge `:spec` options with additional "terse" options

## v0.3.33

- Added `:require` to throw on missing options
- Added `:validate` to throw on invalid options
- Support `parse-opts` options in `dispatch` table entries
- Add `:args->opts` in `parse-opts` to consume positional arguments as options
- Renamed `:aliases` to `:alias` (with backwards compatibility)
- Renamed `:closed` to `:restrict` (with backwards compatibility)
- Renamed `:cmds-opts` to `args->opts` (with backwards compatibility)

## v0.3.32

- Support `:closed` in `parse-args` / `parse-opts` for throwing on unrecognized
  options.
- Fix default-width calculation in format-opts

## v0.3.31

- Improve `babashka.cli.exec` for babashka
- Improve auto-parsing

## v0.3.29

- Improve auto-coercion and keyword arguments

## v0.3.28

- Accept both `:foo` and `foo` as string to be coerced into `:foo` keyword.

## v0.3.27

- `cli/auto-coerce` should not coerce when input is not a string

## v0.3.26

- Compatibility with Clojure 1.9 and older versions of ClojureScript

## v0.2.25

- [#10](https://github.com/babashka/cli/issues/10): Auto-coerce values using [`cli/auto-coerce`](https://github.com/babashka/cli/blob/main/API.md#auto-coerce)

## v0.2.24

- Clean up unnecessary dependency

## v0.2.23

- [#22](https://github.com/babashka/cli/issues/22): respect `:default` in `:spec`

## v0.2.22

- [#20](https://github.com/babashka/cli/issues/20): Preserve namespace in `format-opts` output.
- Fix coercion with `:spec`.

## v0.2.21

- `format-opts` for help output + [spec](https://github.com/babashka/cli#spec) format

## v0.2.20

- Support parsing of trailing `:args`. See [docs](https://github.com/babashka/cli#arguments).

## v0.2.19

- `:no-keyword-opts` to treat `:foo` as option value rather than option name

## v0.2.18

- Allow `nil` to be a valid coerced value

## v0.2.17

- The `:coerce` option now supports a collection and type: `[:keyword]` which
  makes `:collect` redundant and henceforth deprecated. The shorthand
  `:keywords` introduced in v0.2.16 is removed (breaking).

## v0.2.16

- Support `:coerce` + `:collect` shorthands

## v0.2.15

- Support `:ns-default` in deps alias

## v0.2.14

- Support `:exec-fn` in deps alias

## v0.2.13

- Support `:exec-args` on ns metadata

## v0.2.12

- Support `:exec-args` in parse options to provide defaults

## v0.2.11

- Add support for [subcommands](https://github.com/babashka/cli#subcommands)

## v0.2.10

- Introduce `parse-opts` which replaces `parse-args` and returns a single map.

## v0.2.9

- Support `:org.babashka/cli` options in `deps.edn` aliases.

## v0.1.8

- Support `:exec-args` in alias

## v0.1.7

- Separate namespace and invoked function in `exec`

## v0.1.6

- Support `--foo=bar` syntax

## v0.1.5

- Move metadata to `org.babashka/cli`

## v0.1.4

- Allow implicit boolean options to be collected

## v0.1.3

- Support `:collect` option for collecting values into collection
- Support boolean `true` options without explicit boolean value and coercion

## v0.1.2

- Support `:aliases`
- Support keywords in `coerce`: ``:boolean`, `:int`, `:double`, `:symbol`, `:keyword`
- Support `--foo` and `-foo` as syntax sugar for `:foo`

## v0.1.1

Initial release

## Breaking changes

- v0.3.35: The exception thrown on coercion failures no longer contains the
  `:input` and `:coerce-fn` keys in its ex-data. See the "Error handling"
  section in the README for details on the new exception format.
- v0.2.17: The shorthand `:keywords` introduced in v0.2.16 is removed
  (breaking).
