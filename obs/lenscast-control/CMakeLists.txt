cmake_minimum_required(VERSION 3.16)
project(lenscast-control VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(Qt6 REQUIRED COMPONENTS Widgets Network)
find_package(PkgConfig REQUIRED)

# Use only the include/lib variables from pkg-config — NOT the IMPORTED_TARGET, whose
# interface compile options carry libobs' C flags (-Werror, -std=gnu17, -fopenmp-simd) that
# would break this C++ module.
pkg_check_modules(OBS REQUIRED libobs)
pkg_check_modules(OBS_FRONTEND REQUIRED obs-frontend-api)

add_library(${PROJECT_NAME} MODULE
  src/plugin-main.cpp
  src/lenscast-client.cpp
  src/lenscast-dock.cpp
)

target_compile_definitions(${PROJECT_NAME} PRIVATE PLUGIN_VERSION="${PROJECT_VERSION}")

# The Arch libobs.pc reports -I/usr/include (stripped by pkg-config as a default path) but the
# headers live in /usr/include/obs, so add the includedir/obs subdir explicitly.
target_include_directories(${PROJECT_NAME} PRIVATE
  ${OBS_INCLUDE_DIRS} ${OBS_INCLUDEDIR}/obs
  ${OBS_FRONTEND_INCLUDE_DIRS} ${OBS_FRONTEND_INCLUDEDIR}/obs)
target_link_directories(${PROJECT_NAME} PRIVATE
  ${OBS_LIBRARY_DIRS} ${OBS_FRONTEND_LIBRARY_DIRS})
target_link_libraries(${PROJECT_NAME} PRIVATE
  Qt6::Widgets Qt6::Network
  ${OBS_LIBRARIES} ${OBS_FRONTEND_LIBRARIES})

# Linux loads modules as <name>.so with no "lib" prefix.
set_target_properties(${PROJECT_NAME} PROPERTIES PREFIX "")

# Default install target is the per-user OBS plugin layout:
#   ~/.config/obs-studio/plugins/<name>/bin/64bit/<name>.so
#   ~/.config/obs-studio/plugins/<name>/data/locale/en-US.ini
if(NOT DEFINED OBS_PLUGIN_DESTINATION)
  set(OBS_PLUGIN_DESTINATION "$ENV{HOME}/.config/obs-studio/plugins/${PROJECT_NAME}")
endif()
install(TARGETS ${PROJECT_NAME} LIBRARY DESTINATION "${OBS_PLUGIN_DESTINATION}/bin/64bit")
install(DIRECTORY data/ DESTINATION "${OBS_PLUGIN_DESTINATION}/data")
