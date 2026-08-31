package to.eyed.seeker.code.ui.shell

/**
 * The shell's navigation model: three destinations, three stacks, one sealed
 * [Route] type — hand-rolled, and deliberately not `androidx.navigation`.
 *
 * There is no navigation dependency in app/build.gradle.kts today and this
 * file is why one is not being added (docs/UI.md, P1). The library's unit of
 * work is a `NavHost` with a back stack per graph and a `SavedStateHandle` per
 * entry; what this shell needs is three lists of a closed set of routes, a
 * `pop`, and an ordering rule the back handler can read synchronously
 * (ShellBackHandler.kt). That is this file, and it is small enough that the
 * whole navigation behaviour of the app is readable in one screen.
 *
 * Everything here is immutable value types on purpose: [ShellState] holds the
 * stacks in one snapshot-state slot, so a push is one write that recomposes
 * once, and the whole navigation state of the app is a value a test can build,
 * compare and print (RouteStackTest).
 */

/**
 * The three stops on the bottom bar, and nothing else is a destination
 * (docs/UI.md, "Navigation"). Files is a sheet, Changes is a route, Shell is a
 * *mode* of [Build] reached by a header chip — none of them are here, and the
 * enum having exactly three entries is the specification, not an accident.
 *
 * Declaration order is left-to-right order in the bar.
 */
enum class Destination {
    /** The editor, full-bleed. The start destination and the one back ends at. */
    Code,

    /** One ACP conversation on the current project. */
    Agent,

    /** Cluster, wallet, program id, build output — and Shell, its second mode. */
    Build,
}

/**
 * A full-screen route: pushed *over* a destination, keeping the nav bar, and
 * owning a ← in its own top row (docs/UI.md, "Navigation").
 *
 * The set is closed and it is short. A surface that is not on this list is
 * either a destination (three of those) or a modal bottom sheet (everything
 * else) — there is no third kind of navigation in this app, which is what
 * makes an eight-step back handler enough.
 */
sealed interface Route {

    /**
     * Whether pushing this route takes the bottom bar away with it.
     *
     * Exactly one route does. Setup is a takeover — on a fresh install it is
     * the first thing the app shows, with nothing behind it worth switching
     * to, and a nav bar offering Build while the toolchain that Build needs is
     * still downloading would be an invitation to a dead end (docs/UI.md,
     * "First run").
     */
    val hidesNavBar: Boolean get() = false

    /** Agent Keep/Reject fused with git's status list, per docs/UI.md. */
    data object Changes : Route

    /**
     * One file's diff. [staged] picks which side of the index it is read from;
     * agent edits are always the unstaged side.
     */
    data class Diff(val path: String, val staged: Boolean = false) : Route

    /** The merged diagnostics list — LSP's and cargo's, per solana/build. */
    data object Problems : Route

    /** Fourteen rows and "Edit settings.json". */
    data object Settings : Route

    /**
     * Every component this package is built from, and its licence — the
     * screen docs/LICENSING.md §5 specifies. Pushed from Settings and from
     * About; keeps the nav bar, like every route but Setup.
     */
    data object Licences : Route

    /**
     * One component's full licence text.
     *
     * A route rather than state inside [Licences] so that back walks list →
     * detail → list the way a reader expects, through the same
     * ShellBackHandler step (BackStep.PopRoute) that every other pair of
     * screens uses. §5 describes "list → detail" and leaves the mechanism
     * open; making the detail a second route is what keeps the back gesture
     * out of the screen's own hands.
     *
     * [name] is carried rather than looked up because [Route.title] is a pure
     * property with no access to the catalogue — and a title that had to read
     * a 260 KB asset to print itself would be a title that arrives a frame
     * late. [id] is the row's stable identifier from components.json.
     */
    data class LicenceDetail(val id: String, val name: String) : Route

    /** Name, framework, cluster, "open a thread afterwards". */
    data object NewProgram : Route

