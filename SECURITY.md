# Security Policy

## Supported versions

Only the latest commit on `main` gets security fixes. There are no release builds yet.

## Reporting a vulnerability

**Do not open a public issue.** Report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/vanillaxtra/grimml/security/advisories/new).

Include:

- what is affected (`recorder`, `detector`, or `training`)
- server software and version, plugin version or commit
- steps to reproduce, and what an attacker gains

You should get a reply within 7 days. Once a fix is on `main`, the advisory is published.

## In scope

- leaking raw player UUIDs, names or IPs to disk, logs or models (the recorder must only store salted SHA-256 ids)
- packets from a client that crash the server, block the main or netty thread, or fill the disk past the disk cap
- command permission bypasses (`grimml.admin`)
- the detector loading or running untrusted model files in an unsafe way

## Out of scope

- false positives or missed cheats in the models (open a normal issue)
- a weak or default `anonymize-salt` you did not change
- problems in GrimAC, PacketEvents, Paper or other plugins (report those upstream)
