cmake_minimum_required(VERSION 3.24)

project("marfa_llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Marfa native llama backend currently supports arm64-v8a only")
endif()

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b10982
    GIT_SHALLOW TRUE
)

FetchContent_MakeAvailable(llama_cpp)

add_library(marfa_llama SHARED
    marfa_llama.cpp
)

target_compile_definitions(marfa_llama PRIVATE
    GGML_USE_CPU
)

target_include_directories(marfa_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(marfa_llama
    llama
    android
    log
)
