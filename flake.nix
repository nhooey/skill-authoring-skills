{
  description = "skill-authoring-skills: skills and tools for writing agent skills, as a Nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    systems.url = "github:nix-systems/default";
    # `agent-skill-flake` is the builder library, not a skill — it turns skill
    # directories into installable flakes and aggregates them.
    agent-skill-flake = {
      url = "github:nhooey/agent-skill-flake";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      nixpkgs,
      flake-parts,
      agent-skill-flake,
      ...
    }@inputs:
    let
      # Every skill under ./skills, built into per-skill packages plus the
      # base install/preview apps. The aggregate is named by owner + topic,
      # since the owner-wide `agent-skills-nhooey-all` collides across every
      # nhooey repo.
      base = agent-skill-flake.lib.mkAllSkillsFlake {
        inherit nixpkgs;
        source = import ./source.nix;
        skillsDir = ./skills;
        name = "agent-skills-nhooey-skill-authoring-all";
      };

      citationsFor = pkgs: import ./nix/citations.nix { inherit pkgs; };
    in
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import inputs.systems;
      imports = [
        inputs.agent-skill-flake.flakeModules.devshellSkills
        inputs.treefmt-nix.flakeModule
      ];

      agent-skill-flake.devshellSkills.name = "skill-authoring-skills";

      flake.reconcileScript = base.reconcileScript;

      # For another flake's checks, so its `nix flake check` verifies its
      # skills' citations without depending on the repo the skills cite:
      #
      #   checks = skill-authoring-skills.lib.citationChecks { inherit pkgs; root = ./.; };
      #
      # `citationChecks` gives one `citations-<skill>` check per directory
      # under `root`/`skillsDir` (default "skills") with a
      # citations.lock.json; `citationCheck` builds one, for a skill at
      # `root`/`skill`.
      flake.lib = {
        citationChecks =
          {
            pkgs,
            root,
            skillsDir ? "skills",
          }:
          (citationsFor pkgs).checks { inherit root skillsDir; };
        citationCheck =
          {
            pkgs,
            root,
            skill,
          }:
          (citationsFor pkgs).check { inherit root skill; };
      };

      perSystem =
        { pkgs, system, ... }:
        let
          citations = citationsFor pkgs;
        in
        {
          packages = base.packages.${system} // {
            skill-citations = citations.tool;
          };

          apps = base.apps.${system} // {
            skill-citations = {
              type = "app";
              program = pkgs.lib.getExe citations.tool;
              meta.description = "Lock, verify or drift-check a skill's upstream citations";
            };
          };

          # This repo's own skills, and a fixture citing pitch-io/uix, whose
          # GitHub releases are named by commit hash.
          checks =
            citations.checks { root = ./.; }
            // citations.checks {
              root = ./.;
              skillsDir = "tests/skills";
            };

          devshells.default.commands = [
            {
              category = "skills";
              name = "skill-citations";
              help = "lock, verify or drift-check a skill's upstream citations";
              package = citations.tool;
            }
          ];

          devshells.default.packages = [ pkgs.babashka ];

          treefmt = {
            projectRootFile = "flake.nix";
            programs = {
              nixfmt.enable = true;
              yamlfmt.enable = true;
            };
          };
        };
    };
}
