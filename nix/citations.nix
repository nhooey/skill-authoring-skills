# Citation checking for skills that pin their claims to upstream spans
# (see scripts/skill-citations.clj). `tool` is the CLI; `check` builds the
# `verify` check of one skill directory, and `checks` one
# `citations-<skill>` check per skill under `skillsDir` with a
# citations.lock.json.
#
# A check fetches each cited file by raw URL at the locked commit, as a
# fixed-output derivation keyed by the lock's file hash, so it runs in the
# sandbox without network and fails if the lock, the footnotes or the
# anchors disagree. It runs against the whole of `root`, so a skill's
# observed footnotes can be checked against the eval examples they name
# outside the skill's own directory.
{ pkgs }:
let
  inherit (pkgs) lib;

  script = ../scripts/skill-citations.clj;

  tool = pkgs.writeShellApplication {
    name = "skill-citations";
    runtimeInputs = [
      pkgs.babashka
      pkgs.git
    ];
    text = ''exec bb ${script} "$@"'';
  };

  # The `verify` check of the skill at `skill`, a path relative to `root`.
  check =
    {
      root,
      skill,
      name ? "citations-${baseNameOf skill}",
    }:
    let
      lock = lib.importJSON (root + "/${skill}/citations.lock.json");
      # A source's files under `prefix`: the skill's own at the root, each
      # second source a citation names under _sources/<repo>@<tag>/.
      filesOf =
        prefix: source:
        lib.mapAttrsToList (path: sha256: {
          name = prefix + path;
          path = pkgs.fetchurl {
            url = "https://raw.githubusercontent.com/${source.repo}/${source.commit}/${path}";
            inherit sha256;
          };
        }) source.files;
      upstream = pkgs.linkFarm "${name}-cited-upstream" (
        filesOf "" lock
        ++ lib.concatLists (lib.mapAttrsToList (key: filesOf "_sources/${key}/") (lock.sources or { }))
      );
    in
    pkgs.runCommand name { nativeBuildInputs = [ pkgs.babashka ]; } ''
      bb ${script} verify ${root}/${skill} --source ${upstream}
      touch $out
    '';

  # One `citations-<skill>` check per directory under `root`/`skillsDir`
  # that holds a citations.lock.json.
  checks =
    {
      root,
      skillsDir ? "skills",
    }:
    let
      dir = root + "/${skillsDir}";
      cited = lib.filterAttrs (
        name: type: type == "directory" && builtins.pathExists (dir + "/${name}/citations.lock.json")
      ) (if builtins.pathExists dir then builtins.readDir dir else { });
    in
    lib.mapAttrs' (
      name: _:
      lib.nameValuePair "citations-${name}" (check {
        inherit root;
        skill = "${skillsDir}/${name}";
      })
    ) cited;
in
{
  inherit tool check checks;
}
