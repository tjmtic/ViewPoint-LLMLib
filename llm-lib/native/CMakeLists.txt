cmake_minimum_required(VERSION 3.22)
project(lm_shim C CXX)

set(CMAKE_C_STANDARD 11)

# llama.cpp is built from the pinned source tree, statically, into whatever links the shim.
#   Android (AGP externalNativeBuild): CBindingKMP passes CBINDING_LLAMA_DIR (prebuilt "llama")
#   and CBINDING_JNI_BRIDGE (generated JNI glue).
#   Host tests: -DLM_HOST_TESTS=ON -DCBINDING_LLAMA_DIR=<llama.cpp source>.
option(LM_HOST_TESTS "Build host executables that test the shim" OFF)

if(NOT DEFINED CBINDING_LLAMA_DIR)
    message(FATAL_ERROR "CBINDING_LLAMA_DIR must point at the llama.cpp source tree")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory(${CBINDING_LLAMA_DIR} llama EXCLUDE_FROM_ALL)

set(LM_SOURCES src/lm_shim.c src/lm_utf8.c)

if(LM_HOST_TESTS)
    enable_testing()
    add_executable(lm_utf8_test src/lm_utf8.c test/lm_utf8_test.c)
    target_include_directories(lm_utf8_test PRIVATE src)
    add_test(NAME lm_utf8_test COMMAND lm_utf8_test)

    add_executable(lm_shim_test ${LM_SOURCES} test/lm_shim_test.c)
    target_include_directories(lm_shim_test PRIVATE include src)
    target_link_libraries(lm_shim_test PRIVATE llama)

    add_executable(lm_generate ${LM_SOURCES} test/lm_generate.c)
    target_include_directories(lm_generate PRIVATE include src)
    target_link_libraries(lm_generate PRIVATE llama)
elseif(ANDROID)
    if(NOT DEFINED CBINDING_JNI_BRIDGE)
        message(FATAL_ERROR "CBINDING_JNI_BRIDGE must point at the generated JNI bridge")
    endif()
    add_library(lmshim SHARED ${LM_SOURCES} ${CBINDING_JNI_BRIDGE})
    target_include_directories(lmshim PRIVATE include src)
    target_link_libraries(lmshim PRIVATE llama log)
else()
    add_library(lmshim STATIC ${LM_SOURCES})
    target_include_directories(lmshim PUBLIC include PRIVATE src)
    target_link_libraries(lmshim PRIVATE llama)
endif()