    /** GitClone.kt with its progress and credential prompts. */
    data object Clone : Route

    /**
     * The Spettro account — email, plan, credits, connected providers and the
     * model favourites (ui/agent/spettro/SetupSheets.kt). A route rather than
     * a sheet because it is reached from two places — Settings' Agent section
     * and the Agent overflow — and both expect back to pop it like any other
     * drill page; keeps the nav bar, unlike the login gate it grew out of.
     */
    data object SpettroSettings : Route

    /** The toolchain takeover — the one route that hides the bar. */
    data object Setup : Route {
        override val hidesNavBar: Boolean get() = true
    }
}

/**
 * One destination's stack of full-screen routes, innermost last.
 *
 * Empty means "the destination itself is on screen", which is the state two of
 * the three stacks are in almost all the time. The destination is not an entry
 * in the stack: it is the floor, it cannot be popped, and modelling it as an
 * entry would mean every reader had to remember that a stack of size one is
 * empty (androidx.navigation's start destination is exactly that trap).
 */
@JvmInline
value class RouteStack(val routes: List<Route> = emptyList()) {

    /** What is drawn, or null when the destination itself is. */
    val top: Route? get() = routes.lastOrNull()

    val isEmpty: Boolean get() = routes.isEmpty()

    val depth: Int get() = routes.size

    /** Whether the bar is hidden right now — see [Route.hidesNavBar]. */
    val hidesNavBar: Boolean get() = top?.hidesNavBar == true

    /**
     * Push [route], unless it is already on top.
     *
     * The guard is not tidiness. Every push in this app is a tap on a row —
     * a diagnostic, a changed file, a settings entry — and a double tap on a
     * row is a slip, not a request for the same screen twice; without this,
     * back would have to be pressed twice to undo one accidental tap. Two
     * *different* diffs still stack, because walking from one file's diff to
     * another's is a real path through the app.
     */
    fun push(route: Route): RouteStack =
        if (top == route) this else RouteStack(routes + route)

    /** Drop the top route. Popping an empty stack is a no-op, not an error. */
    fun pop(): RouteStack =
        if (routes.isEmpty()) this else RouteStack(routes.dropLast(1))

    /** Back to the destination itself — what switching project does to all three. */
    fun clear(): RouteStack = RouteStack()

    operator fun contains(route: Route): Boolean = route in routes
}

/**
 * The three stacks, kept together so [ShellState] holds one value rather than
 * three that could disagree.
 *
 * They are independent, and that is the whole point of the model: opening a
 * file from Agent pushes Code's editor onto *Agent's* stack, so back returns
 * to the conversation you were reading, while Code's own stack — and its
 * caret, and its scroll — are exactly where you left them (docs/UI.md,
 * "Navigation"). A single shared stack, which is what one `NavHost` would
 * give, cannot express that.
 */
data class RouteStacks(
    val code: RouteStack = RouteStack(),
    val agent: RouteStack = RouteStack(),
    val build: RouteStack = RouteStack(),
) {

    operator fun get(destination: Destination): RouteStack = when (destination) {
        Destination.Code -> code
        Destination.Agent -> agent
        Destination.Build -> build
    }

    /** This set with one destination's stack replaced. */
    fun with(destination: Destination, stack: RouteStack): RouteStacks = when (destination) {
        Destination.Code -> copy(code = stack)
        Destination.Agent -> copy(agent = stack)
        Destination.Build -> copy(build = stack)
    }

    fun push(destination: Destination, route: Route): RouteStacks =
        with(destination, this[destination].push(route))

    fun pop(destination: Destination): RouteStacks =
        with(destination, this[destination].pop())

    /**
     * Every stack emptied — what closing a project does. A route naming a file
     * in a project that is no longer open is a screen that cannot draw itself,
     * and OpenFiles.kt clears its jump list on the same event and for the same
     * reason (OpenFiles.kt:924).
     */
    fun clear(): RouteStacks = RouteStacks()
}
