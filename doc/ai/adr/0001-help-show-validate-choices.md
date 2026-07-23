# 0001 - Show allowed choices in help; add `:enum`

AI-generated design record. Read as AI prose.

> STATUS: IMPLEMENTED (unreleased). Raised by lread during the bb tasks CLI
> review (2026-07); `:enum` proposed by borkdude in the same thread.

## Context

When an option is restricted to a fixed set of values, the values are known at
help time. Completion offered them and a rejected value printed them, but
`--help` was silent:

```
$ bb deploy --environment qa
Error: Invalid value for option --environment: qa. Expected one of: dev, prod, staging
```

So, tab completion aside, the only way to discover the allowed values from the
command line was to pass a bogus one. `--help` is where a person looks first.

## Decision

Two parts.

1. A set-valued `:validate` now self-documents in `--help`, as a
   `(one of: ...)` note folded into the option's description, alongside the
   existing `(required)` / `(default: ...)` notes. Same values the error and
   completion already used.

2. Add `:enum`, an ordered vector of allowed values. A set has no order, so
   deriving choices from `:validate #{...}` forces a sort, and the sort is
   often wrong: `#{"dev" "staging" "prod"}` displays as `dev, prod, staging`,
   backwards from the promotion order. `:enum ["dev" "staging" "prod"]` keeps
   the declared order. It also conflates concerns: `:validate` is a predicate
   (set *or* function); "here are the N choices, in this order" is a distinct
   declaration.

`:enum` is the canonical form. It derives membership validation (unless the
author also wrote a `:validate`, which is then respected), and drives the help
note, completion and the validation error, all in declared order. A bare
`:validate` set still self-documents (sorted) so nothing regresses; `:enum`
wins when both are present.

```
Arguments:
  <environment>  Target environment (one of: dev, staging, prod)
```

Values may be of any type; keywords render as their bare name, matching the
error and completion, and (like a keyword `:validate`) need `:coerce :keyword`
to match input. Coercion is otherwise orthogonal - `:enum` does not infer it.

Related change in the same pass: an `:args->opts` argument now renders under
`Arguments:` rather than as a `--flag` under `Options:`, so a positionally
consumed value is no longer described as an option.

## Prior art

Surveyed argparse, click, clap, cobra/pflag, kingpin, commander, yargs,
picocli, tools.cli, malli.

- Naming: `choices` is the common user-facing name (argparse, click, commander,
  yargs, kingpin); `enum` is the type-driven camp (clap `ValueEnum`, picocli,
  malli `[:enum ...]`). `:enum` aligns with malli, which matters for the
  malli-docs branch.
- Help format: most append a comma-separated list to the option description
  (clap `[possible values: ...]`, commander `(choices: ...)`, yargs
  `[choices: ...]`). Our `(one of: ...)` matches that shape and reuses the
  phrase from our own error (`Expected one of: ...`), so help and error agree.
  argparse/click instead put choices in the usage/metavar line.
- Order: preserving declared order is universal; none sort. Our order-preserving
  `:enum` is the norm, the sorted `:validate`-set fallback the exception.

## Rejected

`:choices` as the attribute name - more common elsewhere, but `:enum` matches
malli and is shorter. Auto-deriving `:coerce` from `:enum` element types - too
magic; kept orthogonal.
