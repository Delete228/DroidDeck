# Unified input configuration and routing proposal

Status: implemented locally for device evaluation; not published or approved by maintainers.
Prepared on 2026-10-23 against `ac0909ea`.
Related source snapshots: PR #1202 at `42db788f`, PR #1305 at `76fc9b31`.

## Problem and scope

DroidDeck has useful input paths whose configuration and lifecycle rules are
spread across several components. Desktop users need configurable physical
controller bindings. Dual-screen users also need to arrange and configure lower
screen controls without giving up Steam Deck trackpads or Steam Input layouts.

Provide one configuration model and reusable action handling while preserving
the existing low-latency transports and Steam's ownership of Steam-managed
bindings. A common model does not require all input to cross a new queue, UI
thread, generic event bus, or virtual device.

The implementation has two editable built-in profiles, Game and
Desktop. Named profile creation, per-application selection, and profile
import/export are future work; the stored model must allow them without changing
the routing contract.

## Existing paths to preserve

| Source | Current path | Meaning |
| --- | --- | --- |
| Android physical pad | `PadBridge` -> `FakeInputWriter` | Gamepad buttons, axes, player slots |
| Built-in screen pad, including the lower-screen Gamepad tab | `PadBridge.applyTouch` -> `FakeInputWriter` | Virtual gamepad input |
| Lower-screen Deck trackpads and back grips | `DeckControlsPanel` -> `DeckControls` -> `FakeInputWriter.writeDeckControls` | Touch coordinates, contact, click/pressure, L4/L5/R4/R5 |
| Steam touch overlay | `SteamTouchControls` -> `SteamTouchDevice` -> Steam | Steam Mobile Touch reports, with Steam-owned bindings and action sets |
| Desktop trackpad and keyboard | Existing gesture/keyboard handlers -> compositor | Direct guest mouse and keyboard input |
| Steam Controller BLE and proposed native DualSense | Driver-specific report paths | Input can bypass Android `KeyEvent` dispatch |

Deck trackpads must not be reduced to mouse deltas or a pair of ordinary joystick
axes. Their contact, coordinates, click state and pressure are meaningful to
Steam. Likewise, Steam Mobile Touch is a distinct protocol, not another spelling
of an Xbox pad.

See [Steam touch controls](steam-touch-controls.md) for the current integration,
including Steam-owned layouts, action sets and cloud configuration.

## Separate bindings, screen layout, and device calibration

The proposed model has three independent parts:

- **Binding profile:** logical input sources and their actions, with Game and
  Desktop defaults and user overrides.
- **Surface layout:** which controls appear on the primary or secondary control
  surface, their positions, sizes and presentation. A layout references bindings
  or Steam-managed controls; it does not copy Steam's action-set definitions.
- **Device configuration:** hardware identification, axis normalization,
  calibration and capabilities. This is separate from a user's desktop layout.

Use logical surface roles rather than persistent Android display IDs. Controls
need stable IDs independent of their location. Moving a control to the lower
screen must not change its action or create a new guest gamepad. The existing
player-slot and device identities remain authoritative.

A Game profile can use the lower screen for two Deck trackpads and back grips,
plus selected DroidDeck actions. A Desktop profile can put a mouse trackpad and
keyboard there while mapping the physical sticks to arrow keys, scrolling or
other actions. Connecting a second display must not silently rewrite physical
controller bindings.

The model should support reusable lower-screen layouts later. Initially expose
existing layouts and a bounded set of configurable controls; a complete freeform
multi-display editor is a separate deliverable.

## Steam Input ownership

Each binding explicitly selects an execution path:

1. **Native/Steam-managed:** send the existing controller or touch report.
   Steam remains responsible for game bindings, action sets, layers and its
   touch/radial menus. DroidDeck shows an appropriate entry into Steam's editor.
2. **DroidDeck-managed:** execute a guest key combination, mouse action or
   application action through the existing output functions. The consumed
   control is excluded from the normal controller output.
3. **Disabled:** intentionally produce no action.

