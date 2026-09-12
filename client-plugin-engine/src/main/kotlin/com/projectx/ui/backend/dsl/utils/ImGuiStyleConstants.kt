package com.projectx.ui.backend.dsl.utils

enum class ImGuiCol {
    Text,
    TextDisabled,
    WindowBg,              // Background of normal windows
    ChildBg,               // Background of child windows
    PopupBg,               // Background of popups, menus, tooltips windows
    Border,
    BorderShadow,
    FrameBg,               // Background of checkbox, radio button, plot, slider, text input
    FrameBgHovered,
    FrameBgActive,
    TitleBg,               // Title bar
    TitleBgActive,         // Title bar when focused
    TitleBgCollapsed,      // Title bar when collapsed
    MenuBarBg,
    ScrollbarBg,
    ScrollbarGrab,
    ScrollbarGrabHovered,
    ScrollbarGrabActive,
    CheckMark,             // Checkbox tick and RadioButton circle
    SliderGrab,
    SliderGrabActive,
    Button,
    ButtonHovered,
    ButtonActive,
    Header,                // Header* colors are used for CollapsingHeader, TreeNode, Selectable, MenuItem
    HeaderHovered,
    HeaderActive,
    Separator,
    SeparatorHovered,
    SeparatorActive,
    ResizeGrip,            // Resize grip in lower-right and lower-left corners of windows.
    ResizeGripHovered,
    ResizeGripActive,
    InputTextCursor,       // InputText cursor/caret
    TabHovered,            // Tab background, when hovered
    Tab,                   // Tab background, when tab-bar is focused & tab is unselected
    TabSelected,           // Tab background, when tab-bar is focused & tab is selected
    TabSelectedOverline,   // Tab horizontal overline, when tab-bar is focused & tab is selected
    TabDimmed,             // Tab background, when tab-bar is unfocused & tab is unselected
    TabDimmedSelected,     // Tab background, when tab-bar is unfocused & tab is selected
    TabDimmedSelectedOverline,//..horizontal overline, when tab-bar is unfocused & tab is selected
    PlotLines,
    PlotLinesHovered,
    PlotHistogram,
    PlotHistogramHovered,
    TableHeaderBg,         // Table header background
    TableBorderStrong,     // Table outer and header borders (prefer using Alpha=1.0 here)
    TableBorderLight,      // Table inner borders (prefer using Alpha=1.0 here)
    TableRowBg,            // Table row background (even rows)
    TableRowBgAlt,         // Table row background (odd rows)
    TextLink,              // Hyperlink color
    TextSelectedBg,        // Selected text inside an InputText
    TreeLines,             // Tree node hierarchy outlines when using ImGuiTreeNodeFlags_DrawLines
    DragDropTarget,        // Rectangle highlighting a drop target
    NavCursor,             // Color of keyboard/gamepad navigation cursor/rectangle, when visible
    NavWindowingHighlight, // Highlight window when using CTRL+TAB
    NavWindowingDimBg,     // Darken/colorize entire screen behind the CTRL+TAB window list, when active
    ModalWindowDimBg,      // Darken/colorize entire screen behind a modal window, when one is active
    COUNT,
}

enum class ImGuiStyleVar {
    Alpha,
    DisabledAlpha,
    WindowPadding,
    WindowRounding,
    WindowBorderSize,
    WindowMinSize,
    WindowTitleAlign,
    ChildRounding,
    ChildBorderSize,
    PopupRounding,
    PopupBorderSize,
    FramePadding,
    FrameRounding,
    FrameBorderSize,
    ItemSpacing,
    ItemInnerSpacing,
    IndentSpacing,
    CellPadding,
    ScrollbarSize,
    ScrollbarRounding,
    GrabMinSize,
    GrabRounding,
    ImageBorderSize,
    TabRounding,
    TabBorderSize,
    TabMinWidthBase,
    TabMinWidthShrink,
    TabBarBorderSize,
    TabBarOverlineSize,
    TableAngledHeadersAngle,
    TableAngledHeadersTextAlign,
    TreeLinesSize,
    TreeLinesRounding,
    ButtonTextAlign,
    SelectableTextAlign,
    SeparatorTextBorderSize,
    SeparatorTextAlign,
    SeparatorTextPadding,
    COUNT
}