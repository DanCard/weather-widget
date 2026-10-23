#!/usr/bin/env python3
"""Diff the daily parity harness outputs: Android vs desktop, field by field.

Run the two halves first:
  ./gradlew :desktop:test --tests '*DesktopDailyParityTest' \
            :app:testDebugUnitTest --tests '*AndroidDailyParityTest'
then:  python3 scripts/daily_parity_diff.py [--summary]

Each output line is `date field=value` (shared DailyParity.render). Every differing line is a
divergence between the two daily-column builders (plans/261023-shared-daily-column-builder.md).
"""
import collections
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "shared" / "build" / "daily-parity"


def load(path):
    out = {}
    for line in path.read_text().splitlines():
        key, _, value = line.partition("=")
        out[key] = value
    return out


def main():
    summary = "--summary" in sys.argv
    android, desktop = ROOT / "android", ROOT / "desktop"
    if not android.is_dir() or not desktop.is_dir():
        sys.exit(f"missing outputs under {ROOT}; run both parity tests first")
    by_field = collections.Counter()
    total = 0
    for a_file in sorted(android.glob("*.txt")):
        d_file = desktop / a_file.name
        if not d_file.exists():
            print(f"{a_file.stem}: no desktop output")
            continue
        a, d = load(a_file), load(d_file)
        diffs = [(k, a.get(k, "<absent>"), d.get(k, "<absent>")) for k in sorted(set(a) | set(d)) if a.get(k) != d.get(k)]
        total += len(diffs)
        if not summary:
            print(f"== {a_file.stem}: {len(diffs)} differing fields")
            for key, av, dv in diffs:
                print(f"   {key:32s} android={av:24s} desktop={dv}")
        for key, _, _ in diffs:
            by_field[key.split(" ", 1)[1]] += 1
    print(f"\n{total} differences; by field:")
    for field, n in by_field.most_common():
        print(f"   {field:14s} {n}")
    sys.exit(1 if total else 0)


if __name__ == "__main__":
    main()
