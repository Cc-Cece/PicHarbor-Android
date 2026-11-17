"""Generate bundled mobile tables from this repository's editable UTF-8 TSVs."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read_table(path):
    table = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        key, value = line.split("\t", 1)
        key, value = json.loads(key), json.loads(value)
        if key in table:
            raise ValueError(f"{path}:{number}: duplicate key")
        table[key] = value
    return table


if __name__ == "__main__":
    tables = {language: read_table(ROOT / "localization" / f"{language}.tsv")
              for language in ("en", "yue-Hant-HK")}
    if set(tables["en"]) != set(tables["yue-Hant-HK"]):
        raise ValueError("Language tables must have identical source keys")
    for language, table in tables.items():
        target = ROOT / "app/src/main/assets/localization" / f"{language}.json"
        target.write_text(json.dumps(table, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(tables['en'])} entries per language")
