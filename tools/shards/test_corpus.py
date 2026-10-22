"""Small safety tests; never prune real data or execute --yes."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import corpus

class Safety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='emon-shard-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root_patch = patch.object(corpus, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        (self.root / 'source').mkdir()
        (self.root / 'source/a').write_text('a')
        self.shard = dict(id='a', paths=['source'], keep=False, reason='', fetch=[])
        self.shard.update(corpus.fingerprint(self.shard))

    def test_changes_and_appledouble(self):
        self.assertEqual(corpus.state(self.shard)[0], 'present')
        (self.root / 'source/._a').write_text('ignored')
        (self.root / 'source/._dir').mkdir()
        (self.root / 'source/._dir/b').write_text('ignored')
        self.assertEqual(corpus.state(self.shard)[0], 'present')
        (self.root / 'source/a').write_text('b')
        self.assertEqual(corpus.state(self.shard)[0], 'modified')

    def test_glob_inventory_matches_directory_and_ignores_appledouble(self):
        (self.root / 'source/._a').write_text('ignored')
        self.assertEqual(corpus.fingerprint({'paths': ['source/*']}), corpus.fingerprint(self.shard))
        (self.root / 'source/link').symlink_to(self.root / 'source/a')
        with self.assertRaisesRegex(ValueError, 'symlink'):
            corpus.files({'paths': ['source/*']})

    def test_absent_and_partial_guard(self):
        reg = {'consumers': {'task': ['a']}, 'shards': [self.shard]}
        (self.root / 'source/a').unlink()
        self.assertEqual(corpus.state(self.shard)[0], 'absent')
        with self.assertRaisesRegex(ValueError, 'bb corpus fetch a'):
            corpus.require(reg, 'task', [])

    def test_symlink_refused(self):
        (self.root / 'source/link').symlink_to(self.root / 'source/a')
        with self.assertRaisesRegex(ValueError, 'symlink'):
            corpus.fingerprint(self.shard)

    def test_prune_defaults_to_dry_run_and_retains_modified_and_keep(self):
        reg = dict(default=[], consumers={}, shards=[self.shard])
        registry = self.root / 'registry.json'
        def prune(*args):
            registry.write_text(json.dumps(reg))
            output = io.StringIO()
            with patch.object(corpus, 'REGISTRY', registry), patch.object(corpus, 'config', return_value={}), patch.object(sys, 'argv', ['corpus', 'prune', *args]), contextlib.redirect_stdout(output):
                corpus.main()
            self.assertTrue((self.root / 'source/a').exists())
            return output.getvalue()
        self.assertIn('WOULD DELETE a', prune())
        self.shard['keep'] = True
        self.assertIn('RETAIN a', prune('--dry-run'))
        self.shard['keep'] = False
        (self.root / 'source/a').write_text('changed')
        self.assertIn('digest mismatch', prune())

    def test_modified_fetch_never_runs_recipe(self):
        (self.root / 'source/a').write_text('changed')
        with patch.object(corpus.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'modified or partial'):
                corpus.fetch(self.shard)
            run.assert_not_called()

    def test_unsafe_path_refused(self):
        with self.assertRaisesRegex(ValueError, 'unsafe shard path'):
            corpus.files({'paths': ['../outside']})

    def test_keep_fetch_does_not_invoke_recipe(self):
        self.shard['keep'] = True
        with patch.object(corpus.subprocess, 'run') as run:
            corpus.fetch(self.shard)
            run.assert_not_called()

if __name__ == '__main__':
    unittest.main()
