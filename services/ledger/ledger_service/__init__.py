"""vela-ledger-service: the C3 job ledger (design doc §4.2).

Standalone, durable, server-side ledger for `dispatch_to_fleet`-style handle-returning
work. Requires no fork of `amplifier-agent`. See README.md for schema mapping to the
Android `JobEntity`/`JobRecord` shape (lane 1.5) and the endpoint reference.
"""

__version__ = "0.1.0"
