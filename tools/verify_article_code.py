"""Extract and verify the complete Java program printed in every numbered lesson."""
from pathlib import Path
import os
import subprocess

from article_sources import EXPECTED, article_sources
from verify_editions import verify_editions

ROOT = Path(__file__).resolve().parents[1]
H2 = ROOT / 'build/lib/h2-2.2.224.jar'
DATABASE_CHAPTERS = {11, 12, 16, 17}

def main():
    verify_editions()
    failures = []
    count = 0
    for language, article, text, block in article_sources():
        count += 1
        number = int(article.name[:2])
        directory = ROOT / 'build/article-check' / language / f'{number:02d}'
        directory.mkdir(parents=True, exist_ok=True)
        name = f'Demo{number:02d}'
        source = directory / f'{name}.java'
        source.write_text(block[1], encoding='utf-8')
        compile_result = subprocess.run(
            ['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', '.', source.name],
            cwd=directory, text=True, capture_output=True, timeout=45)
        if compile_result.returncode:
            failures.append(f'{article.name}:\n{compile_result.stderr}')
            continue
        classpath = '.'
        if number in DATABASE_CHAPTERS:
            if not H2.exists():
                failures.append(f'{article.name}: missing {H2}')
                continue
            classpath += os.pathsep + str(H2)
        result = subprocess.run(['java', '-cp', classpath, name], cwd=directory,
                                text=True, capture_output=True, timeout=20)
        expected = EXPECTED.search(text)
        if result.returncode:
            failures.append(f'{article.name}:\n{result.stderr}')
        elif expected is None or result.stdout.strip() != expected.group(1).strip():
            failures.append(f'{article.name}: output mismatch\nactual: {result.stdout}\nexpected: {expected.group(1) if expected else "missing"}')
        else:
            print(f'PASS {language}/{name}: compiled in isolation, executed, output matched')
    if failures:
        raise SystemExit('\n\n'.join(failures))
    print(f'PASS all {count} article programs; no companion source files used')

if __name__ == '__main__':
    main()
