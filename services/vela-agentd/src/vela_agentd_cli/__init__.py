"""vela_agentd_cli — thin I/O adapter for the Amplifier agent engine.

This package is the CLI entry-point layer on top of vela_agentd_lib.

Phase 2 scope:
  Mode A (single-turn argv invocation) + admin verbs (doctor, config show, cache clear).
  Mode B (stdio JSON-RPC) — stubbed; full implementation in Phase 3.

The critical invariant: all business logic lives in vela_agentd_lib.
This package only handles I/O: reading from stdin, writing to stdout,
and wiring click commands to the engine.
"""

from __future__ import annotations

from vela_agentd_lib import __version__ as _lib_version

__version__: str = _lib_version
__all__ = ["__version__"]
