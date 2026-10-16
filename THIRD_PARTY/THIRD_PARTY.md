# Third-party components

This project (GPL-3.0) bundles the following third-party components. Each keeps
its own license; the full license texts are in this directory.

| Component | Where | License | Upstream |
|---|---|---|---|
| libjpeg-turbo (static lib + header) | host/jni/turbo/ | BSD-style + IJG (dual) | github.com/libjpeg-turbo/libjpeg-turbo |
| IJG libjpeg 9e (source) | camera/jni/libjpeg/ | IJG License (text included in libjpeg-turbo-LICENSE.md) | ijg.org |
| DSEG fonts (DSEG14, DSEG7) | camera/assets/, host/res/raw/ | SIL Open Font License 1.1 | github.com/keshikan/DSEG |
| Liberation Sans (Regular, Bold) | host/res/raw/ | SIL Open Font License 1.1 | github.com/liberationfonts/liberation-fonts |
| Archivo Black | host/res/raw/ | SIL Open Font License 1.1 | github.com/Omnibus-Type/ArchivoBlack |
| WenQuanYi Zen Hei | host/res/raw/ | GPL v2 with font embedding exception | wenq.org |

## Font notices

All fonts are bundled unmodified as separate resource files and are loaded at
runtime; they are not linked into the program's object code.

`OFL-1.1.txt` holds the SIL Open Font License 1.1, which covers the DSEG,
Liberation Sans and Archivo Black fonts. As the OFL requires, each font's
copyright notice is reproduced here:

- **DSEG** — Copyright (c) 2018, keshikan (http://www.keshikan.net), with
  Reserved Font Name "DSEG".
- **Liberation Sans** — Digitized data copyright (c) 2010 Google Corporation;
  Copyright (c) 2012 Red Hat, Inc. Liberation is a trademark of Red Hat, Inc.
  (also in `LiberationSans-OFL.txt`).
- **Archivo Black** — Copyright 2017 The Archivo Black Project Authors
  (https://github.com/Omnibus-Type/ArchivoBlack).

`LiberationSans-OFL.txt` carries the SIL Open Font License text as shipped
with the Liberation fonts.

## WenQuanYi Zen Hei

WenQuanYi Zen Hei is Copyright (c) Qianqian Fang and the WenQuanYi Board of
Trustees, licensed under the GNU General Public License version 2 with a font
embedding exception; the GPL v2 text is in `GPL-2.0.txt`. The exception reads:

> As a special exception, if you create a document which uses this font, and
> embed this font or unaltered portions of this font into the document, this
> font does not by itself cause the resulting document to be covered by the GNU
> General Public License. This exception does not however invalidate any other
> reasons why the document might be covered by the GNU General Public License.
> If you modify this font, you may extend this exception to your version of the
> font, but you are not obligated to do so. If you do not wish to do so, delete
> this exception statement from your version.
