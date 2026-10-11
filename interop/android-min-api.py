#!/usr/bin/env python3
"""Minimum Android API level a set of JVM jars needs, from their bytecode.

Reads every class's constant pool (class, field and method references), looks each platform reference up in the
Android SDK's api-versions.xml (walking superclasses and interfaces for inherited members), and reports the highest
"since" level with the references that require it. Usage: scan.py <api-versions.xml> <jar>...
"""
import struct
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict


def load_api(path):
    classes = {}
    for c in ET.parse(path).getroot().iter("class"):
        since = int(c.get("since", "1"))
        members = {}
        for m in list(c.iter("method")) + list(c.iter("field")):
            members[m.get("name")] = int(m.get("since", str(since)))
        supers = [e.get("name") for e in c.iter("extends")] + [e.get("name") for e in c.iter("implements")]
        classes[c.get("name")] = (since, members, supers)
    return classes


def refs(data):
    """(kind, class, name, descriptor) for each Class / Fieldref / Methodref / InterfaceMethodref constant."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    count = struct.unpack(">H", data[8:10])[0]
    pool = [None] * count
    i, pos = 1, 10
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            pool[i] = ("utf8", data[pos + 3:pos + 3 + n].decode("utf-8", "replace"))
            pos += 3 + n
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            a, b = struct.unpack(">HH", data[pos + 1:pos + 5])
            pool[i] = (tag, a, b)
            pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError(f"constant tag {tag}")
        i += 1

    def utf(k):
        return pool[k][1]

    out = []
    for e in pool:
        if not e:
            continue
        if e[0] == 7:
            out.append(("class", utf(e[1]), None, None))
        elif e[0] in (9, 10, 11):
            cls = utf(pool[e[1]][1])
            nat = pool[e[2]]
            out.append(("field" if e[0] == 9 else "method", cls, utf(nat[1]), utf(nat[2])))
    return out


def member_since(api, cls, key, seen=None):
    seen = seen or set()
    if cls in seen or cls not in api:
        return None
    seen.add(cls)
    since, members, supers = api[cls]
    if key in members:
        return members[key]
    for s in supers:
        found = member_since(api, s, key, seen)
        if found is not None:
            return found
    return None


def main():
    api = load_api(sys.argv[1])
    needs = defaultdict(set)
    unknown = set()
    for jar in sys.argv[2:]:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if not name.endswith(".class") or name.startswith("META-INF/versions/"):
                    continue
                for kind, cls, member, desc in refs(z.read(name)):
                    if cls.startswith("["):
                        continue
                    if not cls.startswith(("java/", "javax/", "android/", "dalvik/", "sun/")):
                        continue
                    if cls not in api:
                        unknown.add(cls)
                        continue
                    level = api[cls][0]
                    what = cls
                    if kind != "class":
                        key = member + desc if kind == "method" else member
                        found = member_since(api, cls, key)
                        if found is None and kind == "method":
                            found = member_since(api, cls, member + desc.rsplit(")", 1)[0] + ")")
                        if found is None:
                            unknown.add(f"{cls}.{key}")
                            continue
                        level = max(level, found)
                        what = f"{cls}.{key}"
                    needs[level].add(f"{what}  ({jar.rsplit('/', 1)[-1]})")
    top = max(needs) if needs else 1
    for level in sorted(needs, reverse=True):
        if level <= 21:
            break
        print(f"API {level}:")
        for w in sorted(needs[level]):
            print(f"  {w}")
    print(f"minimum API level: {top}")
    if unknown:
        print("not in the Android API (fails at run time if reached):")
        for u in sorted(unknown):
            print(f"  {u}")


main()
