"""CLI entry point: `python -m ledger_service` runs the service with uvicorn."""

from __future__ import annotations

import argparse

import uvicorn


def main() -> None:
    parser = argparse.ArgumentParser(prog="ledger-service")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=9199)
    parser.add_argument("--log-level", default="info")
    args = parser.parse_args()

    uvicorn.run(
        "ledger_service.app:app",
        host=args.bind,
        port=args.port,
        log_level=args.log_level,
    )


if __name__ == "__main__":
    main()
