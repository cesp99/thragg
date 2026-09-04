package to.eyed.thragg.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation model, which is hand-rolled precisely so that it can be
 * tested like this: three lists of a closed set of routes, no `NavHost`, no
 * `SavedStateHandle`, no Android on the classpath.
 *
 * What is pinned here is what the shell's behaviour depends on — the empty
 * stack meaning "the destination itself", the double-tap collapse, and the
 * independence of the three stacks, which is the one property the whole
 * "opening a file from Agent comes back to Agent" rule rests on.
 */
class RouteStackTest {

    @Test
    fun `an empty stack is the destination itself`() {
        val stack = RouteStack()
        assertTrue(stack.isEmpty)
        assertNull(stack.top)
        assertEquals(0, stack.depth)
        assertFalse(stack.hidesNavBar)
    }

    @Test
    fun `push puts a route on top`() {
        val stack = RouteStack().push(Route.Changes).push(Route.Problems)
        assertEquals(Route.Problems, stack.top)
        assertEquals(2, stack.depth)
        assertTrue(Route.Changes in stack)
    }

    @Test
    fun `pushing the route already on top is a double tap, not a second screen`() {
        val once = RouteStack().push(Route.Settings)
        val twice = once.push(Route.Settings)
        assertEquals(1, twice.depth)
        assertEquals(once, twice)
    }

    @Test
    fun `two different diffs still stack`() {
        val stack = RouteStack()
            .push(Route.Diff("src/lib.rs"))
            .push(Route.Diff("src/state.rs"))
        assertEquals(2, stack.depth)
        assertEquals(Route.Diff("src/state.rs"), stack.top)
    }

    @Test
    fun `the same diff on the same side is the same route`() {
        val stack = RouteStack()
            .push(Route.Diff("src/lib.rs", staged = false))
            .push(Route.Diff("src/lib.rs", staged = false))
        assertEquals(1, stack.depth)
    }

    @Test
    fun `the staged and unstaged diffs of one file are different routes`() {
        val stack = RouteStack()
            .push(Route.Diff("src/lib.rs", staged = false))
            .push(Route.Diff("src/lib.rs", staged = true))
        assertEquals(2, stack.depth)
    }

    @Test
    fun `pop removes the top and pop on empty is a no-op`() {
        val stack = RouteStack().push(Route.Changes).push(Route.Problems)
        assertEquals(Route.Changes, stack.pop().top)
        val empty = RouteStack()
        assertSame(empty.routes, empty.pop().routes)
        assertTrue(empty.pop().isEmpty)
    }

    @Test
    fun `clear goes back to the destination`() {
        val stack = RouteStack().push(Route.Changes).push(Route.Diff("a.rs"))
        assertTrue(stack.clear().isEmpty)
    }

    /**
     * "Setup is the one route that hides the nav bar" — docs/UI.md,
     * "Navigation". Asserted over the whole set rather than on Setup alone, so
     * a route added later has to choose deliberately.
     */
    @Test
    fun `setup is the only route that hides the bar`() {
        val routes = listOf(
            Route.Changes,
            Route.Diff("a.rs"),
            Route.Problems,
            Route.Settings,
            Route.NewProgram,
            Route.Clone,
            Route.Setup,
        )
        assertEquals(listOf<Route>(Route.Setup), routes.filter { it.hidesNavBar })
        assertTrue(RouteStack().push(Route.Setup).hidesNavBar)
        assertFalse(RouteStack().push(Route.Setup).push(Route.Settings).hidesNavBar)
    }

    // ---- The three stacks ---------------------------------------------------

    @Test
    fun `the three stacks are independent`() {
        val stacks = RouteStacks()
            .push(Destination.Agent, Route.Diff("src/state.rs"))
            .push(Destination.Build, Route.Problems)
        assertTrue(stacks[Destination.Code].isEmpty)
        assertEquals(Route.Diff("src/state.rs"), stacks[Destination.Agent].top)
        assertEquals(Route.Problems, stacks[Destination.Build].top)
    }

    @Test
    fun `popping one destination leaves the others alone`() {
        val stacks = RouteStacks()
            .push(Destination.Agent, Route.Changes)
            .push(Destination.Code, Route.Problems)
            .pop(Destination.Agent)
        assertTrue(stacks[Destination.Agent].isEmpty)
        assertEquals(Route.Problems, stacks[Destination.Code].top)
    }

    @Test
    fun `clear empties all three`() {
        val stacks = RouteStacks()
            .push(Destination.Code, Route.Changes)
            .push(Destination.Agent, Route.Changes)
            .push(Destination.Build, Route.Changes)
            .clear()
        assertEquals(RouteStacks(), stacks)
    }

    @Test
    fun `with replaces exactly one stack`() {
        val stacks = RouteStacks().with(Destination.Build, RouteStack().push(Route.Setup))
        assertEquals(Route.Setup, stacks[Destination.Build].top)
        assertTrue(stacks[Destination.Code].isEmpty)
        assertTrue(stacks[Destination.Agent].isEmpty)
    }

    /** Left to right in the bar, and exactly three. */
    @Test
    fun `there are three destinations, in bar order`() {
        assertEquals(
            listOf(Destination.Code, Destination.Agent, Destination.Build),
            Destination.entries.toList(),
        )
    }
}
