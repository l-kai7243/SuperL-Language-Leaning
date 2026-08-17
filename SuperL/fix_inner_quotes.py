#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fix Chinese quotation marks inside string literals.
Replace " and " with regular ASCII quotes or escape them.
"""

import re
import sys

def fix_inner_chinese_quotes(content):
    """
    Replace Chinese quotation marks with regular ASCII quotes.
    This handles the pattern where Chinese quotes appear inside Kotlin string literals.
    """

    # Replace Chinese left and right double quotation marks with regular quotes
    fixed = content.replace('"', '"').replace('"', '"')

    return fixed

def main():
    file_path = "app/src/main/java/com/example/myapplication111/MainActivity.kt"

    try:
        # Read the file
        print(f"Reading {file_path}...")
        with open(file_path, 'r', encoding='utf-8') as f:
            original_content = f.read()

        print(f"Original file size: {len(original_content)} characters")

        # Count Chinese quotes
        left_quote_count = original_content.count('"')
        right_quote_count = original_content.count('"')
        print(f"Found {left_quote_count} left quotes")
        print(f"Found {right_quote_count} right quotes")

        # Apply fixes
        print("Replacing Chinese quotes with ASCII quotes...")
        fixed_content = fix_inner_chinese_quotes(original_content)

        # Verify changes
        remaining_left = fixed_content.count('"')
        remaining_right = fixed_content.count('"')
        print(f"Remaining left quotes: {remaining_left}")
        print(f"Remaining right quotes: {remaining_right}")

        # Write back
        print(f"Writing fixed content back to {file_path}...")
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(fixed_content)

        print("Success! File fixed.")
        print(f"Replaced {left_quote_count} left quotes and {right_quote_count} right quotes")

        return 0

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
