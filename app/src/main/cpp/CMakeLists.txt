# Trigger CMake reconfiguration after copying llama.cpp sources.
cmake_minimum_required(VERSION 3.22.1)

project("localai")

# Force an optimized build (Debug would be 10-50x slower)
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# Disable unnecessary components.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# CPU-specific kernels
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Galaxy M51 (Cortex-A76/A55): dotprod + fp16, no i8mm
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Android emulator on an x86 laptop
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
    set(GGML_BMI2 ON CACHE BOOL "" FORCE)
endif()

# Include the llama.cpp source code.
add_subdirectory(llama.cpp)

# Create our Android native library.
add_library(localai SHARED native-lib.cpp)

# Link Android logging and the llama inference engine.
find_library(log-lib log)

target_link_libraries(
        localai
        PRIVATE
        llama
        ${log-lib}
)