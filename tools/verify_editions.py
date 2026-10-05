"""Reject executable-code or expected-output drift between language editions."""
import re

from article_sources import EXPECTED, article_sources

# Match Java literals before comments so URLs and comment markers inside strings survive.
# Text blocks remain whole tokens: their contents are part of the runnable example.
TOKENS = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    r'|//[^\n]*|/\*[\s\S]*?\*/|\w+|[^\s]'
)


def executable_tokens(source):
    return [token for token in TOKENS.findall(source)
            if not token.startswith(("//", "/*"))]


def verify_editions():
    programs = {}
    for language, article, text, block in article_sources():
        expected = EXPECTED.search(text)
        if expected is None:
            raise ValueError(f"{article}: expected output is missing")
        programs[language, article.name[:2]] = (
            executable_tokens(block[1]), expected[1])
    for number in range(1, 18):
        key = f"{number:02d}"
        if programs["zh", key] != programs["en", key]:
            raise ValueError(f"Chapter {key}: executable code or expected output differs by language")
    print("PASS bilingual parity: 17 matching implementations and expected outputs")


if __name__ == "__main__":
    verify_editions()
