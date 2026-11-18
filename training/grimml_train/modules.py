"""Per-module training settings, read from config/modules.yaml."""
from __future__ import annotations

import re
from dataclasses import dataclass, field, fields, replace
from pathlib import Path

import yaml

from grimml_train.paths import MODULES_YAML


class ConfigError(ValueError):
    """A problem in config/ (or a command-line value) the user has to fix; shown without a traceback."""


@dataclass(frozen=True)
class ModuleSpec:
    """Everything that decides how one module is trained. Defaults come from the `defaults` block."""

    name: str
    aliases: tuple[str, ...] = ()
    families: tuple[str, ...] = ()
    schema4: bool = False
    train_clients: tuple[str, ...] = ("prestige",)
    match_activity: bool = True
    match_cols: tuple[str, ...] = ("attack_count", "event_count")
    chunk_min: float = 4.0
    chunk_gap_s: float = 7.0
    trim_s: float = 3.0
    min_secs: float = 60.0
    roles: tuple[int, ...] = (1, 2)
    seed: int = 7

    @property
    def slug(self) -> str:
        return slugify(self.name)

    def with_overrides(self, **values) -> ModuleSpec:
        """A copy with the given settings changed; None values are ignored (an option not given on the command line)."""
        return _validated(replace(self, **{k: _coerce(k, v) for k, v in values.items() if v is not None}))


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


_FIELDS = {f.name for f in fields(ModuleSpec)}
_TUPLES = {"aliases", "families", "train_clients", "match_cols", "roles"}


def _coerce(key: str, value):
    if key not in _FIELDS or key == "name":
        raise ConfigError(f"unknown module setting '{key}'")
    if key in _TUPLES:
        items = value.split(",") if isinstance(value, str) else list(value)
        items = [i.strip() if isinstance(i, str) else i for i in items]
        if key == "roles":
            return tuple(int(i) for i in items if str(i) != "")
        if key == "train_clients":
            return tuple(str(i).lower() for i in items if i)
        return tuple(str(i) for i in items if i)
    if key in ("schema4", "match_activity"):
        return bool(value)
    if key == "seed":
        return int(value)
    return float(value)


def _validated(spec: ModuleSpec) -> ModuleSpec:
    if spec.schema4 and not spec.families:
        raise ConfigError(f"{spec.name}: schema4 is on but no feature families are set in modules.yaml")
    if not spec.train_clients:
        raise ConfigError(f"{spec.name}: train_clients is empty")
    return spec


@dataclass
class ModuleRegistry:
    specs: dict[str, ModuleSpec] = field(default_factory=dict)  # by lower-case name and alias

    @classmethod
    def load(cls, path: Path = MODULES_YAML) -> ModuleRegistry:
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        defaults = {k: _coerce(k, v) for k, v in (raw.get("defaults") or {}).items()}
        reg = cls()
        for name, values in (raw.get("modules") or {}).items():
            settings = {**defaults, **{k: _coerce(k, v) for k, v in (values or {}).items()}}
            spec = _validated(ModuleSpec(name=str(name), **settings))
            for key in (spec.name, *spec.aliases):
                if key.lower() in reg.specs:
                    raise ConfigError(f"modules.yaml: '{key}' is defined twice")
                reg.specs[key.lower()] = spec
        return reg

    def get(self, name: str) -> ModuleSpec:
        spec = self.specs.get(name.strip().lower())
        if spec is None:
            raise ConfigError(f"unknown module '{name}'. Known: {', '.join(self.names())}. Add it to config/modules.yaml.")
        return spec

    def names(self) -> list[str]:
        return sorted({s.name for s in self.specs.values()})

    def matches(self, spec: ModuleSpec, module_label: str) -> bool:
        """True if a sessions.csv module value belongs to this module (its name or an alias)."""
        return module_label.strip().lower() in {spec.name.lower(), *(a.lower() for a in spec.aliases)}
