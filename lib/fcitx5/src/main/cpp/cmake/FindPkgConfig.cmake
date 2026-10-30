# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

set(PkgConfig_FOUND TRUE)

function(pkg_check_modules)
    message("pkg_check_modules: ${ARGV}")
endfunction()

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(PkgConfig
    FOUND_VAR
        PkgConfig_FOUND
    REQUIRED_VARS
        PkgConfig_FOUND
)
