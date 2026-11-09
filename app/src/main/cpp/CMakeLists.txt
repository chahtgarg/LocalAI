
# Minimum CMake version required by the project.
cmake_minimum_required(VERSION 3.22.1)

# Defines the native project name.
project("localai")


# ==========================================
# 1. BUILD CONFIGURATION
# ==========================================

# Uses Release mode for better performance.
set(CMAKE_BUILD_TYPE Release)

# -O3 enables compiler optimizations.
# -DNDEBUG disables debug assertions.
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")


# ==========================================
# 2. LLAMA.CPP CONFIGURATION
# ==========================================

# Disables tests because they are not needed in the app.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)

# Disables example programs.
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Disables command-line tools.
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

# Disables automatic detection of the build machine's CPU.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Disables OpenMP parallel processing.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)


# ==========================================
# 3. CPU-SPECIFIC OPTIMIZATION
# ==========================================

# Checks which Android CPU architecture is being built.

if(ANDROID_ABI STREQUAL "arm64-v8a")

    # Configures ARM-specific CPU instructions.
    set(GGML_CPU_ARM_ARCH
        "armv8.2-a+dotprod+fp16"
        CACHE STRING "" FORCE
    )

elseif(ANDROID_ABI STREQUAL "x86_64")

    # Enables CPU instruction optimizations for the x86_64 emulator.
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)

    set(GGML_FMA ON CACHE BOOL "" FORCE)

    set(GGML_F16C ON CACHE BOOL "" FORCE)

    set(GGML_BMI2 ON CACHE BOOL "" FORCE)

endif()


# ==========================================
# 4. INCLUDE LLAMA.CPP
# ==========================================

# Includes the llama.cpp source directory.
# CMake will build the llama inference engine as part of this project.
add_subdirectory(llama.cpp)


# ==========================================
# 5. CREATE OUR NATIVE LIBRARY
# ==========================================

# Builds native-lib.cpp into a shared Android library.
# The resulting library is named liblocalai.so.
add_library(localai SHARED native-lib.cpp)


# ==========================================
# 6. LINK REQUIRED LIBRARIES
# ==========================================

# Finds Android's logging library.
find_library(log-lib log)

# Links our native library with llama.cpp and Android logging.
target_link_libraries(
    localai
    PRIVATE
    llama
    ${log-lib}
)
