"""Protocol sub-package for vela_agentd_lib.

Public API — everything a consumer needs is importable directly from
``vela_agentd_lib.protocol``.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Capabilities
# ---------------------------------------------------------------------------
from vela_agentd_lib.protocol.capabilities import (
    ApprovalCapability,
    ClientCapabilities,
    DisplayCapability,
    ServerCapabilities,
    negotiate_capabilities,
    server_default_capabilities,
)

# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------
from vela_agentd_lib.protocol.errors import AaaError, ErrorCode

# ---------------------------------------------------------------------------
# Methods
# ---------------------------------------------------------------------------
from vela_agentd_lib.protocol.methods import (
    PROTOCOL_VERSION,
    AgentShutdownParams,
    AgentShutdownResult,
    CacheInfoParams,
    CacheInfoResult,
    ClientInfo,
    InitializeParams,
    InitializeResult,
    ServerInfo,
    SessionCreateParams,
    SessionCreateResult,
    SessionEndParams,
    SessionEndResult,
    SessionState,
    TurnSubmitParams,
    TurnSubmitResult,
)

# ---------------------------------------------------------------------------
# Notifications
# ---------------------------------------------------------------------------
from vela_agentd_lib.protocol.notifications import (
    CANONICAL_DISPLAY_EVENTS,
    ApprovalRequestNotification,
    ApprovalTimeoutNotification,
    ErrorNotification,
    ProgressNotification,
    ResultDeltaNotification,
    ResultFinalNotification,
    ThinkingDeltaNotification,
    ThinkingFinalNotification,
    ToolCompletedNotification,
    ToolStartedNotification,
    UsageNotification,
)

__all__ = [
    "CANONICAL_DISPLAY_EVENTS",
    "PROTOCOL_VERSION",
    "AaaError",
    "AgentShutdownParams",
    "AgentShutdownResult",
    "ApprovalCapability",
    "ApprovalRequestNotification",
    "ApprovalTimeoutNotification",
    "CacheInfoParams",
    "CacheInfoResult",
    "ClientCapabilities",
    "ClientInfo",
    "DisplayCapability",
    "ErrorCode",
    "ErrorNotification",
    "InitializeParams",
    "InitializeResult",
    "ProgressNotification",
    "ResultDeltaNotification",
    "ResultFinalNotification",
    "ServerCapabilities",
    "ServerInfo",
    "SessionCreateParams",
    "SessionCreateResult",
    "SessionEndParams",
    "SessionEndResult",
    "SessionState",
    "ThinkingDeltaNotification",
    "ThinkingFinalNotification",
    "ToolCompletedNotification",
    "ToolStartedNotification",
    "TurnSubmitParams",
    "TurnSubmitResult",
    "UsageNotification",
    "negotiate_capabilities",
    "server_default_capabilities",
]
