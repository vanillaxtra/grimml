# Accessibility

grimml is a server plugin with no GUI. Everything it shows is chat text, an action bar, console output or plain files. This page explains how that works for people who use screen readers, are colour blind, or can't use the in-game client easily, and where it still falls short.

## What works today

- **Everything is a typed command.** Every `/grimml` command works the same from chat, the server console and RCON, so staff can work entirely from a terminal or a panel without joining the game.
- **Colour is never the only signal in numbers.** The mark action bar always prints the real values next to their colours: `p:` ping in ms, `j:` jitter in ms, MSPT in ms, and `f` for frame count.
- **Plain-text output.** Sidecars (`marks.csv`, `grim-labels.csv`, `grim-review.csv`) are plain CSV that opens in any spreadsheet program or screen reader.
- **Stable ids.** Marks get short ids like `m83e5404d` that you can copy and look up later with `/grimml mark get <id>`, so you don't have to read a message before it disappears.
- **Text documentation.** All docs are Markdown in this repo and readable without a browser.

## Colour meanings

The action bar uses green, yellow and red. If you can't tell them apart, use these thresholds with the numbers shown:

| Value | Green | Yellow | Red |
|---|---|---|---|
| Ping (`p:`) | under 80 ms | 80 to 179 ms | 180 ms or more |
| Jitter (`j:`) | under 30 ms | 30 to 69 ms | 70 ms or more |
| MSPT | under 15 ms | 15 to 29 ms | 30 ms or more |
| Frames (`f`) | more than 60 | 21 to 60 | 20 or fewer |

The quality dot `◉` at the start of the bar is red when ping is 180 ms or more or MSPT is 40 ms or more, yellow when ping is 80 ms or more, jitter 30 ms or more or MSPT 15 ms or more, and green otherwise.

Error messages are red, but they always spell out the problem in words (`account not online`, `unknown module ...`).

## Known gaps

- The quality dot `◉` is colour only. Read the numbers next to it instead.
- Red and green are used together, which is the hardest pair for red-green colour blindness. There is no option to change the colours or turn them off yet.
- The action bar refreshes every 4 ticks and fades, so a screen reader may not announce it. `/grimml mark list` and `/grimml mark get <id>` print the label, module and elapsed time as chat text, but not the ping, jitter, MSPT and frame values.
- Messages are English only.

## Feedback

If something in grimml is hard to use with your setup, open an issue using the feature request template and say what you use (screen reader, colour filter, console only, ...). Accessibility problems are treated as bugs.
