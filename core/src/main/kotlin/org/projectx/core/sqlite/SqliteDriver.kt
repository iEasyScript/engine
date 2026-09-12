package org.projectx.core.sqlite

import java.sql.Driver
import java.sql.DriverManager

/**
 * The injected engine loads through a child URLClassLoader that DriverManager's ServiceLoader
 * auto-registration never scans, so a plain getConnection fails with "No suitable driver found".
 * Registering explicitly through this class's loader is a no-op on a normal classpath.
 */
object SqliteDriver {

    private var registered = false

    @Synchronized
    fun register() {
        if (registered) return
        runCatching {
            val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
            DriverManager.registerDriver(driver)
        }
        registered = true
    }
}
