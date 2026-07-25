#!/usr/bin/env python3
"""
Kotlin Code Complexity & Size Analyzer for Code Reviews.
Calculates cyclomatic complexity, function length, and file length across Kotlin source files.
"""

import os
import re
import sys

# Thresholds
MAX_FILE_LINES = 500
MAX_FUNCTION_LINES = 80
MAX_CYCLOMATIC_COMPLEXITY = 10

# Regex patterns for decision points in Kotlin
FUN_PATTERN = re.compile(r'^\s*(?:override\s+|private\s+|protected\s+|public\s+|internal\s+|inline\s+|suspend\s+)*fun\s+([a-zA-Z0-9_`<>\?]+)\s*\(', re.MULTILINE)
DECISION_PATTERN = re.compile(r'\b(if|when|for|while|catch|&&|\|\||\?\.|\?:)\b')

def analyze_file(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
            lines = f.readlines()
    except Exception:
        return None

    total_lines = len(lines)
    functions = []
    current_fun = None
    current_fun_name = ""
    current_fun_start = 0
    brace_depth = 0
    fun_brace_start_depth = 0
    has_seen_opening_brace = False
    complexity = 1

    for line_idx, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue

        open_braces = line.count('{')
        close_braces = line.count('}')

        match = FUN_PATTERN.search(line)
        if match and current_fun is None:
            current_fun_name = match.group(1)
            current_fun_start = line_idx
            current_fun = []
            complexity = 1
            fun_brace_start_depth = brace_depth
            has_seen_opening_brace = ('{' in line)

        if current_fun is not None:
            current_fun.append(line)
            decisions = len(DECISION_PATTERN.findall(line))
            complexity += decisions

            if '{' in line:
                has_seen_opening_brace = True

            brace_depth += (open_braces - close_braces)

            # Check if function brace block closed
            if has_seen_opening_brace and brace_depth <= fun_brace_start_depth:
                fun_length = line_idx - current_fun_start + 1
                functions.append({
                    'name': current_fun_name,
                    'start_line': current_fun_start,
                    'length': fun_length,
                    'complexity': complexity
                })
                current_fun = None
                has_seen_opening_brace = False

    return {
        'filepath': filepath,
        'total_lines': total_lines,
        'functions': functions
    }

def main():
    dirs_to_scan = sys.argv[1:] if len(sys.argv) > 1 else ['app/src/main', 'shared/src/main', 'desktop/src/main']
    
    large_files = []
    complex_functions = []
    long_functions = []
    total_files = 0
    total_lines = 0

    for scan_dir in dirs_to_scan:
        if not os.path.exists(scan_dir):
            continue
        for root, _, files in os.walk(scan_dir):
            for file in files:
                if file.endswith('.kt'):
                    total_files += 1
                    path = os.path.join(root, file)
                    result = analyze_file(path)
                    if not result:
                        continue
                    
                    total_lines += result['total_lines']
                    
                    if result['total_lines'] > MAX_FILE_LINES:
                        large_files.append((path, result['total_lines']))

                    for fn in result['functions']:
                        if fn['complexity'] > MAX_CYCLOMATIC_COMPLEXITY:
                            complex_functions.append((path, fn['name'], fn['start_line'], fn['complexity'], fn['length']))
                        elif fn['length'] > MAX_FUNCTION_LINES:
                            long_functions.append((path, fn['name'], fn['start_line'], fn['length'], fn['complexity']))

    # Print Report
    print("=" * 80)
    print("  KOTLIN CODE COMPLEXITY & SIZE AUDIT REPORT")
    print("=" * 80)
    print(f" Total Files Analyzed : {total_files}")
    print(f" Total Lines of Code  : {total_lines}")
    print("-" * 80)

    print(f"\n📂 LARGEST FILES (> {MAX_FILE_LINES} lines):")
    if large_files:
        large_files.sort(key=lambda x: x[1], reverse=True)
        for path, loc in large_files[:15]:
            print(f"  - {loc:4d} lines : {path}")
    else:
        print("  None! All files are within size limits.")

    print(f"\n⚠️  HIGH COMPLEXITY FUNCTIONS (Cyclomatic Complexity > {MAX_CYCLOMATIC_COMPLEXITY}):")
    if complex_functions:
        complex_functions.sort(key=lambda x: x[3], reverse=True)
        for path, name, start, comp, length in complex_functions[:15]:
            print(f"  - Complexity {comp:2d} (L{start:4d}, {length:3d} lines) : {name} in {path}")
    else:
        print("  None! All functions are within complexity limits.")

    print(f"\n📏 LONG FUNCTIONS (> {MAX_FUNCTION_LINES} lines):")
    if long_functions:
        long_functions.sort(key=lambda x: x[3], reverse=True)
        for path, name, start, length, comp in long_functions[:15]:
            print(f"  - {length:4d} lines (Complexity {comp:2d}, L{start:4d}) : {name} in {path}")
    else:
        print("  None! All functions are within length limits.")

    print("\n" + "=" * 80)

if __name__ == '__main__':
    main()
