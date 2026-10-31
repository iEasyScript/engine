package com.projectx.store

import com.projectx.store.Instances.State
import kotlin.test.Test
import kotlin.test.assertEquals

class InstancesTest {
    private fun next(current: State, status: Int?, error: String? = null, local: Boolean = false) = Instances.next(current, status, error, local)

    @Test
    fun `a granted claim holds a slot`() = assertEquals(State.HELD, next(State.CHECKING, 200))

    @Test
    fun `a claim with every slot taken is refused`() = assertEquals(State.FULL, next(State.CHECKING, 409, "no_instance_available"))

    @Test
    fun `a claim fails closed when the site can't be reached`() = assertEquals(State.UNREACHABLE, next(State.CHECKING, null))

    @Test
    fun `a store on this machine lets a claim through when it is down`() = assertEquals(State.HELD, next(State.CHECKING, null, local = true))

    @Test
    fun `a heartbeat keeps its slot through an outage or a server error`() {
        assertEquals(State.HELD, next(State.HELD, null))
        assertEquals(State.HELD, next(State.HELD, 502))
    }

    @Test
    fun `stop from the website is obeyed, and told apart from a full account`() {
        assertEquals(State.STOPPED, next(State.HELD, 409, "stopped"))
        assertEquals(State.FULL, next(State.HELD, 409, "no_instance_available"))
    }

    @Test
    fun `a rejected sign-in needs signing in again`() = assertEquals(State.SIGN_IN_EXPIRED, next(State.HELD, 401))
}
