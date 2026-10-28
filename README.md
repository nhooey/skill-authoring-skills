# skill-authoring-skills

Tools and [Agent Skills](https://www.anthropic.com/engineering/agent-skills) for writing agent skills. It holds `skill-citations`, which pins every claim in a skill to a span of an upstream Git repo and reports when a newer release changes one. The skills, prefixed `skill-authoring-`, go under `skills/`.

## Citing upstream sources

Every claim in a skill carries a footnote, of one of three kinds, so a new upstream release can be checked against only the parts the skill relies on:

- **A span of the upstream docs or source at a Git tag.** `citations.edn` (written by hand) names the repo and tag, the paths to watch, and one `:cite` per footnote id with a path and an anchor: a Markdown `:heading`, the start of a top-level Clojure `:form`, or a unique `:line`. `citations.lock.json` (generated; JSON so Nix can read it) records the commit the tag resolved to, the hash of each cited file, and the lines and hash of each span.
- **A span of a second upstream**, for a claim about software the first depends on. The `:cite` adds its own `:source`, a map like the top-level one (`{:repo "apache/kafka" :tag "4.0.0"}`). The lock records that source's commit and file hashes under `"sources"`, keyed `repo@tag`, and the footnote names the repo beside the tag. `drift` and `bump` move only the skill's own source.
- **An observed result**, for behaviour no source states. An `:observed` entry names an example in the `examples.edn` that citations.edn's `:observations` points to, which the skill's own eval re-runs. The footnote reads "Observed on *X* *tag*", where *X* is citations.edn's `:observed-on` (`"XTDB"`, say), or the repo when it has none. A `releases.edn` beside `examples.edn`, `{:tags [[tag status]…] :results {example-id [[tag result]…]}}`, gives the tags each example held on.

```clojure
{:source {:repo "pitch-io/uix"
          :tag "d414b12"
          :releases :github          ; order releases by GitHub release, not semver tag
          :watch ["README.md"]}      ; changes here that no :cite covers are reported
 :observed-on "UIx"
 :cite [{:id "react-version" :path "README.md" :line "- API compatibility: React"}]}
```

`skill-citations` (`scripts/skill-citations.clj`, Babashka) does the work:

```sh
skill-citations lock   skills/<skill>                # resolve anchors, write the lock, regenerate footnotes
skill-citations verify skills/<skill>                # lock, footnotes and anchors agree at the pinned tag
skill-citations drift  skills/<skill> --to latest    # each citation unchanged / changed / moved / missing, with diffs
skill-citations bump   skills/<skill> --to <tag>     # move the pin after updating the passages drift named
skill-citations pairs  skills/<skill>                # each citing passage beside what it cites, for a reviewer
```

`lock` also dates each footnote: the earliest release on the pinned tag's line since which the span reads the same ("unchanged since v2.0.0-beta7"), and later releases off that line where it differs. By default the releases are the repo's semver tags, betas and release candidates included, with or without a leading `v`. An upstream that names its GitHub releases by short commit hash, as pitch-io/uix does, sets `:releases :github`: its history and `drift --to latest` then follow the order of its GitHub releases, read from the GitHub API with `$GH_TOKEN` or `$GITHUB_TOKEN` when set.

Upstream files come from a blobless bare clone cached under `$XDG_CACHE_HOME/skill-citations`.

## Using it from another flake

```nix
inputs.skill-authoring-skills = {
  url = "github:nhooey/skill-authoring-skills";
  inputs.nixpkgs.follows = "nixpkgs";
};

# in perSystem, or per system:
checks = skill-authoring-skills.lib.citationChecks { inherit pkgs; root = ./.; };
apps.skill-citations = skill-authoring-skills.apps.${system}.skill-citations;
```

- `lib.citationChecks { pkgs, root, skillsDir ? "skills" }` gives one `citations-<skill>` check per directory under `root`/`skillsDir` with a `citations.lock.json`.
- `lib.citationCheck { pkgs, root, skill }` builds the check of one skill at `root`/`skill`.
- `apps.<system>.skill-citations` and `packages.<system>.skill-citations` are the CLI.

A check runs `verify` in the sandbox. It fetches each cited file by raw URL at the locked commit, as a fixed-output derivation keyed by the lock's file hash, so it needs no network at build time and fails if the lock, the footnotes or the anchors disagree. It reads the whole of `root`, so observed footnotes can name eval examples outside the skill's directory.

`nix run github:nhooey/skill-authoring-skills#skill-citations -- lock skills/<skill>` runs the CLI without a flake of your own.

## Development

`nix develop` (or direnv) enters the dev shell, which reconciles the authoring skills pinned in the `skills-devshell/` sub-flake into `.claude/skills/` and puts `skill-citations` on the path. `nix flake check` runs the citation checks of `skills/` and of the fixture in `tests/skills/uix-fixture`, which cites pitch-io/uix at a hash-named release. `nix fmt` formats with treefmt.

## License

Apache 2.0; see [LICENSE](LICENSE).
