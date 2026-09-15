"""Prune only archived Minecraft logs in an explicit directory. Dry-run by default."""
import argparse
import re
import time
from pathlib import Path


def candidates(directory: Path, days: int, now: float):
    if days < 1 or days > 36500:
        raise ValueError('Retention must be 1–36500 days')
    root = directory.resolve(strict=True)
    if not root.is_dir():
        raise ValueError('Expected a logs directory')
    cutoff = now - days * 86400
    return sorted(p for p in root.iterdir() if not p.is_symlink() and p.is_file()
                  and re.fullmatch(r'\d{4}-\d{2}-\d{2}-\d+\.log\.gz', p.name)
                  and p.resolve().parent == root and p.stat().st_mtime < cutoff)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--days', type=int, default=14)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    for path in candidates(args.directory, args.days, time.time()):
        print(('Delete: ' if args.apply else 'Would delete: ') + str(path))
        if args.apply:
            # Recheck immediately before unlink; never recurse or follow directory links.
            if path.is_symlink() or path.resolve().parent != args.directory.resolve():
                raise RuntimeError('Log path changed during pruning')
            path.unlink()


if __name__ == '__main__':
    main()
