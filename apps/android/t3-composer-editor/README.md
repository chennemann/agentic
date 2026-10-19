# T3 composer editor for Android

Pinned to upstream T3 Code `3e6b45028ceec5820dacb37dc3852470ebdc9411`, the same
baseline as the terminal and diff viewer. There is no build dependency on the
sibling T3 checkout.

The upstream Android `EditText` implementation owns chip spans, atomic deletion
through hardware and software keyboards, native undo, clipboard interception,
selection, placeholder measurement, and input flags. `T3ContextChip.kt` is copied
unchanged. The Expo container and event dispatchers are replaced with a
`FrameLayout` and Kotlin callbacks. The clipboard helper is extracted from the
upstream Expo module. Original package names and source layout are retained.
Unused Expo property setters are omitted; the Compose adapter owns focus, typography,
selection and layout through the methods it uses.

`upstream.json` records every original source hash. `upstream.patch` records all
changes to vendored sources, including the clipboard extraction. Gradle verifies
unmodified artifact hashes before building. To check or update the local delta:

```bash
python apps/android/t3-composer-editor/check-upstream.py apps/t3code
python apps/android/t3-composer-editor/check-upstream.py apps/t3code --write-patch
```

Reference TypeScript sources retain upstream trigger detection, chip source
serialization, token boundaries, and paste disposition. Their Kotlin counterparts
live in Agentic's `domain/composer`; parity cases cover Unicode, path escaping,
incomplete skills, monetary amounts, cursor insertion, and draft retention.

Agentic's Compose adapter keeps its existing composer layout and colors. It
provides inline file, skill, image, and pasted-text chips, workspace file and skill
autocomplete, previews, and clipboard-image paste. Canonical context links bind
to structured message context without expanding pasted text into the prompt.
File uploads use `attachments.createUploadUrl` and the signed binary POST API;
outgoing file references carry upstream's `pasted-text` source tag. Uploads occur
when sending; failures preserve the draft for retry. Server capabilities and
limits determine whether a paste can become an attachment. Otherwise text stays
editable within the 120,000-character input limit. Ctrl+Shift+V bypasses ordinary
large-paste folding, as upstream does.

Draft metadata stays in the SQLDelight preference cache. Large paste payloads live
in content-addressed private app files, avoiding Android's SQLite cursor-window
limit; deleted payloads are pruned after persistence. Existing plain-text drafts
remain readable. Deleted chips retain their payload in the current editor session
for native undo, while only referenced payloads are persisted and submitted.

This port does not add new terminal-capture or review-comment actions, pull-request
autocomplete, cross-environment attachment import, general document picking, or
the web editor's rich Markdown mode. Existing same-environment clipboard records
can be retained; unavailable attachments are rejected without clearing the draft.
The separate native editor does not add iOS-only behavior.
