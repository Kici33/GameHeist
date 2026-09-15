import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prune_logs', Path(__file__).with_name('prune-logs.py'))
prune = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prune)


class RetentionTests(unittest.TestCase):
    def test_only_old_archives_are_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('2026-09-01-1.log.gz', '2026-09-15-1.log.gz', 'latest.log', 'reward_receipts.json'):
                path = root / name
                path.touch()
                os.utime(path, (0, 0) if name != '2026-09-15-1.log.gz' else (2_000_000, 2_000_000))
            self.assertEqual(['2026-09-01-1.log.gz'], [p.name for p in prune.candidates(root, 14, 2_000_000)])
            with self.assertRaises(ValueError):
                prune.candidates(root, 0, 2_000_000)
