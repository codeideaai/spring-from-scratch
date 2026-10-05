"""Run Spotless on examples and on Java code embedded in the tutorials."""
import argparse
import os
from pathlib import Path
import subprocess

from article_sources import article_sources

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["check", "apply"])
    args = parser.parse_args()
    directory = ROOT / "build/spotless-articles"
    directory.mkdir(parents=True, exist_ok=True)
    # Discard only generated sources so removed lessons cannot leave stale check targets.
    for source in directory.rglob("*.java"):
        source.unlink()
    articles = []
    for language, article, text, match in article_sources():
        source = directory / language / f"Demo{article.name[:2]}.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(match[1], encoding="utf-8")
        articles.append((article, text, match, source))
    if not articles:
        raise SystemExit("No article code found; refusing an empty formatting check")

    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    subprocess.run([str(wrapper), "-B", f"spotless:{args.mode}"], cwd=ROOT, check=True)
    if args.mode == "apply":
        # Replace only the code, preserving prose, commands and expected output verbatim.
        for article, text, match, source in articles:
            formatted = source.read_text(encoding="utf-8")
            updated = text[:match.start(1)] + formatted + text[match.end(1):]
            if updated != text:
                article.write_text(updated, encoding="utf-8")
    print(f"Spotless {args.mode}: {len(articles)} article programs and examples")


if __name__ == "__main__":
    main()
