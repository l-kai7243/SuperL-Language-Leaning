#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# Fix the MainActivity.kt file by replacing \" back to "

input_file = r"app/src/main/java/com/example/myapplication111/MainActivity.kt"

# Read the file
with open(input_file, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace \" with "
fixed_content = content.replace(r'\"', '"')

# Write back
with open(input_file, 'w', encoding='utf-8') as f:
    f.write(fixed_content)

print(f"Fixed escaped quotes in {input_file}")
print(f"Replaced all \\\" with \"")
