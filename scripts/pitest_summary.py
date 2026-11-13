#!/usr/bin/env python3
"""Summarize a Pitest run of :shared (./gradlew :shared:pitest).

Reads shared/build/reports/pitest/mutations.xml and prints, per package and per class:
killed / survived / no-coverage counts, mutation score (killed / all) and test strength
(killed / covered). Kotlin accessor and data-class members are counted separately as "accessor"
mutants: a surviving getter mutant says the value is never read by a :shared test, which is
weak evidence compared with a surviving branch in a rule.

Usage:
  scripts/pitest_summary.py                 # package table + 25 weakest classes
  scripts/pitest_summary.py --class Foo     # surviving mutants of classes whose name contains Foo
  scripts/pitest_summary.py --top 50
"""
import argparse
import collections
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORT = Path(__file__).resolve().parent.parent / "shared/build/reports/pitest/mutations.xml"
ACCESSOR = re.compile(r"^(get[A-Z]|is[A-Z]|set[A-Z]|component\d+$|copy(\$default)?$|hashCode$|equals$|toString$)")
KILLED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR"}


def load(path):
    rows = []
    for m in ET.parse(path).getroot():
        cls = m.findtext("mutatedClass")
        method = m.findtext("mutatedMethod")
        rows.append(
            {
                "cls": cls,
                "outer": cls.split("$")[0],
                "pkg": cls.rsplit(".", 1)[0],
                "method": method,
                "line": int(m.findtext("lineNumber")),
                "status": m.get("status"),
                "mutator": m.findtext("mutator").rsplit(".", 1)[-1].replace("Mutator", ""),
                "desc": m.findtext("description") or "",
                "accessor": bool(ACCESSOR.match(method)),
            }
        )
    return rows


def tally(rows):
    c = collections.Counter()
    for r in rows:
        if r["accessor"]:
            c["accessor"] += 1
            continue
        c["all"] += 1
        if r["status"] in KILLED:
            c["killed"] += 1
        elif r["status"] == "SURVIVED":
            c["survived"] += 1
        elif r["status"] == "NO_COVERAGE":
            c["nocov"] += 1
    return c


def line(name, c):
    covered = c["killed"] + c["survived"]
    score = 100 * c["killed"] / c["all"] if c["all"] else 0
    strength = 100 * c["killed"] / covered if covered else 0
    return f"{name:<58} {c['all']:>6} {c['killed']:>6} {c['survived']:>6} {c['nocov']:>6} {score:>5.0f}% {strength:>5.0f}% {c['accessor']:>6}"


HEADER = f"{'':<58} {'mutants':>6} {'killed':>6} {'surviv':>6} {'no-cov':>6} {'score':>6} {'strng':>6} {'access':>6}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--class", dest="cls")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--report", default=str(REPORT))
    args = ap.parse_args()
    if not Path(args.report).exists():
        sys.exit(f"No report at {args.report}. Run ./gradlew :shared:pitest first.")
    rows = load(args.report)

    if args.cls:
        hits = [r for r in rows if args.cls in r["cls"] and r["status"] in ("SURVIVED", "NO_COVERAGE")]
        for r in sorted(hits, key=lambda r: (r["cls"], r["line"])):
            tag = "accessor " if r["accessor"] else ""
            print(f"{r['cls'].rsplit('.', 1)[-1]}:{r['line']} {r['method']} {r['status']} {tag}{r['mutator']}: {r['desc'][:90]}")
        print(f"\n{len(hits)} surviving or uncovered mutants")
        return

    print(HEADER)
    print(line("TOTAL", tally(rows)))
    print()
    by_pkg = collections.defaultdict(list)
    for r in rows:
        by_pkg[r["pkg"]].append(r)
    for pkg in sorted(by_pkg, key=lambda p: -tally(by_pkg[p])["all"]):
        print(line(pkg.replace("com.weatherwidget.", ""), tally(by_pkg[pkg])))

    print(f"\nClasses with the most surviving + uncovered rule mutants (top {args.top})")
    print(HEADER)
    by_cls = collections.defaultdict(list)
    for r in rows:
        by_cls[r["outer"]].append(r)
    ranked = sorted(by_cls, key=lambda k: -(tally(by_cls[k])["survived"] + tally(by_cls[k])["nocov"]))
    for cls in ranked[: args.top]:
        print(line(cls.replace("com.weatherwidget.", ""), tally(by_cls[cls])))


if __name__ == "__main__":
    main()
