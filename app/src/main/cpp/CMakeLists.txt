cmake_minimum_required(VERSION 3.22.1)
project(drum_engine CXX)

add_library(drum_engine SHARED drum_jni.cpp)
target_compile_features(drum_engine PRIVATE cxx_std_17)
target_compile_options(drum_engine PRIVATE -Wall -Wextra)
target_link_libraries(drum_engine PRIVATE aaudio android log)
