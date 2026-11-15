{
  description = "skill-authoring-skills dev-shell skill set — an isolated sub-flake invoked at RUNTIME by the root devShell, never a root input. The skill sources (skillspkgs' authoring-with-git combination) live only in THIS flake's lock, so the root skill-authoring-skills stays a leaf with zero skill inputs and transitive consumers never drag the skill mesh in.";

  # Every skill-repository input tracks its `overnight-skills` branch for
  # the overnight cleanup wave; they go back to the default branches after.
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";

    # `agent-skill-flake` is the builder library, not a skill — it provides
    # `mkDevshellSkillsFlake`. Followed by every skill source below so the
    # whole tree shares one evaluation.
    agent-skill-flake = {
      url = "github:nhooey/agent-skill-flake";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    # Skill sources. Each follows only nixpkgs, keeping its own compatible
    # agent-skill-flake; the combiner still runs from the one pinned above.
    coding-agent-skills = {
      url = "github:nhooey/coding-agent-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    data-skills = {
      url = "github:nhooey/data-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    document-skills = {
      url = "github:nhooey/document-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    # Also the git and nix sources inside the skillspkgs combination below,
    # which follows these two.
    git-skills = {
      url = "github:nhooey/git-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    nix-skills = {
      url = "github:nhooey/nix-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    shell-skills = {
      url = "github:nhooey/shell-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    # skillspkgs' curated `authoring-with-git` combination (nix + humanizer +
    # anthropic skill-creation + superpowers + the whole git/GitHub pack),
    # surfaced through its own subdir flake so it stays re-composable as a
    # source. This is the dev shell's entire skill set in one combination.
    skillspkgs-combinations = {
      url = "github:nhooey/skillspkgs?dir=sources/combinations";
      inputs = {
        nixpkgs.follows = "nixpkgs";
        agent-skill-flake.follows = "agent-skill-flake";
        git-skills.follows = "git-skills";
        nix-skills.follows = "nix-skills";
      };
    };

    writing-skills = {
      url = "github:nhooey/writing-skills/overnight-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      nixpkgs,
      agent-skill-flake,
      coding-agent-skills,
      data-skills,
      document-skills,
      shell-skills,
      skillspkgs-combinations,
      writing-skills,
      ...
    }@inputs:
    agent-skill-flake.lib.mkDevshellSkillsFlake {
      inherit nixpkgs;
      systems = import inputs.systems;
      name = "skill-authoring-skills-devshell";
      envName = "agent-skills-skill-authoring-skills-devshell";
      sources = [
        # The base skills every headless run in this checkout needs: running
        # a prompt in another checkout, the plan graph, a tidy scratchpad.
        {
          source = coding-agent-skills;
          skills = [
            "coding-agent-headless-in-checkout"
            "coding-agent-plans-as-graph"
            "coding-agent-scratchpad-scripts"
          ];
        }
        { source = data-skills; }
        { source = document-skills; }
        { source = shell-skills; }
        { source = skillspkgs-combinations.combinations.authoring-with-git; }
        { source = writing-skills; }
      ];
    };
}
