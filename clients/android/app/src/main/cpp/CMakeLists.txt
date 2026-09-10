cmake_minimum_required(VERSION 3.31.6)
project(source-ai-pad LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT EXISTS "${LLAMA_CPP_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Pinned llama.cpp source is missing. Run scripts/provision-llama-cpp.sh")
endif()

add_subdirectory("${LLAMA_CPP_ROOT}" llama.cpp-build EXCLUDE_FROM_ALL)

add_library(source-ai-pad SHARED pad_model_probe.cpp source_ai_engine.cpp)
target_include_directories(source-ai-pad PRIVATE "${LLAMA_CPP_ROOT}/include" "${LLAMA_CPP_ROOT}/common")
target_link_libraries(source-ai-pad PRIVATE llama llama-common android log)
