cmake_minimum_required(VERSION 3.22.1)

project("oorty_engine")

add_library(
    oorty_engine
    SHARED
    oorty_engine.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    oorty_engine
    ${log-lib}
)
