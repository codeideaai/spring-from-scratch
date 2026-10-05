"""Discover matching tutorial programs in both editions."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
EDITIONS = {"zh": ROOT / "tutorial", "en": ROOT / "en/tutorial"}
BLOCK = re.compile(r"^```java\n(.*?)^```", re.M | re.S)
EXPECTED = re.compile(r"(?:预期输出：|Expected output:)\s*```text\n(.*?)\n```", re.S)


def article_sources():
    for language, directory in EDITIONS.items():
        articles = sorted(directory.glob("[0-9][0-9]-*.md"))
        numbers = [int(article.name[:2]) for article in articles]
        if numbers != list(range(18)):
            raise ValueError(f"{language}: expected chapters 00 through 17, got {numbers}")
        for article in articles[1:]:
            text = article.read_text(encoding="utf-8")
            blocks = list(BLOCK.finditer(text))
            if len(blocks) != 1:
                raise ValueError(f"{article}: expected one complete Java block")
            yield language, article, text, blocks[0]
