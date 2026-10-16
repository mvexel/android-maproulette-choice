"""UI Automator dump helper for the emulator smoke check.

  ui.py dump.xml find "<text>"   centre "x y" of the first node whose text or content-desc contains <text> (case-insensitive)
  ui.py dump.xml option          centre of the first answer option after "Question N of M"
  ui.py dump.xml exact "<text>"  centre of the first node whose text is exactly <text>
  ui.py dump.xml starts "<text>" centre of the first node whose text starts with <text> (case-sensitive)
  ui.py dump.xml texts           every node with text or content-desc, for the log
"""
import re
import sys
import xml.etree.ElementTree as ET


def bounds(node):
    m = re.findall(r"\d+", node.get("bounds", ""))
    return tuple(map(int, m)) if len(m) == 4 else None


def centre(node):
    x1, y1, x2, y2 = bounds(node)
    return f"{(x1 + x2) // 2} {(y1 + y2) // 2}"


def main():
    try:
        root = ET.parse(sys.argv[1]).getroot()
    except (ET.ParseError, FileNotFoundError):
        return
    cmd = sys.argv[2]
    nodes = list(root.iter("node"))
    if cmd == "find":
        q = sys.argv[3].lower()
        for n in nodes:
            s = (n.get("text", "") + " | " + n.get("content-desc", "")).lower()
            if q in s and bounds(n):
                print(centre(n))
                return
    elif cmd == "exact":
        for n in nodes:
            if n.get("text", "") == sys.argv[3] and bounds(n):
                print(centre(n))
                return
    elif cmd == "starts":
        for n in nodes:
            if n.get("text", "").startswith(sys.argv[3]) and bounds(n):
                print(centre(n))
                return
    elif cmd == "option":
        after = False
        for n in nodes:
            t = n.get("text", "")
            if re.match(r"Question \d of \d", t):
                after = True
                continue
            if (after and n.get("class", "").endswith("TextView") and t and not t.endswith("?")
                    and len(t) < 40 and not re.match(r"^(The |Look|Count|For a)", t) and bounds(n)):
                print(centre(n))
                return
    elif cmd == "texts":
        for n in nodes:
            tx, cd = n.get("text", ""), n.get("content-desc", "")
            if tx or cd:
                print(f"[{tx}] desc=[{cd}]")


main()
