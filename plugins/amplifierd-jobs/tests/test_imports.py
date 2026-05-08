"""Test that the amplifierd-jobs plugin packages can be imported."""


def test_amplifierd_jobs_imports():
    """Test that amplifierd_jobs package can be imported."""
    import amplifierd_jobs
    assert hasattr(amplifierd_jobs, "__version__")


def test_amplifier_module_tool_jobs_imports():
    """Test that amplifier_module_tool_jobs package can be imported."""
    import amplifier_module_tool_jobs
    assert hasattr(amplifier_module_tool_jobs, "__amplifier_module_type__")
