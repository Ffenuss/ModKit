# Automatic puzzle discovery and publisher context

ModKit performs source analysis on the device. Sending each target APK to an
assistant is not part of the product workflow.

Publisher descriptions checked on 2026-10-10:

- https://play.google.com/store/apps/details?id=com.x.aniimos describes Aniimo
  as an open-world creature-catching RPG.
- https://play.google.com/store/apps/details?id=com.cat.hole.puzzle.aos describes
  Drop the Cat as a puzzle game with time limits.

These descriptions supply fallback genre hints for exact package names. User
selection and unambiguous local symbol evidence take precedence. They do not
prove an engine, installed version, binary address, or successful gameplay
modification. No remote lookup or remote recipe download is implemented here.

The generic IL2CPP catalog now searches remaining moves, moves left, move count,
hint count, remaining hints, hints left, remaining time, time left, round time,
and level time. The vocabulary is shared with late disk-index discovery, so
these methods are also examined after the first 30,000 in-memory bindings.
Metadata fields remain discovery signals, not automatically writable objects.

A native recipe still requires an exact scalar return type, unique method body,
verified function bounds, read-only body proof, fitting replacement bytes and
runtime ABI/image/original-byte checks. Calls and state writes are rejected.
Numeric puzzle recipes prefer 999 when it fits and choose a positive fitting
alternative otherwise; this is a proposed value, not verified gameplay behavior.

Regression coverage includes publisher-hint precedence, package lookalikes,
typed puzzle getter recipes, and rejection of a getter containing a call.
The new checks have been authored; CI results must be inspected before this
change is called validated. Actual effects in Aniimo and Drop the Cat remain
unverified. Recovering missing metadata and supporting other runtime formats
are separate remaining capabilities.
