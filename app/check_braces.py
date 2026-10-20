import sys

with open("app/src/main/java/com/example/ui/EquipmentControlPanel.kt") as f:
    text = f.read()

i = 0
n = len(text)
stack = []
line_no = 1

while i < n:
    c = text[i]
    if c == '\n':
        line_no += 1
        i += 1
        continue
    # Single line comment
    if text[i:i+2] == '//':
        while i < n and text[i] != '\n':
            i += 1
        continue
    # Block comment
    if text[i:i+2] == '/*':
        i += 2
        while i < n and text[i:i+2] != '*/':
            if text[i] == '\n': line_no += 1
            i += 1
        i += 2
        continue
    # Triple quote string
    if text[i:i+3] == '"""':
        i += 3
        while i < n and text[i:i+3] != '"""':
            if text[i] == '\n': line_no += 1
            i += 1
        i += 3
        continue
    # Double quote string
    if c == '"':
        i += 1
        while i < n and text[i] != '"':
            if text[i] == '\\':
                i += 2
            else:
                if text[i] == '\n': line_no += 1
                i += 1
        i += 1
        continue
    # Char literal
    if c == "'":
        i += 1
        while i < n and text[i] != "'":
            if text[i] == '\\':
                i += 2
            else:
                i += 1
        i += 1
        continue
    
    if c == '{':
        stack.append((line_no, text[max(0, i-25):i+25].replace('\n', ' ')))
    elif c == '}':
        if stack:
            top = stack.pop()
            # print when EquipmentControlPanel closes or line 5760 closes
            if top[0] == 682:
                print(f"EquipmentControlPanel (line 682) CLOSED at line {line_no}")
        else:
            print(f"Mismatched closing brace at line {line_no}")
    i += 1

print("Unclosed blocks count at end of file:", len(stack))
for s in stack:
    print(f"  Line {s[0]}: {s[1]}")
