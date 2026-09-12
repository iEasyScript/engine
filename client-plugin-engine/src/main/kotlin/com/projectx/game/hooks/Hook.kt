package com.projectx.game.hooks

import com.projectx.game.platform.Platform

/**
 * Hooks the client function published in the offset table's `OFunctions` under [value]. A build
 * whose table has no such entry leaves the hook uninstalled rather than hooking a bogus address.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Hook(val value: String, val priority: Priority = Priority.NORMAL)

/**
 * Symbolic hook will lookup the symbol passed into its value and hook the function at that location.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SymbolHook(val library: String, val symbol: String, val priority: Priority = Priority.NORMAL)

/** Restricts a hook, or a whole hook container, to the listed platforms. Absent means every platform. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SupportedOn(vararg val platforms: Platform)

enum class Priority { FIRST, HIGH, NORMAL, LOW, LAST }
