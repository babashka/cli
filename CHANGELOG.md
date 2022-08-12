# Changelog

For breaking changes, check [here](#breaking-changes).

## Unreleased

- Added `:error-fn` to handle errors
- Support `:require` in specs

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

- Unreleased: The exception thrown on coercion failures no longer contains the
  `:input` and `:coerce-fn` keys in its ex-data. See the "Error handling"
  section in the README for details on the new exception format.
- v0.2.17: The shorthand `:keywords` introduced in v0.2.16 is removed
  (breaking).
