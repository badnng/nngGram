# Telegram code highlighting grammar

`shared/src/jvmMain/resources/code-highlight/grammars.dat` is the unmodified grammar shipped by
Telegram Desktop's pinned `Telegram/ThirdParty/libprisma` submodule:

- Telegram Desktop: `a7f453612f4896ac04d0007a36ab0d377e5bcbcf`
- [libprisma](https://github.com/desktop-app/libprisma/tree/75f26c17308871bee69f69a2831a8af2bc2f3627): `75f26c17308871bee69f69a2831a8af2bc2f3627`
- Grammar SHA-256: `56fdf805f4a9fef8f5ed97006f18005d6b07398d8b28434c2d3aa39564e6715b`
- Upstream generator uses Prism 1.29.0; all shipped languages, aliases and nested grammars are retained.
- MIT license is preserved here and alongside the bundled grammar in the application resources.

`RichCodeHighlight.jvm.kt` adapts `LanguageTree.cpp`, `SyntaxHighlighter.cpp`, and `TokenList.cpp`:
ordered grammar matching, Prism lookbehind groups, greedy token replacement, recursive `inside`
grammars and rematching after replacement. It follows the official 32-level recursion guard.
`lib_spellcheck` revision `3273fd25c185fb626a9836dafbd644c2bd129ee1` supplies the `diff`/`patch`
to `git` alias and eight token-color categories. Incoming text is never parsed as Markdown.

The platform adaptation uses `java.util.regex` in place of Boost.Regex's ECMAScript mode.
Its compatibility scanner escapes literal nested `[` and `&` inside character classes and
non-quantifier `{` outside them. Dots and anchors use Boost's `char` byte separators (LF, CR and FF),
with strict buffer anchors for `no_mod_m` and no anchor between CRLF. UTF-8 continuation byte `0x85`
must never become Java's Unicode NEL line break. These are syntax conversions, not replacement language grammars.
All 3,674 bundled patterns must compile in the regression test. Regex-engine behavior can still
differ for unusual expressions; this is an adaptation of the official grammar, not a claim of
byte-for-byte parity on all input. Matching runs over UTF-8 bytes like upstream, then maps complete
code-point boundaries to Compose's UTF-16 indices. Tokens that split encoded characters are not
painted, preserving Chinese, emoji, selection and copying.

Highlighting is bounded to 65,536 UTF-8 bytes, four million regex/token operations and 32 recursion
levels. Unsupported languages, invalid Unicode or exhausted work budgets keep readable plain
code. The composable paints plain text first and dispatches tokenization to a single background
worker queue; switching text/language cancels the prior result without reusing its ranges. Annotation
and spoiler-animation updates reuse tokenization but preserve explicit foreground styles, including
transparent or partially revealed spoilers. A 256-entry in-memory cache holds results only for the application session. Source code is
never written to a cache file or diagnostics. The grammar and license have their own Native Image
resource declarations; no JVM scripting engine, new JNI binding or network request is used.
