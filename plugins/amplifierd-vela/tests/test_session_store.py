"""Tests for vela_plugin.session_store."""
import time

import pytest

import vela_plugin.session_store as ss


@pytest.fixture(autouse=True)
def patch_store_path(tmp_path, monkeypatch):
    """Redirect _store_path to a temp file for every test."""
    store_file = tmp_path / "vela-sessions.json"
    monkeypatch.setattr(ss, "_store_path", lambda: store_file)
    return store_file


def test_load_all_returns_empty_when_file_missing():
    assert ss.load_all() == {}


def test_add_session_persists_and_get_sessions_returns_it():
    session = ss.VelaSession(
        session_id="s1",
        project_id="p1",
        created_at=time.time(),
        status="running",
        title="Test session",
    )
    ss.add_session(session)
    result = ss.get_sessions("p1")
    assert len(result) == 1
    assert result[0].session_id == "s1"
    assert result[0].title == "Test session"


def test_add_session_inserts_newest_first():
    now = time.time()
    s1 = ss.VelaSession(session_id="old", project_id="p1", created_at=now - 10)
    s2 = ss.VelaSession(session_id="new", project_id="p1", created_at=now)
    ss.add_session(s1)
    ss.add_session(s2)
    result = ss.get_sessions("p1")
    assert result[0].session_id == "new"
    assert result[1].session_id == "old"


def test_get_sessions_returns_empty_for_unknown_project():
    assert ss.get_sessions("nonexistent") == []


def test_update_session_status_changes_status_and_last_activity():
    session = ss.VelaSession(
        session_id="s1",
        project_id="p1",
        created_at=time.time(),
        status="running",
    )
    ss.add_session(session)
    t = time.time()
    ss.update_session_status("s1", "completed", t)
    result = ss.get_sessions("p1")
    assert result[0].status == "completed"
    assert result[0].last_activity == t


def test_update_session_status_no_op_for_unknown_session():
    # Should not raise even when session_id doesn't exist
    ss.update_session_status("ghost", "completed", time.time())


def test_load_all_handles_corrupt_file(tmp_path, monkeypatch):
    store_file = tmp_path / "corrupt.json"
    store_file.write_text("not-json{{{")
    monkeypatch.setattr(ss, "_store_path", lambda: store_file)
    assert ss.load_all() == {}
