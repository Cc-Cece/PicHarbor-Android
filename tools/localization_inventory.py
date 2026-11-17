"""Static Kotlin string inventory, including interpolation templates. No app execution."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def strings(text):
    found = []

    def quoted(at):
        at += 1
        output, slot = [], 0
        while at < len(text):
            if text[at] == '\\' and at + 1 < len(text):
                output.append(text[at:at + 2]); at += 2
            elif text[at] == '"':
                raw = ''.join(output).replace('\\$', '$')
                try:
                    value = json.loads('"' + raw + '"')
                except ValueError:
                    value = raw
                if re.search('[\u4e00-\u9fff]', value):
                    found.append(value)
                return at + 1
            elif text.startswith('${', at):
                output.append('{' + str(slot) + '}'); slot += 1
                at += 2
                depth = 1
                while at < len(text) and depth:
                    if text[at] == '"':
                        at = quoted(at)
                    else:
                        depth += (text[at] == '{') - (text[at] == '}')
                        at += 1
            elif text[at] == '$' and re.match('[A-Za-z_]', text[at + 1:at + 2]):
                output.append('{' + str(slot) + '}'); slot += 1
                at += len(re.match(r'\$[A-Za-z_][A-Za-z_0-9]*', text[at:])[0])
            else:
                output.append(text[at]); at += 1
        return at

    at = 0
    while at < len(text):
        if text.startswith('//', at):
            end = text.find('\n', at); at = len(text) if end < 0 else end
        elif text.startswith('/*', at):
            end = text.find('*/', at + 2); at = len(text) if end < 0 else end + 2
        elif text.startswith('"""', at):
            end = text.find('"""', at + 3); at = len(text) if end < 0 else end + 3
        elif text[at] == '"':
            at = quoted(at)
        else:
            at += 1
    return found


if __name__ == '__main__':
    table = json.loads((ROOT / 'app/src/main/assets/localization/en.json').read_text(encoding='utf-8'))
    inventory = {}
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        for value in strings(path.read_text(encoding='utf-8')):
            inventory.setdefault(value, []).append(path.name)
    missing = {key: sorted(set(files)) for key, files in sorted(inventory.items()) if key not in table}
    target = ROOT / 'build/localization-missing.json'
    target.parent.mkdir(exist_ok=True)
    target.write_text(json.dumps(missing, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{len(inventory)} Chinese source strings; {len(missing)} missing entries. Report: {target}')
    for key in list(missing)[:80]:
        print(json.dumps(key, ensure_ascii=False))
