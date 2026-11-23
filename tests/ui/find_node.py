"""find_node.py UI.xml LABEL [REGEX] [first]
Prints "x y" (centre) of the first node whose text or content-desc equals LABEL (or matches REGEX)."""
import re, sys, xml.etree.ElementTree as ET

def main():
    path, label = sys.argv[1], sys.argv[2]
    rx = re.compile(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] else None
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return
    for n in root.iter('node'):
        vals = [n.get('text') or '', n.get('content-desc') or '']
        ok = any((rx.search(v) if rx else v == label) for v in vals if v)
        if ok:
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                if x2 > x1 and y2 > y1:
                    print((x1 + x2) // 2, (y1 + y2) // 2)
                    return

main()
