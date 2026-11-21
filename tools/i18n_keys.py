#!/usr/bin/env python3
"""Lists the English text shown in the app, as a starting point for a translation file.

    python3 tools/i18n_keys.py > keys.json      # {"English": ""} for every text found

Text with values filled in at run time ("$count channels") becomes a template ("{} channels").
Copy keys.json to app/src/main/assets/i18n/<language code>.json and fill in the translations;
anything left empty or missing stays in English.
"""
import json, pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java"
lit = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
keys = set()
SKIP = {"DashRecorder.kt", "HlsFolderRecorder.kt", "Hls.kt", "M3uParser.kt", "XmlTvParser.kt", "EpgMatcher.kt", "Catchup.kt", "StreamSpec.kt"}
for f in root.rglob("*.kt"):
    if f.name in SKIP:
        continue
    for line in f.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s.startswith(("import", "package", "//", "*", "@")) or "Regex(" in s or "Key(" in s:
            continue
        for m in lit.finditer(line):
            t = m.group(1)
            t = re.sub(r"\$\{[^}]*\}|\$[a-zA-Z_][a-zA-Z0-9_.]*", "{}", t)
            letters = re.sub(r"\{\}", "", t)
            # Human text: has a space or starts with a capital, and isn't a URL / key / format.
            if not re.search(r"[A-Za-z]{2}", letters) or re.match(r"^(https?:|[a-z_]+$|[a-z]+[A-Z])", t):
                continue
            if any(x in t for x in ("\\n", "%", "://")) and " " not in t:
                continue
            keys.add(t.replace('\\"', '"'))
json.dump({k: "" for k in sorted(keys)}, sys.stdout, ensure_ascii=False, indent=1)
