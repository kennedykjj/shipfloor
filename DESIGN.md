---
name: Shipfloor Dashboard
description: "A calm instrument for one owner of a software factory: a belt that shows where every card is, a work area that shows one thing at a time, and a wide view for one card."
colors:
  ground: "#0a0a0a"
  panel: "#14171b"
  panel-hi: "#1c2026"
  raise: "#242830"
  accent-ink: "#0a0a0a"
  paper: "#181c21"
  edge: "#2a2f39"
  edge-hi: "#3d444f"
  text: "#f2f1ee"
  soft: "#d6d8da"
  muted: "#a3abb6"
  faint: "#8f98a3"
  sand: "#c7b9a6"
  glow1a: "#3b4a5e"
  glow1b: "#1c232d"
  glow2: "#2a2622"
  track: "#5a6472"
  track-on: "#c9ced4"
  refine: "#8fa8c7"
  validate: "#c896ec"
  ok: "#9fcbaa"
  warn: "#dcb872"
  bad: "#e58f80"
typography:
  display:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "34px"
    fontWeight: 600
    lineHeight: 1.1
  section:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.2
  headline:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.3
  lead:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
  base:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.45
  body:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.45
  meta:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.4
  caption:
    fontFamily: '"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.4
  log:
    fontFamily: '"IBM Plex Mono", ui-monospace, "Cascadia Mono", "SF Mono", Menlo, Consolas, monospace'
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.55
rounded:
  bar: "2px"
  sm: "4px"
  md: "5px"
  lg: "6px"
  ctrl: "8px"
  tile: "12px"
  decision: "14px"
  panel: "16px"
  pill: "999px"
  dial: "50%"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
components:
  panel:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.text}"
    rounded: "{rounded.panel}"
    padding: "16px 24px"
  tile:
    textColor: "{colors.text}"
    rounded: "{rounded.tile}"
    padding: "12px 16px"
  decision:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.text}"
    rounded: "{rounded.decision}"
    padding: "16px"
  tag:
    textColor: "{colors.muted}"
    rounded: "{rounded.sm}"
    padding: "1px 8px"
  gate:
    textColor: "{colors.ok}"
    rounded: "{rounded.sm}"
    padding: "2px 8px"
  button:
    backgroundColor: "{colors.panel-hi}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
  button-approve:
    backgroundColor: "{colors.sand}"
    textColor: "{colors.accent-ink}"
    rounded: "{rounded.pill}"
    padding: "8px 16px"
  drawer:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.text}"
    width: "min(660px, 52vw)"
---

> Shipfloor: herdado do DESIGN.md do Shipfloor V0 como base. Descreve o dashboard web da V0; o app em Compose (fase 6) reaproveita tokens, tipografia e comportamento, e este documento será adaptado nessa fase. Mudança aqui é proposta antes de virar código.

# Design System: Shipfloor Dashboard

## Overview

**Creative North Star: "The Floor Plan"**

The dashboard is the floor plan of a software factory, read by one owner doing quick checks: look at the belt to see where every card stands, answer what waits for you, glance at what is running and what it cost, close. It has three views, chosen in the header: **Factory** (the board), **Feed** (a timeline of runs) and **Settings** (the dispatcher, the sessions, the projects, the themes and the prices). A card is shown in the work area under the belt; a click on its token on the belt picks it.

The surface is a quiet page with translucent panels and hairline borders. Its type is IBM Plex Sans for words and IBM Plex Mono for logs and times; hierarchy comes from size and weight, never from case or tracking, and every number keeps tabular figures. One color is reserved: the **accent** (`sand`) marks only what waits for the owner, and the outcome trio (ok, warn, bad) marks results. Everything else is ink and hairlines. Color is chosen by the owner: fourteen themes change the same tokens and nothing else.

**Key Characteristics:**
- One belt: every column a segment, every card a token, a rail under it that moves only while the dispatcher is alive.
- Panels are translucent washes of the theme's ink over the ground, with a hairline border and a 16px radius; the decision panel has 14px.
- Sentence case everywhere; weight and size carry the hierarchy.
- Hairlines instead of shadows at rest; depth appears for the wide card view and the maximized panel.
- Three continuous motions: the cooldown dial on a running card, the pulse of a running dot and the belt's rail. The belt is the third continuous motion, allowed because it carries state, and it stops under reduced motion like the other two.

## Colors

