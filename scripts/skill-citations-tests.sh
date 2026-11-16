# The dev shell's `skill-citations-tests`: run tests/unit/skill_citations_test.clj
# from the working tree, so it tests the edits in hand, not a store copy.
# Arguments pass through (`--help`).
root=${PRJ_ROOT:-$(git rev-parse --show-toplevel)}
exec bb "$root/tests/unit/skill_citations_test.clj" "$@"
