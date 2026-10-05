"""Check that shared components in article snapshots match the runnable project."""
from pathlib import Path

from article_sources import article_sources
from verify_editions import executable_tokens

ROOT = Path(__file__).resolve().parents[1]
# These earlier teaching snapshots intentionally precede later capabilities.
EARLIER_SNAPSHOTS = {(11, "BeanBox"), (12, "BeanBox"), (16, "LibraryApp")}


def type_body(tokens, name):
    for index in range(len(tokens) - 1):
        if tokens[index] in ("class", "interface") and tokens[index + 1] == name:
            start = tokens.index("{", index + 2)
            depth = 1
            for end in range(start + 1, len(tokens)):
                if tokens[end] == "{":
                    depth += 1
                elif tokens[end] == "}":
                    depth -= 1
                if depth == 0:
                    return tokens[start + 1:end]
            raise ValueError(f"Unclosed type: {name}")
    return None


def verify_project_alignment():
    components = {}
    for source in (ROOT / "examples/library/src").rglob("*.java"):
        body = type_body(executable_tokens(source.read_text(encoding="utf-8")), source.stem)
        if body is None:
            raise ValueError(f"Missing top-level type: {source}")
        components[source.stem] = body
    covered = set()
    comparisons = 0
    for language, article, _, block in article_sources():
        if language != "zh":
            continue  # verify_editions checks the complete English implementation separately.
        number = int(article.name[:2])
        tokens = executable_tokens(block[1])
        for name, expected in components.items():
            actual = type_body(tokens, name)
            if actual is None or (number, name) in EARLIER_SNAPSHOTS:
                continue
            if actual != expected:
                raise ValueError(f"{article.name}: shared component {name} differs from project")
            covered.add(name)
            comparisons += 1
    if covered != components.keys():
        raise ValueError(f"Project components absent from article checks: {components.keys() - covered}")
    print(f"PASS project alignment: {comparisons} component snapshots, {len(covered)} project files")


if __name__ == "__main__":
    verify_project_alignment()