Steam-managed and DroidDeck-managed controls can coexist on one surface. One
activation must have one semantic owner, so a local keyboard shortcut does not
also produce the original controller button. Gamepad-to-gamepad remaps remain
explicit preprocessing before the normal game/Steam path, rather than a second
independent action sent alongside it.

Steam Input already supports extensive binding and touch-menu behavior. This
proposal concentrates DroidDeck's work on Android surfaces, app shortcuts,
desktop/non-Steam bindings and consistent lifecycle handling. It does not propose
reimplementing Steam's configuration engine or importing its profiles into a
second authoritative store.

Preserve raw-report fields and output/feedback channels not affected by a local
binding, including motion and haptics. Driver-specific adapters must participate
before publishing consumed controls; an Activity-only interceptor cannot cover
raw HID devices.

## Configuration and runtime model

Responsibilities:

| Component | Responsibility |
| --- | --- |
| `InputProfile` | Version, stable ID, default role, bindings and surface-layout references |
| `SurfaceLayout` | Stable control IDs, display role, geometry and appearance |
| `InputBinding` | Source selector, activation rule, action and execution owner |
| Source adapters | Preserve device identity, normalize inputs and expose capabilities |
| Compiled binding plan | Pre-resolved routes for one active profile and its devices |
| Action executor/output ownership | Execute existing outputs and track held contributions |

Buttons and axis directions can produce keys, key combinations, mouse buttons,
gamepad buttons or app actions. Sticks can retain their gamepad role or produce
pointer velocity, scrolling or keyboard directions. Analog triggers need distinct
press/release thresholds when used as buttons. Capability-aware controls should
explain why an unavailable action cannot run, rather than silently substituting
another behavior.

The same action picker is reused for physical and local screen controls. Screen
geometry stays outside binding execution. Steam-managed widgets retain their
native configuration and protocol adapters.

Configuration is loaded, validated and compiled when it changes. Dispatch uses
an immutable plan snapshot and preallocated per-source state. JSON decoding,
preference reads, disk writes and layout traversal do not belong in event dispatch.
Use stable symbolic control/action identifiers in storage; transient Android
device IDs are runtime keys, not portable profile identifiers.

Profile selection defaults to the session's mode, with an explicit session
override. Desktop mode can contain a game, so it is not sufficient evidence for
automatic keyboard/mouse interception. An optional profile-switch action uses
the same app-action mechanism as menus and keyboard display.

## Ownership and lifecycle

- Track held keys and mouse buttons by source. Releasing one source must not
  release a key or button another source still owns.
- Cancel only a disappearing surface's or disconnected device's contributions;
  avoid a global release that also clears another screen or controller.
- Define arbitration for multiple sources targeting the same analog control or
  Deck touch surface. Retain ownership through a contact; do not let concurrent
  views overwrite each other's reports or add a second virtual pad implicitly.
- Changing a binding/profile releases its old outputs before installing the new
  route. Held controls must return to neutral before triggering a new binding.
- Menus, keyboard capture, pause, focus loss and device removal have explicit
  routing rules. Menu navigation retains working confirmation, cancellation and
  page controls even when those buttons have gameplay bindings.
- Keep system-reserved Android inputs with the system and retain touchscreen
  access to reset a problematic controller assignment.

Button chords require a deliberate policy. A dedicated modifier can select a
chord without delaying unrelated controls. A button that also has a single-press
action may require deferred dispatch to disambiguate a chord, hold or double tap.
That delay must be opt-in, visible in configuration and limited to the affected
binding. Do not buffer all controller presses to support optional chords.

## Latency requirements and verification

The goal is no additional scheduling stage for normal input, not an unmeasured
claim of zero added latency.

- Identity routes retain the existing direct publication path and report format.
- Ordinary buttons and touch reports must not wait for drawing, Compose state,
  a new coroutine, a timer, an event-bus subscriber or another frame.
- Add no hot-path allocations for ordinary binding lookup. Reuse buffers and
  keep critical sections short. The current router serializes routing and profile
  changes under one monitor to keep shared output ownership consistent; hardware
  traces must establish whether this causes contention before publication.
- Continuous stick-to-pointer movement needs a clock even when no new stick
  event arrives. Keep that scheduler limited to the active synthesized movement
  route; stop it at neutral. It must not schedule ordinary gamepad forwarding.
