# Plan: Deciding Which Files to Code Review

## Goal
Establish a repeatable, data-driven strategy for prioritizing files in a codebase for review — not how to *perform* the review, but how to *select* which files deserve attention.

## Phases

### Phase 1: Gather Metrics (data collection)
- **File size**: lines of code per file → flag files >400 lines
- **Churn**: `git log --oneline --since=6months -- <file>` → files changed most often are bug magnets
- **Cyclomatic complexity**: run detekt or similar → flag methods >10 complexity
- **Cognitive complexity**: detekt rule → flag methods >15
- **Test coverage**: files with low or no test coverage → higher risk
- **Dependency count**: files that import many other modules → tight coupling
- **TODO/FIXME density**: grep for `TODO`, `FIXME`, `HACK`, `workaround`

### Phase 2: Rank by Risk Score
Combine metrics into a weighted score:
- High churn + high complexity = highest priority
- Large file + low coverage = next priority
- High dependency count = structural risk
- New files in critical paths = review regardless of size

### Phase 3: Apply Context Filters
- **Critical paths**: files involved in data fetching, rendering, persistence
- **Recent changes**: files in the current diff that touch risky areas
- **New dependencies**: files that introduce new library/API usage
- **Cross-module boundaries**: interfaces between `:shared`, `:app`, `:desktop`

### Phase 4: Produce Review Queue
Output a prioritized list of files with rationale, e.g.:
```
Priority 1: TemperatureGraphRenderer.kt (1200 lines, complexity 45, churn top 5%)
Priority 2: WeatherRepository.kt (600 lines, 0 tests, 8 dependencies)
Priority 3: DailyViewHandler.kt (450 lines, high churn)
```

## Tools to Use
- `detekt` — Kotlin static analysis (complexity, smells, size)
- `git log --follow -- <file> | wc -l` — churn per file
- `wc -l` — file size
- `grep -rn "TODO\|FIXME\|HACK"` — debt markers
- `git diff --stat` — size of current change

## Decision Matrix

| File Profile | Review Priority | Action |
|---|---|---|
| Large + high churn + complex | **Critical** | Full line-by-line review |
| Large + low churn | **High** | Spot-check, refactor candidate |
| Small + high churn | **Medium** | Focus on changed lines |
| Small + low churn + new | **Low** | Quick scan |
| Generated / auto-formatted | **Skip** | Exclude from review |

## Output
Write the prioritized list to a file (e.g., `review-queue-<date>.md`) and share with the team before the review session.
