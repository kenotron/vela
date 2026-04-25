pub mod context;
pub mod resolver;

pub use context::{build_inherited_context, ContextDepth, ContextScope};
pub use resolver::{resolve_agent, ResolvedAgent};
