# Génère internal/search/table.go : la lettre de base de chaque caractère
# précomposé qui porte un accent.
#
# Kotlin replie un nom par la décomposition NFD de la plateforme, puis retire
# les marques combinantes. La bibliothèque standard de Go n'a pas de NFD, et
# golang.org/x/text pèserait dans libgojni.so (carnet : OUTIL-POIDS). Cette
# table rend le même résultat pour un seul caractère, ce qui suffit : les
# marques déjà décomposées sont retirées une à une par le code Go.
#
# Usage, depuis la racine du dépôt :
#   python scripts/genere-table-repli.py
#   gofmt -w internal/search/table.go

import sys
import unicodedata

entrees = []
for code in range(0x00C0, 0x10000):
    # Les syllabes coréennes se décomposent en jamos, qui ne sont pas des
    # marques : rien à replier, et onze mille entrées pour rien.
    if 0xAC00 <= code <= 0xD7A3 or 0xD800 <= code <= 0xDFFF:
        continue
    c = chr(code)
    nfd = unicodedata.normalize("NFD", c)
    if nfd == c:
        continue
    base = "".join(x for x in nfd if unicodedata.category(x) != "Mn")
    # Seules les décompositions « une lettre et ses accents » intéressent la
    # recherche ; une décomposition en plusieurs lettres n'est pas un accent.
    if len(base) != 1 or base == c:
        continue
    entrees.append((code, ord(base)))

lignes = [
    "// Code généré par scripts/genere-table-repli.py ; ne pas modifier à la main.",
    "",
    "package search",
    "",
    "// lettreDeBase associe à un caractère précomposé sa lettre sans accent,",
    "// telle que la donnerait la décomposition NFD suivie du retrait des marques.",
    f"// Unicode {unicodedata.unidata_version}, {len(entrees)} entrées.",
    "var lettreDeBase = map[rune]rune{",
]
for code, base in entrees:
    lignes.append(f"\t0x{code:04X}: 0x{base:04X}, // {chr(code)} → {chr(base)}")
lignes.append("}")
lignes.append("")

with open("internal/search/table.go", "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lignes))

print(f"{len(entrees)} entrées", file=sys.stderr)
