"""check_layout.py UI.xml PACKAGE
Fails (exit 1) when two visible texts of the app overlap, or a text sticks out of the screen."""
import re, sys, xml.etree.ElementTree as ET

def rect(n):
    m = re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', n.get('bounds', ''))
    return tuple(map(int, m.groups())) if m else None

def main():
    path, pkg = sys.argv[1], sys.argv[2]
    try:
        root = ET.parse(path).getroot()
    except Exception as e:
        print(f"  (no dump: {e})")
        return 0
    nodes = list(root.iter('node'))
    screen = None
    for n in nodes:
        r = rect(n)
        if r and (screen is None or (r[2] - r[0]) * (r[3] - r[1]) > (screen[2] - screen[0]) * (screen[3] - screen[1])):
            screen = r
    # Only the app's own views: Android's built-in widgets (date/time pickers…) have "android:id/…" IDs.
    texts = [(n.get('text'), rect(n)) for n in nodes
             if n.get('package') == pkg and (n.get('text') or '').strip() and rect(n)
             and not (n.get('resource-id') or '').startswith('android:id/')]
    bad = 0
    for i in range(len(texts)):
        t1, a = texts[i]
        if screen and (a[0] < screen[0] - 2 or a[2] > screen[2] + 2):
            print(f"  OUTSIDE SCREEN: '{t1}' {a}")
            bad += 1
        for j in range(i + 1, len(texts)):
            t2, b = texts[j]
            w = min(a[2], b[2]) - max(a[0], b[0])
            h = min(a[3], b[3]) - max(a[1], b[1])
            if w > 3 and h > 3:
                print(f"  OVERLAP: '{t1}' {a}  with  '{t2}' {b}")
                bad += 1
    return 1 if bad else 0

sys.exit(main())
