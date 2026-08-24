---
bundle:
  name: vela
  version: 0.1.0
  description: Project-local bundle for kenotron/vela — carries Ditto, the project's Chief of Staff agent.

includes:
  - bundle: foundation

agents:
  include:
    - vela:ditto
---

# Vela project bundle

Thin project-local bundle. Its only job right now is making `vela:ditto` — the
project's Chief of Staff agent (`agents/ditto.md`) — delegatable in sessions
running against this repo.
