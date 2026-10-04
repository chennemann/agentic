# Chat view comparison

Reference: the Android-capable [official T3 Code mobile client](https://github.com/pingdotgg/t3code/tree/main/apps/mobile), mirrored in `apps/t3code/apps/mobile`,
at T3 commit `526df0278be551dc2df2164d36c105b105cb7103`.
The comparison also uses the shared provider/orchestration contracts. T3 source
is a reference only; Android has no build dependency on that checkout.

| Area | Reference behavior | Android changes |
| --- | --- | --- |
| Tool lifecycles | One evolving row per call, retaining command/output | Merge identified updates across intervening messages, scoped to turn; preserve row identity and prior output |
| Tool status | Running, completed, failed, stopped | Interpret nested status/output and exit codes; show active and failed calls; do not invent success when a turn ends |
| Background work | Task lifecycle events remain inspectable | Include task starts and merge progress/completion by task ID |
| Timestamps | User creation time, assistant update time, local clock | Populate message timestamps, honor Android time format, compare parsed instants for ordering |
| Reasoning | Expandable thinking content | Dedicated reasoning author and expandable thought rows |
| Completed work | First and final assistant messages stay visible; intermediate work folds | Expandable timed turn summary, interrupted label, failure count, preserved history |
| User input | Single/multiple choices, exact option values, permitted custom text | Use `multiSelect`/`allowCustomAnswer`, preserve opaque values, require answers, serialize multi-select as arrays even for one choice |
| Request placement | Requests stay reachable at the composer | Collapsible, bounded request panel above the bottom edge |
| Request recovery | Failed responses can be retried | Retain answers and clear submission lock on transport/provider failure |
| Async questions | Dismiss without messaging the agent | `thread.user-input.dismiss`, gated by `responseMode: message` |
| Answer history | Answered questions remain available | Human-readable question/answer detail outside completed-work folding |
| Approvals | Server option labels/warnings; default choices if options absent | Read `options`, preserve labels/warnings, use the official default decision set |
| Mermaid | Requested addition to Android markdown | Bundled offline Mermaid 12.1.0, fitted previews, separate zoomable viewer with Fit control, theme-aware diagrams, source/copy controls, parse-error fallback |

Existing Android behavior retained includes streaming text, selectable markdown,
code copying, image attachments, proposed-plan continuation, changed-file summaries,
older-history loading, connection recovery, and follow-latest/turn-boundary scrolling.

## Verification

Unit coverage includes cross-message tool completion, failure preservation, settled
and live turn presentation, timestamp offsets/precision, exact option values,
single-versus-multiple answer encoding, custom answers, submission retry, dismissal
command mapping, incomplete Mermaid fences, and HTML/script source isolation.

Android instrumentation tests cover the anchored request panel and actual WebView
SVG/error rendering and source updates. The request-panel and three Mermaid tests passed on the
connected A024 running Android 16. Device execution exposed an initial-document
HTTP 403 in WebView: the offline interceptor now explicitly serves the generated
HTML as well as the bundled script. Instrumentation uses isolated activities that
can show synthetic test content over the lock screen and keep the screen awake.
The generated renderer document was additionally exercised in the collaborative
browser with flowchart, sequence, class, state, ER, Gantt, and pie diagrams, plus
malformed source containing script delimiters.

The clipping/gesture follow-up passes seven renderer instrumentation tests on the
Pixel 6 Android 16 emulator. These include the original chat example, a tall
diagram, swiping the conversation over an inline preview, and expanded-view zoom
buttons, native pinch/pan, and Fit reset. Previews fit both dimensions; gestures
belong to a separate viewer so they do not compete with conversation scrolling.

This comparison is not a certification of complete mobile-client parity. In
particular, question-specific file attachments and the official client's richer
workspace file/image navigation and agent-batch presentation still need a separate
implementation and device acceptance pass. Full production parity should not be
claimed from the unit/build checks alone.
