    //! Directory loader — walks a directory and loads all agent bundle files into
    //! an [`AgentRegistry`].

    use std::path::Path;
    use crate::AgentRegistry;

    /// Load all agent bundle files from `dir` into `registry`.
    ///
    /// Returns the number of agents successfully loaded.
    pub fn load_from_dir(_registry: &mut AgentRegistry, _dir: &Path) -> anyhow::Result<usize> {
        Ok(0)
    }
    