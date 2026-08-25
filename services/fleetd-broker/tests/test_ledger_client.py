"""Tests for ProgressCoalescer (progress coalescing, attention never coalesced)."""

from __future__ import annotations

import time

from fleetd_broker.ledger_client import ProgressCoalescer


def test_first_offer_flushes_immediately():
    c = ProgressCoalescer(interval_s=2.0)
    entry = {"message": "started"}
    result = c.offer("job-1", entry)
    assert result == entry


def test_second_offer_within_interval_is_held():
    c = ProgressCoalescer(interval_s=2.0)
    c.offer("job-1", {"message": "first"})
    result = c.offer("job-1", {"message": "second"})
    assert result is None
    assert c.pending("job-1") == {"message": "second"}


def test_offer_after_interval_flushes():
    c = ProgressCoalescer(interval_s=0.01)
    c.offer("job-1", {"message": "first"})
    time.sleep(0.02)
    result = c.offer("job-1", {"message": "second"})
    assert result == {"message": "second"}


def test_drain_returns_and_clears_pending():
    c = ProgressCoalescer(interval_s=100.0)
    c.offer("job-1", {"message": "first"})
    c.offer("job-1", {"message": "second"})  # held
    drained = c.drain("job-1")
    assert drained == {"message": "second"}
    assert c.pending("job-1") is None


def test_drain_with_nothing_pending_returns_none():
    c = ProgressCoalescer()
    assert c.drain("no-such-job") is None


def test_coalescing_is_per_job():
    c = ProgressCoalescer(interval_s=100.0)
    r1 = c.offer("job-1", {"message": "a"})
    r2 = c.offer("job-2", {"message": "b"})
    assert r1 == {"message": "a"}
    assert r2 == {"message": "b"}
