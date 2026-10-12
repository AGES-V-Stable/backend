# Receipt fonts

Bundled under the SIL Open Font License (see license files alongside the fonts),
so receipt generation does not depend on host fonts or network access.
PDFBox embeds only the glyphs used by each document.

- NotoSans-Regular.ttf and NotoSans-Bold.ttf: https://github.com/notofonts/noto-fonts/tree/main/hinted/ttf/NotoSans
- NotoSansJP-Regular.ttf: https://github.com/google/fonts/tree/main/ofl/notosansjp
- NotoEmoji-Regular.ttf: https://github.com/google/fonts/tree/main/ofl/notoemoji

The Japanese and emoji variable TrueType fonts were instantiated at weight 400
using fontTools.varLib.instancer (no glyph subsetting). These fallback fonts cover
CJK and monochrome emoji; Noto Sans covers Latin, Greek and Cyrillic names.
Unsupported glyphs fail explicitly rather than silently changing a name.
