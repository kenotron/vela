    //! Agent bundle file parser.
    //!
    //! Parses a `.md`-formatted agent file (YAML front-matter + Markdown body)
    //! into an [`AgentConfig`].

    use crate::AgentConfig;

    /// Parse the text content of an agent bundle file into [`AgentConfig`].
    ///
    /// # Format
    /// ```text
    /// ---
    /// name: my-agent
    /// description: Does things
    /// ---
    /// System prompt text here.
    /// ```
    ///
    /// The body after the closing `---` becomes `AgentConfig::instruction`.
    pub fn parse_agent_file(_content: &str) -> anyhow::Result<AgentConfig> {
        anyhow::bail!("not implemented")
    }
    