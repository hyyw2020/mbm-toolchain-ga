# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

set(Fcitx5ModulePunctuation_FOUND TRUE)

if (NOT TARGET Fcitx5::Module::Punctuation)
    # fix target name
    add_library(Fcitx5::Module::Punctuation ALIAS fcitx5-chinese-addons::punctuation)
endif()

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(Fcitx5ModulePunctuation
    FOUND_VAR
        Fcitx5ModulePunctuation_FOUND
    REQUIRED_VARS
        Fcitx5ModulePunctuation_FOUND
)