- Keep existing synchronization and low-latency flags intact until measurements
  justify a separate change. Do not weaken shared-memory publication ordering.
- Send UI updates separately from input publication. Marshaling app-menu work to
  Android's UI thread must not marshal ordinary controller output with it.

Before publishing the change, record a baseline on Thor with the same build
configuration, controller mode, display refresh rates and low-latency settings.
Compare identity forwarding, a local key binding, stick-to-pointer, Deck trackpad
input and simultaneous upper/lower-screen input under both idle and rendering
load. Measure p50/p95/p99 dispatch-to-publication time, allocations and contention.
Use debug-only sampling or buffered tracing; avoid per-event log writes.

Android-to-publication measurements do not measure complete button-to-photon
latency. Guest receipt and rendered response need separate measurement, with
clock-domain alignment for cross-process traces or an external high-speed-camera
test. Record benchmark conditions and uncertainty; establish numerical acceptance
budgets from the baseline before presenting latency claims in a PR.

Hard acceptance conditions include no new frame/timer wait for identity routes,
no duplicate or lost press/release transitions, and no degradation of current
Deck trackpad behavior. Microbenchmarks alone cannot establish those conditions.

## Reuse, migration, and removal

- [#1202](https://github.com/Droid-Deck/DroidDeck/pull/1202): reuse physical capture,
  device identification and menu-action UI where suitable. Generalize the
  execution contract beyond Activity `KeyEvent` interception.
- [#1305](https://github.com/Droid-Deck/DroidDeck/pull/1305): reuse PC-key definitions,
  key selection and screen-control presentation. Adapt keyboard output to shared
  ownership; its screen-layout format is not a complete physical-input profile.
- [#581](https://github.com/Droid-Deck/DroidDeck/pull/581): coordinate device
  calibration/profile matching, independently of user binding/surface profiles.
- [#1226](https://github.com/Droid-Deck/DroidDeck/pull/1226): use a shared keyboard
  action with the appropriate existing host/display, rather than hard-coding a
  second keyboard implementation.
- Reuse current guest keyboard/mouse output, `PadBridge`, Deck/Steam touch
  transports and the existing second-screen presentation lifecycle.

Remove duplicated settings, capture UI and action switches only after their
replacement preserves saved settings and passes behavior tests. Do not remove
working device adapters, Steam touch support or native transport code merely to
make the architecture diagram more uniform. Stage each migration so it can be
reviewed and reverted independently.

## Implemented local evaluation build

No PR is created yet. Test the complete integrated change on Thor before deciding
whether to publish it. The intended framing is **Shared input profiles and
configurable control surfaces**, with links to the desktop requests
[#860](https://github.com/Droid-Deck/DroidDeck/issues/860) and
[#1356](https://github.com/Droid-Deck/DroidDeck/issues/1356), the centralization
discussion in #1202, and the related work above. Those discussions are context,
not maintainer approval of this implementation.

### Runtime and configuration

- `InputProfile` separates actions and source roles from `InputSurfaceLayout`.
  `InputProfiles` stores a versioned document and migrates legacy touch mappings
  and Select-as-Steam into Game once. Editing one role does not edit another.
- `InputRouter` resolves actions when a profile changes. `PadSource` returns the
  original `PadState` on the identity path. Other routes reuse their output
  buffers and consume only the selected control. A key combination presses
  modifiers first and releases in reverse order. Trigger/direction hysteresis
  uses separate press and release thresholds.
- `PadBridge` preserves physical player slots and separately owns each touch
  surface. Merging uses OR for buttons, maximum trigger values and the strongest
  complete stick vector. Surface removal cannot release another source's hold.
- `DeckControls` keeps per-source contacts, coordinates, clicks and pressure.
  Default native actions retain the existing Deck report. Local trackpad actions
  use contact-relative deltas, avoiding a jump at first contact.
- `TritonInputRouter` routes Steam Controller BLE reports before publication. Its
  identity path returns the original byte array. Other paths preserve packet,
  timestamp, inertial data, grip-contact bits, report extensions and pressure.
  This adapter is covered by synthetic report tests; physical BLE testing is
  still required.
- `PhysicalInputBindings` captures device identity, keycode and scancode, including
  controller buttons exposed as keyboard keys. It consumes a configured local
  action before normal dispatch and keeps releases paired across profile edits.
  Android volume and system keys remain reserved.
- `HeldInput` coordinates local mappings with existing guest keyboard, gesture,
  mouse and second-screen outputs. `InputMotion` schedules only synthesized
  stick movement/scrolling. Buttons and trackpad deltas publish synchronously;
  UI commands use the existing main-thread handler.
- Profile changes, suspension and menu entry release local outputs and require
  held inputs to return to neutral before activating their new actions.

### User interface

Input profiles are available from Controller setup and the session's Controller
panel. Auto selects Desktop for a desktop session and Game for other sessions;
manual selection handles games launched from a desktop without guessing from
window focus. The editor exposes physical, primary-screen and secondary-screen
roles, native/remapped gamepad output, local actions and disabled bindings.

Custom controls is an additional second-screen mode. Its editor arranges a
bounded set of existing `DeckControlsPanel` buttons, sticks, trackpads and grips.
Layout geometry is independent of bindings and saved per built-in profile.
Android keyboard input reuses `KeyboardHost`; the existing keyboard/trackpad
and Deck panel modes remain available. Shared PC-key definitions are taken from
PR #1305 at `76fc9b31`; capture/identity handling follows PR #1202 at `42db788f`.
Neither PR is merged wholesale. The superseded touch-only mapping page and
Select-as-Steam UI toggle are removed after migration.

### Boundaries and deferred work

Steam Mobile Touch retains its Steam-owned action sets, layouts and cloud data;
its controls are edited in Steam. It does not pass through DroidDeck's local
remapper. Existing gesture recognition and VR controller handling are retained;
gestures share output ownership, while the VR-specific input path remains owned
by VR. Native remaps are limited to compatible controls within the same report
family. An Android physical key can retain its original behavior or run a local
action; capture does not synthesize an unrelated hardware device.

Named profile libraries, importing/exporting profiles, per-app switching,
reusable layout presets, macros, tap/hold/chord activation and a freeform
multi-display drag editor remain deferred. Key combinations emitted by one
binding are supported; multi-button activation chords are not implemented.
Hardware calibration stays in the existing adapters. No native transport,
Steam haptics path or device driver is replaced.

### Validation and Thor procedure

Before changes, the existing controller/PadMouse/on-screen/second-screen tests
passed. The integrated suite exercises routing, shared holds, key order,
hysteresis, neutral gating, profile migration/persistence, raw report preservation,
existing input paths and the Compose binding/layout editors. See the local
Gradle test report for exact results.

1. Install the local test APK alongside the signed public app; keep runtime data
   and baseline configuration available. Do not replace the official signing key.
2. In a Desktop session, test pointer/scroll, mouse buttons, arrow keys, arbitrary
   key combinations and captured hardware back buttons. Open and close the drawer
   and keyboard with controls held; verify every guest release.
3. Select Game and test Factorio plus Steam UI, first with the default native
   profile, then with individual bindings changed to local or disabled. Repeat
   with Xbox 360 and Steam Deck virtual-controller modes.
4. Exercise keyboard/trackpad, Deck panels and Custom controls on the lower screen.
   Hold the same output from two sources, remove a surface, change profiles while
   holding sticks/buttons and reconnect the controller/display.
5. Enable Input diagnostics, close the editor, generate input under idle and
   rendering load, then reopen and read p50/p95/p99. Recording is bounded to the
   most recent 4096 samples and disabled by default. `InputTiming` measures dispatch
   through existing transport publication, including routing. It does not measure
   full button-to-photon latency. Use the same instrumentation on a baseline build
   before making comparative claims.
6. Record refresh rates, controller/transport mode, build type and low-latency
   flags. Compare raw identity, local key, pointer and native Deck routes. Use
   external high-speed video or aligned guest traces for end-to-end measurement.

A passing host test suite does not establish latency on Thor or physical BLE
compatibility. Those remain explicit release gates before a public PR.
