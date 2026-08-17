"""Protocol-point abstractions for the vela_agentd_lib package.

Re-exports base protocol points and CLI Mode A defaults.
"""

from vela_agentd_lib.protocol_points.base import (
    ApprovalAction,
    ApprovalRequest,
    ApprovalResponse,
    ApprovalSystem,
    DisplayEvent,
    DisplaySystem,
    ProtocolPoints,
)
from vela_agentd_lib.protocol_points.defaults_cli import (
    ApprovalOverride,
    CliApprovalSystem,
    CliDisplaySystem,
    DisplayVerbosity,
)

__all__ = [
    "ApprovalAction",
    "ApprovalOverride",
    "ApprovalRequest",
    "ApprovalResponse",
    "ApprovalSystem",
    "CliApprovalSystem",
    "CliDisplaySystem",
    "DisplayEvent",
    "DisplaySystem",
    "DisplayVerbosity",
    "ProtocolPoints",
]
