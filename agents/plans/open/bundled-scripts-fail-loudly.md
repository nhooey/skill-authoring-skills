---
title: A skill's bundled scripts fail at once, loudly, when a tool is missing or a step fails
state: proposed
kind: end-state
---

# A skill's bundled scripts fail at once, loudly, when a tool is missing or a step fails

Every script shipped in a skill's `scripts/` folder, in every skill repository, stops on its first
problem with one clear line on stderr and a non-zero exit, instead of carrying on and burying the
cause in pages of repeated errors. An agent reading the output, through Bash, Monitor or anything
else, sees the problem on the first event.

## How to check it

- **Missing tool:** with one required tool removed from `PATH`, the script prints one line naming
  the missing tools and exits 127, before doing any work.
- **Failing step:** a failing command, a failing stage inside a pipe and an unset variable each stop
  the script with a non-zero exit.
- **No hidden failures:** no `|| true` or `2>/dev/null` hides an error the agent needs; any that
  stays hides a named, expected message.
- **Every repository:** the rules hold for the bundled scripts of every skill repository, checked
  by a sweep once the skill exists.
