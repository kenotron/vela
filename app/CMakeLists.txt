    cmake_minimum_required(VERSION 3.22.1)
    project("vela")

    # ─── llama.cpp build options ───────────────────────────────────────────────────
    # Disable everything we don't need to keep the .so small and build fast.
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

    # GPU backends
    # set(GGML_OPENCL ON CACHE BOOL "" FORCE)  # Enable for Adreno GPU
    set(GGML_VULKAN  OFF CACHE BOOL "" FORCE)
    # Disable OpenMP — libomp.so is not 16KB-page-aligned on NDK 27
    # llama.cpp uses its own thread pool (ggml-threading) as fallback
    set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)

    # Pull in the full llama.cpp CMake tree (builds llama + ggml + common)
    add_subdirectory(src/main/cpp/llama.cpp)

    # ─── vela-llama shared library ─────────────────────────────────────────────────
    add_library(vela-llama SHARED
        src/main/cpp/llama_bridge.cpp
    )

    target_compile_options(vela-llama PRIVATE
        -std=c++17
        -Wall
        -O3
    )

    target_include_directories(vela-llama PRIVATE
        src/main/cpp/llama.cpp/include
        src/main/cpp/llama.cpp/ggml/include
    )

    # 16KB page alignment — required for Android 15+ (Pixel 10 Pro)
    target_link_options(vela-llama PRIVATE "-Wl,-z,max-page-size=16384")

    target_link_libraries(vela-llama
        llama
        ggml
        android
        log
    )
    