# T3 Code Android review diff

Pinned from T3 Code revision `3e6b45028ceec5820dacb37dc3852470ebdc9411`, directory
`apps/mobile/modules/t3-review-diff/android`. Original filenames, package names,
and formatting are retained; text line endings use LF.

`ReviewDiffCanvasDrawing.kt` is unchanged. The Canvas
view retains upstream row rendering, sticky file headers, touch hit testing,
vertical/horizontal scrolling and flings, line wrapping, viewed-file checkboxes,
line numbers, syntax token rendering, and Unicode text layout.

The complete local delta is in `upstream.patch`:

- `T3ReviewDiffView` extends Android `FrameLayout` and emits Kotlin callbacks
  instead of Expo events. Expo module registration is omitted; Compose embeds
  the view using `AndroidView`.
- Cleanup is idempotent, invalidates queued row/token generations, clears callbacks,
  and releases scrolling/velocity resources. Updates after disposal are ignored.
  Posted worker results cannot restore a disposed view's content.
- Code layout uses the equivalent `LineBreaker.BREAK_STRATEGY_SIMPLE` constant
  to satisfy the SDK 37 lint annotations on `StaticLayout.Builder`.

The application owns navigation, RPC loading, unified-patch parsing and the JSON
row/token adapter. Original TypeScript row/model contracts are retained under
`reference/`. Android uses its existing code lexers rather than importing the
React/Shiki/Pierre runtime. Standard Git unified patches retain hunk numbers,
quoted UTF-8 paths, renames, additions, deletions, binary notices and empty files.
Unknown/combined or malformed patches remain visible as raw text. Inline review
comment editing and word-level diff computation are not part of this port.

The page reads server-owned completed-turn ranges through `orchestration.getTurnDiff`
and `orchestration.getFullThreadDiff`. It opens at the latest ready turn and offers
older completed turns and all turns. This is the replacement for Agentic's former
latest-turn filename sheet; working-tree/branch review sources are not added here.
Collapse and viewed markers are local presentation state. Agentic's Compose adapter
supplies theme, syntax colors, and interface/code font scaling through existing
upstream view properties.

## Updating

`upstream.json` records every original path and source hash. `upstream.sha256`
is checked before Android builds. To check against a T3 checkout containing the
pinned commit, from the Agentic repository root:

```bash
python apps/android/t3-review-diff/check-upstream.py apps/t3code
```

For a new baseline, review upstream review diff commits,
update the revision and source hashes in `upstream.json`, replace unchanged files,
and merge the view using `upstream.patch`. After reviewing local adaptations:

```bash
python apps/android/t3-review-diff/check-upstream.py apps/t3code --write-patch
python apps/android/t3-review-diff/check-upstream.py apps/t3code
```

Run patch-parser, ViewModel, range-selection and WebSocket contract tests, plus
`ReviewDiffScreenTest` and navigation instrumentation on an explicitly selected
Android emulator. Then run the Android quality gate. The module and application's
license assets retain T3 Tools' MIT license.
