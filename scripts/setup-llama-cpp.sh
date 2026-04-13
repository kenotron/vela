    #!/usr/bin/env bash
    # setup-llama-cpp.sh — Run once after cloning the repo to pull in the llama.cpp submodule.
    #
    # Usage:  bash scripts/setup-llama-cpp.sh
    # Needs:  git, internet access (~500 MB download)
    #
    # This pulls the llama.cpp source into app/src/main/cpp/llama.cpp/ so Android Studio
    # can build the vela-llama.so JNI library. Without it the NDK build will fail.

    set -euo pipefail

    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

    echo "📦  Initialising llama.cpp submodule..."
    cd "$REPO_ROOT"

    git submodule add --depth 1 \
        https://github.com/ggml-org/llama.cpp.git \
        app/src/main/cpp/llama.cpp 2>/dev/null || true

    git submodule update --init --depth 1 app/src/main/cpp/llama.cpp

    echo "✅  Done. llama.cpp is at app/src/main/cpp/llama.cpp"
    echo "    Open the project in Android Studio and sync Gradle."
    