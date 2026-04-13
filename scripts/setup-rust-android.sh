    #!/usr/bin/env bash
    # One-time setup for Rust → Android compilation.
    # Run once from the project root.
    set -euo pipefail

    echo "Installing cargo-ndk..."
    cargo install cargo-ndk

    echo "Adding aarch64-linux-android Rust target..."
    rustup target add aarch64-linux-android

    echo "Done. You can now run: ./gradlew buildRustRelease"
    