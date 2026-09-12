package com.projectx.ui.backend.dsl.utils

object ImGuiTableFlags {
    const val None = 0
    const val Resizable = 1 shl 0
    const val Reorderable = 1 shl 1
    const val Hideable = 1 shl 2
    const val Sortable = 1 shl 3
    const val NoSavedSettings = 1 shl 4
    const val ContextMenuInBody = 1 shl 5
    const val RowBg = 1 shl 6
    const val BordersInnerH = 1 shl 7
    const val BordersOuterH = 1 shl 8
    const val BordersInnerV = 1 shl 9
    const val BordersOuterV = 1 shl 10
    const val BordersH = BordersInnerH or BordersOuterH
    const val BordersV = BordersInnerV or BordersOuterV
    const val BordersInner = BordersInnerV or BordersInnerH
    const val BordersOuter = BordersOuterV or BordersOuterH
    const val Borders = BordersInner or BordersOuter
    const val NoBordersInBody = 1 shl 11
    const val NoBordersInBodyUntilResize = 1 shl 12
    const val SizingFixedFit = 1 shl 13
    const val SizingFixedSame = 2 shl 13
    const val SizingStretchProp = 3 shl 13
    const val SizingStretchSame = 4 shl 13
    const val NoHostExtendX = 1 shl 16
    const val NoHostExtendY = 1 shl 17
    const val NoKeepColumnsVisible = 1 shl 18
    const val PreciseWidths = 1 shl 19
    const val NoClip = 1 shl 20
    const val PadOuterX = 1 shl 21
    const val NoPadOuterX = 1 shl 22
    const val NoPadInnerX = 1 shl 23
    const val ScrollX = 1 shl 24
    const val ScrollY = 1 shl 25
    const val SortMulti = 1 shl 26
    const val SortTristate = 1 shl 27
    const val HighlightHoveredColumn = 1 shl 28
}

object ImGuiTableColumnFlags {
    const val None = 0
    const val Disabled = 1 shl 0
    const val DefaultHide = 1 shl 1
    const val DefaultSort = 1 shl 2
    const val WidthStretch = 1 shl 3
    const val WidthFixed = 1 shl 4
    const val NoResize = 1 shl 5
    const val NoReorder = 1 shl 6
    const val NoHide = 1 shl 7
    const val NoClip = 1 shl 8
    const val NoSort = 1 shl 9
    const val NoSortAscending = 1 shl 10
    const val NoSortDescending = 1 shl 11
    const val NoHeaderLabel = 1 shl 12
    const val NoHeaderWidth = 1 shl 13
    const val PreferSortAscending = 1 shl 14
    const val PreferSortDescending = 1 shl 15
    const val IndentEnable = 1 shl 16
    const val IndentDisable = 1 shl 17
    const val AngledHeader = 1 shl 18
}

object ImGuiTabBarFlags {
    const val None = 0
    const val Reorderable = 1 shl 0
    const val AutoSelectNewTabs = 1 shl 1
    const val TabListPopupButton = 1 shl 2
    const val NoCloseWithMiddleMouseButton = 1 shl 3
    const val NoTabListScrollingButtons = 1 shl 4
    const val NoTooltip = 1 shl 5
    const val DrawSelectedOverline = 1 shl 6
    const val FittingPolicyMixed = 1 shl 7
    const val FittingPolicyShrink = 1 shl 8
    const val FittingPolicyScroll = 1 shl 9
    const val FittingPolicyMask_ = FittingPolicyMixed or FittingPolicyShrink or FittingPolicyScroll
    const val FittingPolicyDefault_ = FittingPolicyMixed
}

object ImGuiTabItemFlags {
    const val None = 0
    const val UnsavedDocument = 1 shl 0
    const val SetSelected = 1 shl 1
    const val NoCloseWithMiddleMouseButton = 1 shl 2
    const val NoPushId = 1 shl 3
    const val NoTooltip = 1 shl 4
    const val NoReorder = 1 shl 5
    const val Leading = 1 shl 6
    const val Trailing = 1 shl 7
    const val NoAssumedClosure = 1 shl 8
}

object ImGuiDir {
    const val None = -1
    const val Left = 0
    const val Right = 1
    const val Up = 2
    const val Down = 3
}

object ImGuiTreeNodeFlags {
    const val None = 0
    const val Selected = 1 shl 0                // Draw as selected
    const val Framed = 1 shl 1                  // Draw frame with background (e.g. for CollapsingHeader)
    const val AllowItemOverlap = 1 shl 2        // Hit testing to allow subsequent widgets to overlap this one, if the next widget is also a tree node
    const val NoTreePushOnOpen = 1 shl 3        // Don't do a TreePush() when open (e.g. for CollapsingHeader) = no extra indent nor pushing on ID stack
    const val NoAutoOpenOnLog = 1 shl 4         // Don't automatically and temporarily open node when Logging is active (by default logging will automatically open tree nodes)
    const val DefaultOpen = 1 shl 5             // Default node to be open
    const val OpenOnDoubleClick = 1 shl 6       // Need double-click to open node
    const val OpenOnArrow = 1 shl 7             // Only open when clicking on the arrow part. If ImGuiTreeNodeFlags_OpenOnDoubleClick is also set, single-click arrow or double-click all box to open.
    const val Leaf = 1 shl 8                    // No collapsing, no arrow (use as a convenience for leaf nodes)
    const val Bullet = 1 shl 9                  // Display a bullet instead of arrow. IMPORTANT: node can still be marked open/close if you don't set the _Leaf flag!
    const val FramePadding = 1 shl 10           // Use FramePadding (even for an unframed text node) to vertically align text baseline to regular widget height. Equivalent to calling AlignTextToFramePadding().
    const val SpanAvailWidth = 1 shl 11         // Extend hit box to the right-most edge, even if not framed. This is not the default in order to allow adding other items on the same line without issues.
    const val SpanFullWidth = 1 shl 12          // Extend hit box to both the left-most and the right-most edges (covering the indent area).
    const val SpanAllColumns = 1 shl 13         // Frame will span all columns of its container (text + highlight).
    const val NavLeftJumpsToParent = 1 shl 14   // (WIP) Nav: left direction may move to this TreeNode() from any of its child (items submitted between TreeNode and TreePop)
    const val CollapsingHeader = Framed or NoTreePushOnOpen or NoAutoOpenOnLog
}

object ImGuiChildFlags {
    const val None = 0
    // Bit 0 is guaranteed to equal 1 (true) for back-compat with legacy 'bool border'
    const val Borders = 1 shl 0
    const val AlwaysUseWindowPadding = 1 shl 1
    const val ResizeX = 1 shl 2
    const val ResizeY = 1 shl 3
    const val AutoResizeX = 1 shl 4
    const val AutoResizeY = 1 shl 5
    const val AlwaysAutoResize = 1 shl 6
    const val FrameStyle = 1 shl 7
    const val NavFlattened = 1 shl 8
    const val Border = Borders
}
