# MangoCodex

A lightweight, native Android text editor with regex-based syntax highlighting.

## Features

### Editing
- Native `EditText`-based code surface (not Compose `BasicTextField`) for fast,
  incremental text layout and platform-native cursor/selection handling
- Monospace font tuned for code (13sp, 20sp line height)
- Auto-indent — pressing Enter carries over the current line's leading
  whitespace, toggle via `⋮ → Auto-indent`
- Line-wrap toggle — wrap long lines or scroll horizontally, via `⋮ → Wrap lines`
- Line-number gutter that tracks wrapped (visual) lines back to their logical
  line number, toggle via `⋮ → Line numbers`
- Unsaved-changes protection — opening a new/different file while there are
  unsaved edits prompts a discard-or-cancel confirmation

### Find & replace
- In-editor find/replace bar (`⋮ → Find/Replace`), docked above the keyboard
- Live match counter (`current/total`) as you navigate
- **Next** jumps to the nearest match at or after the cursor, wrapping around
  to the top of the document when it runs off the end
- Every match is outlined inline in the text (drawn per visual line, so a
  wrapped match renders as separate boxes); the current match also gets the
  native text-selection highlight
- **Replace** swaps just the current match and advances to the next one;
  **All** rewrites every match in the document in one pass
- Optional regex mode (`.*` toggle) — the find field becomes a regex pattern,
  invalid patterns surface a clear inline "Error" instead of crashing, and the
  replace field supports `$0`/`$1`/`$2`… capture-group references (`\$` to
  escape a literal `$`)
- Literal (non-regex) mode is a plain, case-sensitive substring search
- Matches stay in sync with ongoing edits — typing elsewhere in the document
  shifts or drops match positions instead of forcing a re-search
- Auto-scrolls to reveal the current match, accounting for both the on-screen
  keyboard and the find bar's own height so it's never hidden behind them

### Syntax highlighting
- Line-by-line regex highlighting — fast, stateless, per-line tokenizer
- Windowed/virtualized highlighting — only the visible region (plus a margin)
  is tokenized and spanned, with a per-line token cache that's pruned outside
  the retained window, so large files stay smooth while scrolling
- Per-language pattern sections — a single `patterns.csv` can define a default
  rule set plus additional sections keyed by filename suffix (e.g. `[.py]`,
  `[.htaccess]`, `[hosts]`); a file's own section's rules take priority and the
  default rules fill in the rest, so multiple languages can share one file
  without their rules clobbering each other
- Adjacent same-color tokens are merged before rendering to cut down on the
  number of paint runs
- Ships with a default pattern set covering C-like syntax (keywords, types,
  strings, chars, numbers, comments, preprocessor directives, operators,
  punctuation) plus basic XML/HTML tags, attributes, and entities

### Pattern customization
- Fully configurable pattern set via a plain CSV file
- Edit patterns directly inside the app (`⋮ → Edit patterns`)
- Hot reload patterns without restarting (`⋮ → Reload patterns`)
- Malformed pattern rows (bad regex/color) are skipped rather than crashing
  the app

### File handling
- New file, open file, save, save as
- Opens files handed off from other apps/file managers via `ACTION_VIEW`
  intents (any `text/*` MIME type)
- Resolves proper display names for `content://` URIs instead of showing
  opaque provider IDs
- Dirty-state indicator (`•`) in the title bar for unsaved changes

### UI
- Dark, VS Code–inspired theme (background/gutter/text colors)
- Overflow menu (`⋮`) for all file, find/replace, pattern, and view-option
  actions

## Pattern format

Patterns live in a CSV file (`patterns.csv`) with three columns:

```
name,color,pattern
comment_line,#6A9955,^\s*//.*
string,#CE9178,"[^"]*"
keyword,#569CD6,\b(if|else|for|return)\b
```

- **name** — identifier, used for readability
- **color** — hex color (`#RRGGBB`)
- **pattern** — Java/Kotlin regex applied per line

Rules are applied in order. First match on any character range wins (no overlaps).

### Language sections

Rows can optionally be split into sections with a `[.ext1,.ext2]`-style header:

```
name,color,pattern
comment_line,#6A9955,^\s*//.*

[.py]
comment_line,#6A9955,^\s*#.*
keyword,#569CD6,\b(def|class|import|from|return)\b

[.html,.htm]
xml_open_tag,#569CD6,</?[\w:]+
```

- Rows before the first `[...]` header belong to the default section, applied
  to every file — so an existing single-section pattern file still works
  unchanged.
- A section header is a comma-separated list of literal strings matched as a
  case-insensitive **suffix** against the whole filename — not just a file
  extension, so `[.py]`, `[.htaccess]`, `[.log.txt]`, or `[hosts]` are all
  valid.
- `[*]` explicitly targets the default section (useful for adding more
  default rules further down the file, after a language section).
- A file's effective rules are: its matching section's rules first, then the
  default rules — so a language's rules get first claim on a piece of text,
  and the defaults just fill in what's left.

## Customizing patterns

Open the pattern file from within the app, edit, save. Changes take effect immediately via reload.
To back up or share your pattern set, use Save as. To load a new one, open it and save it over the internal file.

## Building

```
gradle assembleDebug
```

Requires JDK 17 and Android SDK. CI via GitHub Actions on every push and release.
