cmake_minimum_required(VERSION 3.22.1)
project(rajdex_yuv)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(rajdex_yuv SHARED NativeYuvConverter.cpp)
target_compile_options(rajdex_yuv PRIVATE -O3 -DNDEBUG)
target_link_libraries(rajdex_yuv android jnigraphics log)
