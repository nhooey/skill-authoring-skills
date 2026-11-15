# skill-authoring-skills

Tools and agent skills for writing agent skills, as a Nix flake. It holds `skill-citations` (`scripts/skill-citations.clj`, Babashka), which pins each claim in a skill to a span of an upstream Git repository and reports when a newer release changes one. Skills go under `skills/`, prefixed `skill-authoring-`; `README.md` describes the citation format and the CLI.

## Build and test

- `nix develop` enters the dev shell. It installs the skills pinned in the `skills-devshell/` sub-flake into `.agents/skills/` and puts `skill-citations` on the path.
- `nix flake check` builds every skill package and runs the `citations-<skill>` check of each skill under `skills/` and of the fixture in `tests/skills/uix-fixture`. CI runs the same.
- `nix fmt` formats with treefmt.