Every color is a token of the base block (`:root, [data-theme="charcoal"]`), and every other theme sets the same tokens. The 23 front matter entries are exactly that block. Two more are derived from them in `:root`: `--gold` and `--azure` are `text`, `--azure-ink` is `ground` and `--azure-soft` is `text` at 12% (the page still uses these names for the owner's action).

### Neutral
- **Ground** (`colors.ground`): the page, under a grain and the glows (`glow1a`, `glow1b`, `glow2`), and the well of the text area and of code.
- **Panel** (`colors.panel`), **Raised Panel** (`colors.panel-hi`) and **Raise** (`colors.raise`): the drawer, a maximized panel and hover rows; ordinary panels on the page are only a 2% wash of the ink over the ground. `paper` is the surface of the decision panel.
- **Hairline** (`colors.edge`) and **Lit Edge** (`colors.edge-hi`): dividers and borders; the lit edge is the border of the drawer and the buttons. The header rule, the legend, the stats separator and the passes use `--hair`, the theme's ink at 11%.
- **Text steps** (`colors.text`, `colors.soft`, `colors.muted`, `colors.faint`): primary, body, secondary and tertiary text, in that order. Each theme holds every step to 4.5:1 on its ground and panels.
- **Track** (`colors.track`, `colors.track-on`): the belt's rail ticks, and the ticks under a stage that works.

### Accent
- **Sand** (`colors.sand`): the accent. It marks only what waits for the owner: the waiting dot, the card number in the work area's head, the `waits for you` words, the waiting count, the Approve that answers it and the focus ring. Text on an accent fill is `accent-ink`. It is never one of the outcome colors, and in the page's themes it is yellow, orange, cyan, blue or teal as the theme decides.
- **Refine** (`colors.refine`) and **Validate** (`colors.validate`): the hues of those two stages in the Settings pool bar, where Develop is `text` and Deploy is `ok`. Neither is the accent.

### Outcome trio
- **ok, warn, bad** (`colors.ok`, `colors.warn`, `colors.bad`): results, gates, flags and alerts. A blocked card is `bad` in its number and its dot; infrastructure trouble is `warn`. Never decorative, and never the accent.

### Named Rules
**The Accent Rule.** The accent appears only on what waits for the owner, and on the action that answers it. Outcomes use their own trio; money, titles and outlines use `text` and `edge-hi`.

**The Estimate Rule.** Every cost is an estimate and says so, and every cost is set in `text` with `est`: "est." next to the figure, or "Spent est." in the header; a missing price shows `$ ?` with an explanation, never a zero.

## Typography

**Sans:** "IBM Plex Sans", "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif
**Mono:** "IBM Plex Mono", ui-monospace, "Cascadia Mono", "SF Mono", Menlo, Consolas, monospace

**Character:** a tool's voice. The IBM Plex faces are embedded in the file as `@font-face` data (Plex Sans 400, 500, 600 and 400 italic; Plex Mono 400 and 500, each in latin and latin-ext), so the page needs no network, no CDN and no build; the OFL license of the faces ships beside them. If a face fails to load, the system stack named after it takes over. Monospace carries logs, code, the Settings hours field and the time in the Feed. Nothing is set under 12px, and running text is capped at 64ch.

### Hierarchy
- **Display** (sans, 600, 34px): the heading of a panel (`h1.g`), such as Lifecycle or History.
- **Section** (sans, 600, 24px): the heading of the maximized line, of a Settings section and the card's title in the work area (500).
- **Headline** (sans, 600, 17px, 1.3): the card view's title, a segment name on the belt, a Settings group title and the heading of the decision.
- **Lead** (sans, 16px, 1.5): the prose of a brief and the longer descriptions.
- **Base** (sans, 15px, 1.45): the page's base text, with tabular figures everywhere; a row of Calm is 15px.
- **Body** (sans, 14px): the brief's text, the alerts, descriptions, buttons and table cells.
- **Meta (13px)**: meta lines, tab labels, notices, tags, gates, clocks and the tool calls of a run.
- **Caption** (sans, 400, 12px, sentence case): the keys under stats, the header's views and stats, the inbox's section titles and the legend.
- **Log** (mono, 12px, 1.55, pre-wrap): the log pane and the Feed's time.

The type ramp is the eight steps 34, 24, 17, 16, 15, 14, 13 and 12px. Nothing is set under 12px, and running text is capped at 64ch. The values outside the ramp are listed under Known deviations.

### Named Rules
**The Figures Rule.** Any number keeps tabular figures (`font-variant-numeric: tabular-nums` on the body). Prose stays proportional.

**The Case Rule.** There is no uppercase and no wide tracking. The exceptions, if one is ever needed, are listed here; today there is none.

## Layout

The page is a grid of three rows (the header, the alerts when something is wrong, then the view) that does not scroll as a whole above 1180px; each panel scrolls inside itself. The page padding is `clamp(16px, 4vw, 56px)`. Spacing is a 4/8 grid.

The Factory view opens on what waits. The belt and "History" are open at every load, each with a minimize button beside its maximize button, inside the panel. The pool, the runs and the spend of the day are in the header, once; nothing above the panels repeats them. Under the belt stands **the work area**, one panel, full width, that shows one thing at a time (see The Work Area); "History" follows it and scrolls with the page. Minimized, a panel is its heading and the button that opens it again, where it sits. What the owner minimized is kept across the 5-second refreshes and never stored.

The wide card view (Sessions, Log, Gates, Review, Live) is a dialog over the page: 90% of the screen, over a dimmed, blurred board. Its content uses a 24px side gutter. Below 860px the header wraps, stats drop their borders and the work area's columns stack; below 600px the per-stage slots hide and the brand takes its own line. The card view is a size container: under 540px the tooltip stats go two by two.

## Elevation & Depth

Tonal first. A panel is a 2% wash and a 9% hairline; the drawer, a maximized panel and the select's list are the page's only raised surfaces, and they use the `--shadow` token or the drawer's own cast.

### Shadow Vocabulary
- **Raise** (`--shadow`: `0 10px 30px rgba(0,0,0,.45)` in dark themes; each light theme sets a softer one): a maximized panel, the select's list.
- **Drawer cast** (`-14px 0 32px -8px rgba(0,0,0,.45)`): the open drawer only; removed when closed.
- **Dial ring** (`0 0 0 2px panel, 0 0 0 3px edge`): the cooldown dial.
- **Hot dot** (`0 0 0 4px` ink at 25%): the dot that is hovered or open.

### Named Rules
**The Flat-At-Rest Rule.** Panels carry no shadow until something opens over them.

## Shapes

Radii come from one scale: 2px on bars, meters and the tick of a progress step; 4px on tags, gates, keys and code; 5px on buttons, the text area, the hours field and the scrollbar thumb; 6px on small cards (the maximize button, a chip in the lanes, the block note); 8px on selects, option cards, the theme swatch and the stepper; 12px on an alert and the save bar; 14px on the decision panel; 16px on a panel, a maximized one included; a full circle for dots, the dial and the switch knob. Marks whose shape carries state (the Feed's diamond and dash) are square, with no radius. Borders are 1px hairlines.

## Components

### Header
The brand at the left, then the views as three captions (the pressed one in `text`, the others `muted`), then the stats at the right, led by **Docs**: a caption link with the external glyph to Shipfloor's README (`#learn-more`, the table of the docs pages), in a new tab, written in the static markup so it is there offline and before the first read; nothing is fetched until it is clicked. With more than one project the figures are the machine's, and the titles of Runs today and Spent est. say so. The project switch is a caption, a value and a small chevron; where the browser allows (`appearance: base-select`) its list is drawn by the page with the raised panel and a mark on the checked option. Under 1180px the header wraps. After the stats comes **Commands**, a `.btn ghost` with `aria-keyshortcuts="Control+K Meta+K"` and a `kbd` hint (`Ctrl K`, `⌘ K` on macOS) that is hidden under `pointer: coarse`; it opens the palette.

### The Tab
The tab carries the state outside the page. Title: `Shipfloor · <project>`, with `(N) ` in front while N decisions wait in the project on screen and ` · <title.stopped>` at the end while the dispatcher is stopped; it is written only when its text changes, and a replay does not touch it. Favicon: the brand tile on `ground`, recolored by state and nothing else: neutral draws the route in `muted` and the card in `text` (the accent keeps one meaning); waiting fills the tile with `sand` and draws in `ground`; stopped does the same with `bad` and outranks waiting. It is redrawn only when the state or the theme changes.

### The Address
The hash holds a view, never state or the token. Selecting a card pushes `#card/<n>`; Back and Forward restore the selection. `#@HHMM` is the replay's time. A hash for a card that does not exist, or a malformed one, is replaced silently (`replaceState`) by the default view, with no notice. Moving through the palette pushes nothing; only the action it runs does.

### The Palette
A `role="dialog"` (`aria-modal`) box over a scrim, top-centred at 12vh, `min(560px, 100vw - 32px)` wide, `panel-hi`, 1px `edge-hi` border, 14px radius, `shadow`, no animation of its own. One field (15px, `role="combobox"`, `aria-controls` the listbox, `aria-activedescendant` the active row) above up to nine rows: actions first (label alone), then cards (`#n` in mono, `sand` when it waits for the owner, then the title on one line with an ellipsis, then the column in 12px `muted`). The active row has a `raise` fill and a 2px `text` left edge. No match is one `muted` row that cannot be chosen. Ctrl K / Cmd K toggles it from anywhere, even in a field, before the single-letter keys (inert while it is open); arrows and Home/End move (wrapping), Enter runs the row with the same handler as its button, Esc, the scrim or Ctrl K close it and the focus returns to where it was (or to what the action focused). Under 600px the box is full width with a 16px top gap, rows are 44px high and the list scrolls inside (`max-height: 60vh`).

### Alerts
A line that exists only while something is wrong: a bad-tinted bordered strip with one item per cause, each with a dot in its outcome color. Offline and Not updating (bad), Factory paused (bad), a project the dispatcher skips (bad), Board N min old (warn). It is a polite live region.

### The Belt
See Layout. The belt replaces the old dotted line in the same panel (title, minimize and maximize buttons kept): one row of 11 segments in board order, each with a name (stages 13px 600 `text`, gates 13px 600 accent, queues 13px 500 `muted`) and a 12px `faint` meta (`3` or `empty`, `1 of 2`, `2 for you`, `2 today`). Every card is a token under its segment's meta, oldest first.

- **Tokens** are 30px buttons, at least 54px wide, 8px radius, the number in Plex Mono 13px 500 tabular. A running token is filled `text` with its step bar; a queued one is hollow with a `muted` border; one that waits for the owner is in the accent; infrastructure failed is `warn`, blocked is `bad`, done today is `ok`. Each free slot of a stage is a dashed `free` token, `aria-hidden`.
- **Rail:** an 8px rail under all segments with `track` ticks every 22px, sliding right one tick per 1.8s while the dispatcher's last cycle is fresh. Stopped, it pauses where it is and turns `bad`. It is decorative; its state is said by the status sentence.
- **Status sentence** (header, `role="status"`, polite): `Running · 3 decisions wait for you · 2 of 6 sessions · $1.36 today`, or `The belt stopped. Last cycle 14 min ago.` with a ghost Restart button.
- **Travel:** a card that changes column travels from its old place to the new one (950ms) and a gate it arrives at rings once.
- **Keyboard:** one tab stop (roving tabindex); arrows, Home and End move between tokens, Enter, Space or a click selects the card in the work area (the selected token keeps its look; a second click or Esc clears it). Under 1020px the belt scrolls inside its panel.
- **Reduced motion:** under `prefers-reduced-motion: reduce` the rail is still, tokens jump, no ring and no fade. The state stays in the sentence, the rail color and the dot.

Maximized, the belt's panel shows two lanes of chips, and the owner moves their cards there (the moves are the server's table, M1 to M7). A chip shows how long it has waited and, in Ready for Prod, `2 of 5 in line`; in Inbox, Ready for Refinement and Ready for Dev it opens with its position (12px `faint`, tabular, 2ch, with `Position 2 of 5` for screen readers). A chip the owner can move has `cursor: grab`; a locked one (a run in progress, blocked by infrastructure) carries a 12px lock and its reason in words, `cursor: not-allowed`. Picking up a card (drag, `M` on the focused chip, or the ghost `Move` button on a touch screen, `hover: none`) gives it a 2px outline in `edge-hi`; each column that takes it gets a 1px dashed outline in `edge-hi` and a ghost button with what dropping there means ("Drop to approve the spec", "Press 1 to …", "Tap to …"), solid under the pointer; the others dim to .45, a factory column adding `the factory moves`. In its own ordered column a held card goes before the chip it is dropped on: a 2px bar in the gap shows where; the arrows move it one place, Enter places it, Esc puts it back; on touch, `Up` / `Down` / `Place here` buttons appear under it. A move that needs a note opens the note box, a raised surface under the target column. Every move and reorder leaves at once and the page shows it at once; the `.itoast` at the bottom of the panel confirms it (with Comment, for a move that went without a note), and a refused one is put back and says why in the panel's note line. There is no Undo. One status line under the heading (`role="status"`) says what is held and the keys. "Only what I can move", a switch in the heading row, dims every other chip; it is off at every load. Column and lane names stay in English in every language.

### The Work Area
One panel under the belt (`role="region"`, labelled "The work area"), one thing at a time, so the owner decides without switching screens. A token selected on the belt (click, Enter or Space) picks the mode: a card that waits for the owner is **Decide**, a card that runs is **Run**, any other card is **Card**. With nothing selected the area shows Decide on the oldest waiting card, or **Calm** when nothing waits. Clicking the selected token again, or Esc with nothing else open, clears the selection. The queue and the selection are the page's memory only; nothing about a card is stored outside the board.

All modes share the head: the number as a mono chip in the accent, the column, and the title at 24px 500 (40ch), which takes the focus when a new card slides in. The accent marks only what waits for the owner and the action that answers it; the cost is always in `text`, with `est.`, never in the accent.

- **Decide** (Spec Review, Staged). The head adds `waiting 3 h 50 min` and, at the right, `1 of 3 decisions`, a dash per decision (done `ok`, current accent, the rest `edge-hi`) and `next: #129`. On the left, the decision brief (`briefHTML`), or the sandboxed review page when there is no brief. On the right, **Your decision**, a 14px sticky panel on `panel-hi`: Approve (solid `text`, its destination as a second 12px line), Request changes (with its destination), Skip for now (ghost), the lines for a stale board and a skipped project, on a Spec Review card the weight select with its hint, and one row of links (Full review, Sessions, Log, Gates, GitHub) of which the first four open the wide card view on that tab. **Request changes** unfolds inside the panel: one accent-tinted ghost button per brief flag that has a suggestion, "Use the review's N changes" when the review has marks, the note (focused) and Send, disabled while the note is empty; Enter sends and Shift+Enter makes a new line; Esc folds it back and keeps the typed note for the card. **Approve with doubt**: with unreviewed items or changes asked, Approve turns soft and the first click or `A` arms it in place ("Approve with 2 not reviewed", 4s, focus on it, its hint linked by `aria-describedby`); the second sends. When the review page was never seen in this session, Approve sends nothing and the hint reads "Nothing was approved: look at the review first." with Full review beside it. **After a decision** Your decision becomes an `.itoast` line ("Approving #120…") while it is on its way; it leaves on the click, with no hold and no Undo. When the 5 seconds end the next waiting card slides in, focus goes to its title, and a polite status says "#129 waits for you, 2 of 3"; a send that fails brings the card back as current with "#12 was not approved: <reason>". **Skip for now** (`N`) sends the card to the end of this page's queue and shows the next; with one card waiting it does nothing and says "Only #120 waits."
- **Run** (a card that runs). On the left, the stage's steps as a list (done `muted`, the current one `text` 600), the last five tool calls in mono (time, tool, target; the newest fades in) and the field to message the run (Enter sends, Shift+Enter starts a line). On the right, `Step 4 of 7`, the clock, the cost in `text` with `est.`, and the Log and GitHub links. A run that ends while shown becomes Card in place, with no slide.
- **Card** (any other card). The head, one sentence of state in words ("Waiting for the pool · 4 of 4 busy", "2 of 5 in line", "Done today · PR #66"), the cost from Inbox to now with `est.`, then the Sessions, Log, Gates and GitHub links. A blocked card shows its block note above the links: the decision line, the failed gate, Unblock and retry, and the full report folded. A card not read yet shows its head and title with the links disabled and no skeleton.
- **Calm** ("Nothing waits for you", 34px 600). Three columns, each row a button that selects its card: Next decisions (the runs nearest a gate), Running now, and Shipped today (PR, the time it shipped and the cost with `est.`). An empty column is one `faint` line.

**Motion.** A swap slides: the card that goes fades out to the left in 260ms, the one that comes slides in from the right in 400ms. Under `prefers-reduced-motion: reduce` the swap is instant, with no slide, and the newest tool call does not fade in.

**Small screens.** Under 860px the area is one column: Your decision moves under the title, before the brief, and is not sticky; its buttons are full width; the head's right part wraps under the number; the Calm columns stack; tool targets wrap. Every target is at least 24x24px.

**The wide card view.** The links open the card in a dialog at 90% of the screen over a dimmed, blurred board (the card's tabs; Esc or Close returns to the area, and focus goes to its title). Its head is a raised-to-panel gradient closed by a hairline: the number and title, a bordered Close button at the top right, a meta line (column, "waits for you" in the accent or a dial and clock when running, the GitHub link, and once the card has a spec a plain `weight: <weight>` tag, never colored), and four stats as `dt`/`dd` pairs: cost Inbox to now (the total, 17px, in `text` with "est."), sessions, first to last run, last report. Under it, underlined tabs (Live, Sessions, Log, Gates, Review) with mono counts, a real tablist (roving tabindex, arrow keys, Home and End) over one tabpanel. Live is the session as it happens, with a field to say a word to the run that is working. Sessions are a right-aligned numeric table with sticky heads (start, stage and model, input, output, cache read plus written with the split on hover, time, result, cost; a session that ran one model up after a failed gate carries a plain `stepped up` tag), dashed sub-rows for subagents and a total row. Gates map outcomes explicitly: ok, done, pass and merged are ok; fail, error and blocked are bad; anything else is muted; a stage with no gates and no notes says "No gates for this stage". The Log tab counts the card's log files; the log scrolls under its own bar and opens at its end. The Review tab hosts the review page in a sandboxed iframe. The marks the owner makes there (or in the work area's own copy of the page) are kept by the dashboard server, per card and review page, so they survive reloads, and a rebuilt page starts clean; the iframe stays sandboxed and hands its marks to the dashboard, which saves them quietly.

### Cooldown Dial (signature)
A 13px circle filled by a conic sweep in `text` over the lit edge, ringed by the dial ring, turning once every 6s through a registered `--sweep` angle. It appears on a running card. Under `prefers-reduced-motion: reduce` it stops on a static quarter (90deg), and the pulse of the dot, the breathing of the step bar and the card transitions stop as well.

### History
The factory's history over a period the owner picks, today first. The heading is `History` and the period in `muted` (`· today`, `· yesterday`, `· last 7 days`, a date such as `Thu 8 Oct`, or a range such as `3 – 9 Oct`; dates come from `Intl.DateTimeFormat` in the page's language, the year only when it is not this one), a polite live region.

Under it, the **period control**: a group of the Feed's filter buttons (`Today`, `7 days`, `30 days`, `Custom`, `aria-pressed`; a preset is pressed only while its current period shows, none in a stepped period or an opened day, `Custom` for a range), then two maximize-sized buttons with the fold chevron turned left and right (Previous period, Next period; at the present Next is `aria-disabled`, faint, `not-allowed`, still focusable, titled "This is the current period"), then `Back to now` as a `.lnk` away from the present. `Custom` opens a row of two date fields (`From`, `To`, `max` today, prefilled with the period shown); an end before the start, a future date or more than 92 days marks the field `aria-invalid` and says so in one 12px `bad` line, and the panel keeps the last valid period. The period is kept in the browser (`shipfloor.history`): Today, 7 days and 30 days stay relative to now, a stepped or custom period keeps its dates. The control is never rewritten by the refreshes; focus stays on the control just used.

Then the **totals** of the period, a `dl` of four two by two: caption label, the figure at Headline size (17px), and its change from the previous period of the same length in words, at Caption (12px, `muted`), never an arrow or an outcome color: "3 more than the 7 days before", "the same as the day before", "nothing in the 7 days before". Cards done; Runs (`12 clean · 2 failed`, the failed part `bad` and left out at zero); Spent est. (in `text`, `$ ?` with its explanation when a price is missing, and then "not comparable: a price is missing"); Inbox to Done, median (a dash and "no card reached Done" when none did). Maximized, the totals are one strip of four columns divided by hairlines; two by two under 860px.

Then the **charts**, runs that ended (clean and hatched failed, with the key) and the estimated spend: by hour for one day (an image, a title per bar), by day for longer periods. By day every slot is a real button over its whole width (a 6% wash on hover and focus) named with its values ("Mon 6 Oct: 3 clean, 1 failed. Open the day"); it opens that day by hour and moves the focus to `Back to now`. Each by-day chart is one tab stop (roving tabindex; Left, Right, Home and End). Day labels are `Mon 6` up to 7 slots, else the first, the last and every seventh as `6 Oct`; a slot is 24px or wider when maximized. Chart labels are 12px `faint`. Then how long each stage usually takes, over the period.

A period with no run shows only the heading, the control and `No run ended in this period.` with a link to the period before; a period not read yet says `Reading the history…`, and on a refresh the old content stays until the new one arrives. Maximized, the panel adds the runs that ended in the period (newest first, 50 at a time: `50 of 213 runs` and a ghost `Show more runs`, which takes the focus to the first new row) and the cards of the period by estimated cost from Inbox to now, the costliest first, a row opening the card. The summary in the header stays about today whatever the period.

### Feed
One timeline, newest first, with a heading where the day changes. Above it, the filters (Runs, which is the view the Feed opens on and holds the run rows only, Waits for you, Failures, Running, Dispatcher), each with its count, and "Newest first". A run is **one row** placed at the time it ended (or started, while it runs): the time in mono, a mark whose shape carries the state (dot ok, diamond failed, ring running, dash for a dispatcher line or a run whose end was never logged), the project when there is more than one, the stage in bold, the card's number and title, and "waits for you" in the accent when the card sits in an owner column after that run. On the right, the duration (or "running 6m", or "· exit 4") and the run's estimated cost. A second line appears only when something is known of what the run left behind, each piece from its own source: why a run failed first, "gates failed: build" (the run's report) or, when no gate is on record, "ended with exit 4 · no failed gate recorded" (the exit code; no log line is shown, as it can read like a success), then "PR #64" (the report), "now in Staged" on the card's newest run (the board), "Step 4 of 7: Engineer" while it runs (its lock). Nothing is inferred from the stage. The whole row opens the card maximized on that run's log; the title is the row's button and keeps focus across refreshes. There is no button in a row. A dispatcher or stacks line repeated in a row is one row with "37× since 00:09".

### Settings
The dispatcher is the top line of the view: a dot (ok, warn when idle, bad when off), its status in words, the working hours field (mono) and the switch that stops it. Below it, a nav of sections at the left (Sessions, Projects, Themes, Costs) and the section at the right in groups: a group is a panel with a headline title, rows of a label, a description and a control (a stepper, a switch, a select, a value). A switch shows its state as a word, On or Off, left of its track (`role="switch"` with `aria-checked`), so the state never rests on color or on the knob's place alone. A pool bar shows how the stages share the sessions, one hue per stage with a key under it (Refine `refine`, Develop `text`, Validate `validate`, Deploy `ok`; the share no stage uses is the hairline), and warns when they ask for more than the pool allows. While anything is unsaved, a sticky save bar with a hairline border counts the changes and offers Discard and Save; a changed control turns its value to `text`. Under 1180px the nav becomes a row and rows stack. In a stacked row a stepper keeps its width at the row's left edge.

### Language
The page is written in English and shown in the machine's language (`language` in `~/.shipfloor/config.yml`). In another language, every piece of text is rewritten as it enters the document, from the catalog the server sends (`scripts/lang/dashboard.<code>.json`): a whole piece by `exact`, a phrase inside one by `rx`. So a new string needs no code, only an entry; one with no entry shows in English. What the factory did not write is never rewritten: card titles, logs, and what the agents said (the `RAW` selector). Board columns keep their GitHub names in the data and show translated.

### Themes
The owner picks how the dashboard looks in Settings, under Themes. Four come first (Auto, Graphite, Paper, High contrast); the rest sit under "More themes", a fold that opens closed every time Settings opens and, when the theme in use is behind it, says `In use: <name>`. A theme only changes color tokens: the base block in `scripts/dashboard.html` is Charcoal, and each `[data-theme="<id>"]` block sets the same tokens (ground, panels, edges, the four text steps, the accent `sand`, ok/warn/bad, the two glows, the lifecycle track, `refine`, `validate`). Five tokens came with the v4 system and every theme sets them: `raise` (the raised surface), `accent-ink` (text on an accent fill), `paper` (the surface of the decision panel), `accent-soft` (the accent as a tint) and `accent-line` (the accent as a line). A light theme also sets `--ov`, `--well`, `--scrim`, `--shadow` and `color-scheme: light`. Almost no color is written out in a rule: tints come from `color-mix` over a token (`--ov`, the ink of the theme, is what makes a panel's wash and hairline), the one exception being the white of a plain review page.

Ten dark themes (Graphite, Floor tape, Charcoal, Dock, Machine green, Blueprint, Foundry, Night shift, Terminal, High contrast) and four light ones (Paper, Kraft, Steel, Cobalt). Auto, the default, follows the system: Graphite in dark, Paper in light. The choice lives in the browser (`localStorage`, `shipfloor.theme`) and is applied before the first paint. In every theme the accent is not one of the outcome colors, so where the accent is yellow the warning is orange. The older blue light theme is now Cobalt.

A swatch is a button that carries its theme's `data-theme`, so it is drawn with the same tokens as the page it stands for. The embedded review page gets the theme's colors as a message. To add a theme: one block in the CSS and one entry in `THEMES`; `scripts/test/theme.test.sh` refuses a theme with a missing token or with text under 4.5:1 on its ground and panels. The tokens of the pool bar end the list: `refine`, `validate`.

### Buttons
- **Button:** raised panel, lit-edge border, 5px radius, 8px 12px; hover turns the border to `text`. **Ghost** drops the fill and sits in `muted` at 13px.
- **Approve:** accent fill, `accent-ink` text, 600 weight; hover mixes the fill toward the ground. Soft (asks twice): tinted fill. In Your decision it carries its destination as a second 12px line.
- **Disabled:** not-allowed cursor, faint text; progress cursor while a move is in flight.
- **Target:** every focusable control has a box of at least 24x24px; a text-only control grows its box, not its ink.
- **Focus:** a 2px `sand` outline, 2px offset, on every focusable element.

### Embedded Review Pages
Agent-written review pages in the iframe are re-themed by `EMBED_CSS` in `scripts/dashboard.py`: their own header hidden, section headings at 17px 600, their own reviewed count hidden (the decision shows it), transparent buttons with hairline borders. The dashboard sends the page its theme as a message (`page` = ground, `sheet` = panel, `surface` = raised panel, `ink` = text, `muted`, `rule` = hairline), and the embed applies it and, when the owner changes theme, again. While the page loads, the iframe element paints nothing of it and holds the drawer's panel color (`var(--panel)`); it is shown, with one 150ms ease-out opacity fade (instant under reduced motion), once the page has acknowledged the theme (`themed`), and white is its background only under a plain page, one that does not take the embed theme and never announces itself (`ready`), shown when its load ends. The same embed adds a small script that announces `ready`, acknowledges each theme with `themed`, and posts the page's marks (`review-progress`: done, total, the changes as text) to the dashboard, which accepts them only from that iframe.

### Keyboard and Live Updates
Esc folds Request changes (the note stays), then closes the maximized panel or the wide card view, then clears the selection on the belt, in that order (not while typing); in the maximized lifecycle it first closes the note box, then lets go of a held card. In the work area's Decide mode, with the focus outside a text field, a select or an iframe and no wide view open, A approves, R opens Request changes and N skips to the next waiting card (Enter in the note sends it); the keys do nothing in Run, Card and Calm. The footer reads `A approve · R request changes · N skip` and shows only where the primary pointer is fine. Arrows, Home and End move between the card view's tabs, and between the days of a History chart by day. Opening a card moves focus to its title, closing returns it to the work area's title. The overview is read every 2s, the Feed every 5s, the card shown in the work area every 15s, an open card view every 5s, the open History every 10s and Settings every 10s, but a region is rewritten only when its markup changed, so focus, scroll, the open select and typed text survive; clocks tick on their own. The first read shows "Reading the factory…", and a failed read raises a "Not updating" alert with the error.

## Direction: Canon (decided 2026-10-17)

The owner saw three complete directions (`docs/design/direcoes-visuais.html`: Canon, Departures board, Line map) and chose **Canon**: the finish of Vercel and Raycast, applied with rigor. It replaces the colors, the type and the stage hues of the Floor Plan for the shipfloor desktop app; the structure of "Target: the Board" below still holds. The test of the screen: read at a glance from a window left open all day, never a website in a frame, never childish, cold or crowded.

- **Neutral scale, one accent.** Ground `#131417` / panel `#1b1d21` / raise `#24272c` / hairline `#2e3238` / lit hairline `#434850` / text `#ececef` (dark: a graphite, not black, so a card reads as a surface over the ground); `#fafafa` / `#fff` / `#f4f4f4` / `#e6e6e6` / `#cfcfcf` / `#171717` (light). Follows the system theme; both are designed, neither is an inversion of the other.
- **The accent is amber** (`#f5a623`) and marks only what waits for the owner: the alert glyph, the dot on a waiting column, the border of a waiting card, "N esperam a sua decisão" and the action that answers it. As text it is `#ffb84d` on dark and `#9a5b00` on light (`sand`); as a fill it is always `#f5a623` with `#1a1100` text. Outcomes: ok `#3ecf8e` / `#14804a`, bad `#ff6b6b` / `#cf222e`.
- **Stages are told apart by shape and word, never by hue.** One glyph per card state: dashed ring (queued), spinning arc (an agent works on it), amber alert (waits for the owner), green check (delivered), red cross (blocked). The stage hues (`refine`, `develop`, `validate`) are gone and the three tokens now alias neutral text colors.
- **Type: Geist and Geist Mono** (SIL OFL, embedded in `app/src/main/resources/fonts`). Sans for everything, mono only for ids. Scale: 22 (counts), 20 (the factory's state), 15 (brand), 14 (card titles, body), 13 (controls, lane names), 12 (meta). Figures keep tabular figures.
- **Shape and depth.** Radius 8 for cards and the strip, 6 for buttons and fields. Borders are 1 dp hairlines; no shadow and no fill on a stage. A lane has no box: only a hairline on its right edge.
- **Brand.** The Shipfloor mark of the V0 (`docs/img/icon.svg`): floor tape in `#f2c230` that takes a card from the Inbox to Done, ending in an off-white card, on a `#0c0c0b` tile. It is the app icon, the tray icon (with an amber badge when the owner is wanted) and the mark in the app bar, always on its own dark tile whatever the theme; the word is set in Geist SemiBold. The amber accent of the interface stays `#f5a623`.
- **Shell.** App bar (52 dp): brand, Fábrica / Feed do dia / Configurações, the search that opens the palette. Under it the header: what the factory says (20 sp, with its glyph) and how many wait, then sessions, the day's ceiling (a 4 dp meter, always "est.") and the forecast. Then the layout controls, the column strip (count and short name per column, a dot on the waiting ones, a line under those in view) and the lanes.
- **Cards** show the glyph, the 8-character id, the title (up to two lines, medium) and "há 4 h 43 min · US$ 0,21 est."; the cost is left out when it is unknown, never shown as a zero or as "custo desconhecido".
- **Continuous motion**: the spinner on a working card and the factory's glyph. Nothing else moves at rest; both stop under `SHIPFLOOR_REDUCED_MOTION`.
- **Checking a change of look.** `./gradlew :app:test --tests shipfloor.app.ScreenshotsTest` renders the Factory screen (1440 and 1000 px, with and without the card panel, both themes) to `app/build/screens/*.png`; look at them before and after.

## Target: the Board (shipfloor app)

Decided by the owner on 2026-10-16 after the interactive reference `docs/design/interface-alvo.html` (open it in a browser: it works). Where this section and the rest of the document disagree, this section wins for the shipfloor desktop app (Compose). The reference is a visual and behavioral target, not code to copy; sample numbers in it (medians, costs per run, history) are illustrative.

**What changes against the belt and the work area above**

- **The belt becomes the Board.** Each column of the board is a belt segment: header with name and count, a rail under it, and the cards of that column below. Ten columns, in board order. The board scrolls sideways and vertically; column headers stay at the top, column footers (estimated cost of the column) stay at the bottom, and every column stretches to the full scroll height.
- **Column strip** above the board: one cell per column with its count and short name, a dot on the ones that wait for the owner; a click scrolls to its lane.
- **Two lanes of five columns** (decided 2026-10-17 from `docs/design/raias-refinadas.html`, replaces the resizable columns, the fit toggle and the collapsed strips): "Refinar" (Inbox, Ready for Refinement, Refinement, Spec Review, Ready for Dev) and "Desenvolver e entregar" (Coding, Validation, Staged, Ready for Prod, Done). All ten columns are in view with no horizontal scroll; an empty column is still a column (a dashed "vazia" box, title always horizontal). Each lane has a bar (name, what it covers, count, a note: "segue na raia de baixo" / "N esperam a sua decisão"); a small chevron on each divider shows the flow; the column that waits for the owner has an amber line on top and a faint amber wash. Under a column header, the median time (stages) or the mean wait (gates). While a card is held, only the columns it may go to light up ("soltar aqui") and the others dim. A card that waits carries its own Aprovar/Liberar and Pedir mudança. Done shows its four latest cards and "Ver os N mais antigos".
- **Two densities**: Cards (title, stage progress, time, cost, gates, blockers) and Chips (the state glyph and the title on one line; never the id, which means nothing to read).
- **Two modals replace the side panel** (decided 2026-10-17 from `docs/design/inbox-cheio.html`). A column keeps its size: it shows its first four cards (nine chips) and ends in a dashed "+N · ver todos" card. A click on it opens the **list modal** (86% of the window wide, 88% tall): every card of the column in a grid, a filter by title or number, an order (oldest first, A to Z), Esc to close. A click on any card, in the list or on the board, opens the **detail modal** over it (70.8% wide, 72% tall, centered): the number and title and the path through the ten columns on top; the spec and the criteria on the left; on the right the facts (time in the stage, estimated cost, runs), the gates, the latest events and the cost by run; at the foot the owner's way forward (approve or ask for a change, release a staged card, send an Inbox card to refinement). Esc closes the top modal first. The old tabs and the resizable side panel are gone.
- **The pulse strip** between the header and the board (only in the Factory view): the factory in words (the state of #73 and the phrase of #74, in Plex Mono), the pool of sessions as small squares with the active runs, the day's ceiling as a meter with the estimated spend, and the forecast of when the queue empties. Under 1100 px it becomes a 2 by 2 grid, under 880 px one column.
- **"Where the time goes"**: one stacked bar with the median minutes per stage of the cards delivered today; the stretches that wait for the owner are hatched in the accent, and the sentence above says how much of the lead time they are.
- **Command palette** (Ctrl K): cards first those that wait for the owner, then the views.

**Color by stage (replaces "do not color-code columns")**

The stage hues of the pool bar now also mark the board: `refine` for Refinement, a new `develop` hue for Coding (teal, `#7fcdbf` dark, `#17806f` light), `validate` for Validation, `ok` for Ready for Prod and Done. Gates (Spec Review, Staged) keep the accent. Queues and Inbox stay neutral. A stage hue appears only on the 3 px top edge of its column, the card number, the step bars, the dial and the minimap tick; never as a fill of a panel and never on text of running copy. Each theme defines `develop` next to `refine` and `validate`. The accent still marks only what waits for the owner and the action that answers it.

**Cards on the board**

A card is a 12 px radius button on a 70% panel wash with a hairline border: number (mono, in the stage hue), layer chip, then at the right the state (the dial and "há 6 min" when it runs; the pulsing accent dot and "espera você · 3 h 50 min" when it waits; "bloqueada por #n" in `bad` when blocked). Below: the title (14 px), the step bar of the pipeline (when running), and a meta line with gate chips, PR and CI, lead time, estimated cost (always `est.`) and the `manual` and `adiada` chips. A card that waits for the owner has two small buttons, Decide (accent) and View spec.

**Continuous motion (replaces "a fourth continuous animation")**

Four, all stopped under reduced motion: the cooldown dial on a running card, the pulse of a waiting dot, the rail of a column while that column has a running card, and the cursor of the speech line in the pulse strip.


## Known deviations

Values that fewer than three rules use and that have no named role. They stay as they are on the page until a rule widens the ramp or the scale.

- `15.5px` — `.isec .plain`

## Do's and Don'ts

### Do:
- **Do** take every color from a token, and give every new token a value in every theme.
- **Do** keep type in sentence case, and let weight and size carry the hierarchy.
- **Do** keep the accent for what waits for the owner, and the action that answers it.
- **Do** keep numbers in tabular figures and label every cost as an estimate, in `text`.
- **Do** keep surfaces flat at rest and let only what opens (drawer, maximized panel) cast a shadow.
- **Do** keep the dial, the dot's pulse and the belt as the only continuous motion (the belt carries state), and give any new motion a reduced-motion fallback.
- **Do** keep factory stage and column names verbatim.

### Don't:
- **Don't** set uppercase or wide-tracked type, or anything under 12px.
- **Don't** fill a column or a card with a stage hue; the hue is only the top edge, the number, the step bars, the dial and the minimap tick (see Target: the Board) too.
- **Don't** use the accent as decoration, for money or for titles, or the outcome colors for anything but results.
- **Don't** add emoji or icon fonts; text labels carry actions (the few inline SVG marks are the maximize and external-link glyphs, and the lock beside the reason a card in the maximized lifecycle cannot move).
- **Don't** link a font or an asset from the network, or add a build step; the dashboard is one self-contained file.
- **Don't** add resting shadows or a fifth continuous animation (the four are listed in Target: the Board).
