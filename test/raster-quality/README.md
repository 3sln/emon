Synthetic raster table at 300 dpi: #ccc rules, #e6e6e6 zebra bands, three columns and five rows (header included). No corpus data.

Reproduce with ImageMagick's built-in SVG reader:

```sh
magick -background white MSVG:test/raster-quality/light-table.svg -units PixelsPerInch -density 300 test/raster-quality/light-table.png
magick test/raster-quality/light-table.png -quality 55 test/raster-quality/light-table.jpg
```

`rasterqualitytest.kin` holds the document-unit wiring. These fixtures exercise live OCR and the tracer together; check the table's grid profile, five rows, three columns, and no standalone rule glyph in the body.

Run the live fixture check with `bb tools/fusion/raster_quality.clj` after building the live Rust driver. Use `--driver=PATH` to compare another build; the baseline fails because its table profile is `whitespace` rather than `ruled`.
