---
title: A skill telling skill authors how bundled scripts check their tools and fail fast
state: proposed
kind: implementation
part-of: bundled-scripts-fail-loudly
touches: [skills/]
---

# A skill telling skill authors how bundled scripts check their tools and fail fast

A new skill in this repository, loaded whenever an agent writes or edits a skill's `scripts/`, or
reviews a skill that ships scripts. A working name is `skill-scripts-fail-fast`. It states two
rules and a checklist, and links `coding-agent-skill-references` and
`coding-agent-scratchpad-scripts` in its Related skills.

## Rule 1: check every tool before any work

The script's first lines check that every external command it calls is on `PATH`, and exit 127,
the shell's "command not found" status, with one line naming every missing tool:

```bash
require_tools() {
  local missing=() tool
  for tool in "$@"; do command -v "$tool" >/dev/null 2>&1 || missing+=("$tool"); done
  if ((${#missing[@]})); then
    printf 'error: not on PATH: %s\n' "${missing[*]}" >&2
    exit 127
  fi
}
require_tools jq gh git
```

The list names every external command the script runs; shell builtins don't need checking.

## Rule 2: stop on the first error

| Language | What to write                                                                                                                   |
| -------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Bash     | `set -euo pipefail` straight after the shebang; turn `-e` off only around one deliberate probe, as `probe \|\| status=$?`.      |
| Python   | Let exceptions propagate, since an uncaught one already exits non-zero; every `subprocess.run` passes `check=True` or checks the status itself. |
| Node     | Node 15 and later already exit on an unhandled rejection; don't catch errors only to log them and carry on.                    |
| Babashka | `babashka.process/shell` and `process` with `:check true` throw on a non-zero exit; let the exception end the script.          |
| fish     | No global equivalent: follow each command that can fail with `or exit $status`. Prefer Bash for bundled scripts.                |

## Anti-patterns the skill names

- `cmd || true` and `2>/dev/null` hiding errors the agent needs.
- `if ! cmd; then exit $?; fi`, which exits 0, since `$?` is the negation's status; write
  `cmd || exit $?`.
- `set -e` without `-u` and `-o pipefail`, so an unset variable or a failing pipe stage carries on.

## Checks

- The skill's own example scripts pass the rules, tested in `nix flake check`.
- A smoke test runs an example with a required tool hidden from `PATH` and expects exit 127 and one
  line.
- A follow-up sweep, as a schedule node of its own, audits every skill repository's `scripts/`
  against the rules and opens one pull request per repository.
