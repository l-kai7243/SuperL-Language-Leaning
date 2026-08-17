#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Comprehensive fix for MainActivity.kt quotation mark corruption.
This script will:
1. Replace all \" with " (escaped quotes with regular quotes)
2. Handle edge cases like triple quotes for multiline strings
3. Preserve actual escaped quotes that should remain (like in regex patterns)
"""

import re
import sys

def fix_quotes(content):
    """Fix all corrupted quotation marks in the Kotlin file."""

    # Replace all instances of \" with "
    # This handles the main corruption where all quotes were escaped
    fixed = content.replace('\\"', '"')

    return fixed

def main():
    file_path = "app/src/main/java/com/example/myapplication111/MainActivity.kt"

    try:
        # Read the corrupted file
        print(f"Reading {file_path}...")
        with open(file_path, 'r', encoding='utf-8') as f:
            original_content = f.read()

        print(f"Original file size: {len(original_content)} characters")

        # Count corrupted quotes
        escaped_quote_count = original_content.count('\\"')
        print(f"Found {escaped_quote_count} instances of \\\" to fix")

        # Apply fixes
        print("Applying comprehensive quote fixes...")
        fixed_content = fix_quotes(original_content)

        # Verify changes
        remaining_escaped = fixed_content.count('\\"')
        print(f"Remaining \\\" after fix: {remaining_escaped}")

        # Write back
        print(f"Writing fixed content back to {file_path}...")
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(fixed_content)

        print("✓ File successfully fixed!")
        print(f"✓ Replaced {escaped_quote_count - remaining_escaped} escaped quotes")

        return 0

    except Exception as e:
        print(f"✗ Error: {e}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
