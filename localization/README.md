# Mobile translations

`en.tsv` and `yue-Hant-HK.tsv` are the editable source tables for Android.
Each line contains a JSON-quoted Chinese source string, a tab, and a JSON-quoted
translation. JSON quoting preserves newlines and tabs inside text. Both tables
must contain the same source keys. Keep numbered placeholders such as `{0}`.

Run `python tools/build_localizations.py` after editing. Commit both the TSV
sources and generated `app/src/main/assets/localization/*.json` files. Normal
builds use the bundled files and do not need the iOS checkout or OpenCC.
