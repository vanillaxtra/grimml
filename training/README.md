# grimml-train

Trains, tests and publishes the GrimML combat models, **one module at a time**.

```bash
cd training
pip install -e ".[dev]"
```

## Everyday use

```bash
grimml-train modules                      # every configured module, its settings and session count
grimml-train sessions --module "Kill Aura"   # its sessions, file counts and whether each is used (and why not)
grimml-train train "Silent Aim"           # held-out test + model  ->  runs/silent_aim/<time>/
grimml-train publish runs/silent_aim/<time> --enable
grimml-train parity                       # refresh the Java/Python parity fixtures, then: mvn clean package
```

`python -m grimml_train ...` works too, without installing.

`publish` replaces **only that module** in `../models/`; every other module stays as it is. The manifest version goes
up by one, and servers pick it up without a restart once `models/` is pushed. Without `--enable`/`--disable` a
module keeps its current on/off state (a new module starts off).

Read `report.txt` in the run folder before publishing:

- **core AUC**: how well the model separates held-out cheat sessions from held-out legit fights.
- **shortcut AUC**: the same using only ping, window length and counts. 0.75 or more means `LEAK RISK`: the labels
  can be told apart by the recording setup, so do not publish.
- **per FIGHT**: the share of each fight's windows that get flagged. The alert line is set above every legit fight
  (+ margin), so check `top legit fights` and `cheat segments above the line`.

## Adding data or a module

| To... | Edit |
|---|---|
| add recordings | copy the `.gmlml` files into `prestige temp test/` (the file names keep the `m<8 hex>` mark id) |
| label a session | add a row to `config/sessions.csv` |
| never train on a session | list it under `exclude` in `config/labels.yaml`, with the reason |
| keep both accounts of one duel together | `same_fight` in `config/labels.yaml` |
| add a module | an entry in `config/modules.yaml` (its schema 4 feature `families`), then `grimml-train train "<Module>"` |
| change a module's settings | `config/modules.yaml`, or one run with a flag (`--schema4`, `--chunk-min 2`, `--train-clients ...`) |

Rules the pipeline enforces:

- Only `CHEAT` and `LEGIT_CONFIRMED` sessions are used; unlabelled windows never count as legit.
- Whole sessions (cheat) and whole time chunks (legit) are held out together, so a fight is never split across train
  and test.
- A module only sees its own schema 4 feature families, and with `schema4` both classes come from schema 4 recordings.
- Cheat and legit are matched on activity (attacks x events per window) so "how busy the fight was" cannot separate
  them.

## Layout

```
config/
  sessions.csv        labelled sessions (mark id, label, client, module, settings, start/end)
  labels.yaml         sessions to leave out, same-fight groups, recorder validity fix date
  modules.yaml        per-module settings: features, train clients, chunking
grimml_train/
  cli.py              the grimml-train command
  modules.py          module settings (modules.yaml)
  catalog.py          sessions and label rules
  dataset.py          decode (cached in .cache/), label, held-out blocks, activity matching
  evaluation.py       LightGBM fit, leave-one-session-out, fight-level alert line
  report.py           run folder: report.txt, result.json, model/
  train.py            train / loco for one module
  publish.py          models/ and manifest.json
  parity.py           detector parity fixtures
  decode/             .gmlml reader (GML1-GML4)
  features/           feature code, ported 1:1 to the detector (Java)
tests/
prestige temp test/   the recordings (not in git)
runs/                 run output (not in git)
```

Decoded windows are cached in `.cache/` per file and per version of the decode/feature code: after the first run only
new or changed recordings are decoded. `--no-cache` decodes everything again.

## Other commands

```bash
grimml-train loco "Kill Aura" --test-client doomsday   # train on the other clients, test on an unseen one
grimml-train train "Kill Aura" --bulk-processed E:/recordings_processed/ingest/windows   # add bulk legit windows
grimml-train publish runs/kill_aura/<time> --dry-run    # show the alert line without writing models/
```

Format details: [docs/format-notes.md](docs/format-notes.md).
