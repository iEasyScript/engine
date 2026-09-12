package com.projectx.ui.tabs

import com.projectx.ui.backend.dsl.scopes.ChildScope
import com.projectx.ui.backend.dsl.scopes.tabBar

/**
 * The two surfaces that paint the 3D scene, sharing one sidebar entry.
 *
 * Each keeps its own switches beside the output they govern rather than pooling them: the entity toggles are
 * capture switches, not just render ones - the browser is only populated for the types they enable - so moving
 * them away from the list would hide why it is empty.
 */
object SceneTab {
    fun ChildScope.render() {
        tabBar("scene") {
            tabItem("Entities") {
                with(EntitiesTab) { render() }
            }
            tabItem("Collision") {
                with(CollisionDebugTab) { render() }
            }
        }
    }
}
