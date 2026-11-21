cmake_minimum_required(VERSION 3.24)
project(nnggram_translator LANGUAGES C CXX)
include(FetchContent)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
set(CMAKE_POLICY_VERSION_MINIMUM 3.10)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_MKL OFF CACHE BOOL "" FORCE)
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME NONE CACHE STRING "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)

FetchContent_Declare(ctranslate2
    GIT_REPOSITORY https://github.com/OpenNMT/CTranslate2.git
    GIT_TAG ef7e518efa48a19bc3e4c1cfca663f743b1e1916
    GIT_SUBMODULES third_party/cpu_features third_party/spdlog third_party/ruy)
FetchContent_Declare(sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG 31646a467d2051eb904e0b45de3a73e91fe1c1e3)
FetchContent_Declare(fasttext
    GIT_REPOSITORY https://github.com/facebookresearch/fastText.git
    GIT_TAG 5b5943c118b0ec5fb9cd8d20587de2b2d3966dfe
    SOURCE_SUBDIR no-cmake-project)
FetchContent_MakeAvailable(ctranslate2 sentencepiece fasttext)

find_package(Threads REQUIRED)
file(GLOB fasttext_sources "${fasttext_SOURCE_DIR}/src/*.cc")
list(FILTER fasttext_sources EXCLUDE REGEX "/main[.]cc$")
add_library(translation_language_detector STATIC ${fasttext_sources})
target_include_directories(translation_language_detector PUBLIC "${fasttext_SOURCE_DIR}/src")
target_link_libraries(translation_language_detector PRIVATE Threads::Threads)
# The pinned fastText sources use fixed-width integers without including their header.
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    target_compile_options(translation_language_detector PRIVATE -include cstdint)
endif()

add_executable(nnggram-translator main.cpp)
target_include_directories(nnggram-translator PRIVATE "${sentencepiece_SOURCE_DIR}/src")
target_link_libraries(nnggram-translator PRIVATE ctranslate2 sentencepiece-static translation_language_detector)
set(language_model "${CMAKE_CURRENT_BINARY_DIR}/lid.176.ftz")
if(NOT EXISTS "${language_model}")
    file(DOWNLOAD https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz "${language_model}.part"
        EXPECTED_HASH SHA256=8f3472cfe8738a7b6099e8e999c3cbfae0dcd15696aac7d7738a8039db603e83
        TLS_VERIFY ON INACTIVITY_TIMEOUT 30 TIMEOUT 90)
    file(RENAME "${language_model}.part" "${language_model}")
endif()
install(TARGETS nnggram-translator RUNTIME DESTINATION . COMPONENT offline-translator)
install(FILES "${language_model}" DESTINATION . COMPONENT offline-translator)
install(FILES NOTICE.txt DESTINATION licenses COMPONENT offline-translator)
install(FILES "${fasttext_SOURCE_DIR}/LICENSE"
    DESTINATION licenses RENAME fastText-LICENSE.txt COMPONENT offline-translator)
install(FILES "${ctranslate2_SOURCE_DIR}/LICENSE"
    DESTINATION licenses RENAME CTranslate2-LICENSE.txt COMPONENT offline-translator)
install(FILES "${sentencepiece_SOURCE_DIR}/LICENSE"
    DESTINATION licenses RENAME SentencePiece-LICENSE.txt COMPONENT offline-translator)
install(FILES "${ctranslate2_SOURCE_DIR}/third_party/ruy/LICENSE"
    DESTINATION licenses RENAME Ruy-LICENSE.txt COMPONENT offline-translator)
install(FILES "${ctranslate2_SOURCE_DIR}/third_party/ruy/third_party/cpuinfo/LICENSE"
    DESTINATION licenses RENAME cpuinfo-LICENSE.txt COMPONENT offline-translator)
install(FILES "${ctranslate2_SOURCE_DIR}/third_party/spdlog/LICENSE"
    DESTINATION licenses RENAME spdlog-LICENSE.txt COMPONENT offline-translator)
install(FILES "${ctranslate2_SOURCE_DIR}/third_party/cpu_features/LICENSE"
    DESTINATION licenses RENAME cpu-features-LICENSE.txt COMPONENT offline-translator)
foreach(dependency absl darts_clone protobuf-lite)
    install(FILES "${sentencepiece_SOURCE_DIR}/third_party/${dependency}/LICENSE"
        DESTINATION licenses RENAME "${dependency}-LICENSE.txt" COMPONENT offline-translator)
endforeach()
