# 0001 - Show allowed choices in help and add `:enum`

AI-generated design record. Read as AI prose.

> STATUS: IMPLEMENTED (unreleased). Raised by lread during the bb tasks CLI
> review (2026-07). `:enum` was proposed by borkdude in the same thread.

## Context

Fixed choices are available when rendering help. Completion and validation
errors displayed them, but `--help` did not:

``` console
$ bb deploy --environment qa
Error: Invalid value for option --environment: qa. Expected one of: dev, prod, staging
```

## Decision

1. Show set-valued `:validate` values in `--help`. Use the sorted order already
   used by completion and validation errors.

2. Add `:enum` for ordered allowed values. Sets require sorting, which displays
   `#{"dev" "staging" "prod"}` as `dev, prod, staging`. `:enum` preserves the
   declared order.

``` text
Arguments:
  <environment>  Target environment (one of: dev, staging, prod)
```

Keyword values render without the leading colon. Use `:coerce :keyword` to match
keyword values. `:enum` does not infer coercion.

Render `:args->opts` entries under `Arguments:` in help. Their values are
consumed positionally.

## Prior art

Surveyed argparse, click, clap, cobra/pflag, kingpin, commander, yargs,
picocli, tools.cli, malli.

- Naming: `choices` is common in argparse, click, commander, yargs and kingpin.
  `enum` is used by clap, picocli and malli. `:enum` matches malli.
- Help format: most append a comma-separated list to the option description
  (clap `[possible values: ...]`, commander `(choices: ...)`, yargs
  `[choices: ...]`). Our `(one of: ...)` matches that shape and reuses the
  phrase from our own error (`Expected one of: ...`), so help and error agree.
  argparse/click instead put choices in the usage/metavar line.
- Order: all surveyed tools preserve declared order. None sort.

## Rejected

Reject `:choices` because `:enum` matches malli and is shorter. Keep coercion
explicit instead of inferring it from `:enum` element types.
