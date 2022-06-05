# Changelog

For breaking changes, check [here](#breaking-changes).

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

None yet.
