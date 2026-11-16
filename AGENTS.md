# skill-authoring-skills

Tools and agent skills for writing agent skills, as a Nix flake. It holds `skill-citations` (`scripts/skill-citations.clj`, Babashka), which pins each claim in a skill to a span of an upstream Git repository and reports when a newer release changes one. Skills go under `skills/`, prefixed `skill-authoring-`; `README.md` describes the citation format and the CLI.

## Build and test

- `nix develop` enters the dev shell. It installs the skills pinned in the `skills-devshell/` sub-flake into `.agents/skills/` and puts `skill-citations` and `skill-citations-tests` on the path; `menu` lists them. Each takes `--help`, as does each `skill-citations` command.
- `nix flake check` builds every skill package and runs the `citations-<skill>` check of each skill under `skills/` and of the fixture in `tests/skills/uix-fixture`, and `skill-citations-tests`, the unit and offline end-to-end tests in `tests/unit/`. CI runs the same.
- `skill-citations-tests` runs those tests alone, in a second or two.
- `nix fmt` formats with treefmt.
