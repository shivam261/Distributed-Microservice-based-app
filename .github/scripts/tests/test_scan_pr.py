import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('scan_pr', Path(__file__).parents[1] / 'scan_pr.py')
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)


class DiffTests(unittest.TestCase):
    def test_zero_context_hunks_include_added_lines_but_not_deletions(self):
        self.assertEqual(scan.added_ranges('@@ -1,2 +1,0 @@\n-old\n@@ -5 +4,2 @@\n+a\n+b\n'), [(4, 5)])
        self.assertEqual(scan.added_ranges('@@ -0,0 +1 @@\n+new'), [(1, 1)])

    def test_normalization_filters_old_and_unrelated_findings_and_deduplicates(self):
        def match(path, start, end):
            return {'path': path, 'start': {'line': start}, 'end': {'line': end},
                    'check_id': 'security.rule', 'extra': {'severity': 'ERROR', 'message': 'unsafe'}}
        semgrep = {'results': [match('src/A.java', 1, 2), match('src/A.java', 5, 6),
                               match('src/A.java', 5, 6), match('src/B.java', 5, 6)]}
        pmd = {'files': [{'filename': str(Path('src/A.java').resolve()), 'violations': [
            {'beginline': 6, 'endline': 6, 'rule': 'EmptyCatchBlock', 'priority': 3, 'description': 'empty'}]}]}
        result = scan.normalize_findings(semgrep, pmd, {'src/A.java': [(5, 6)]})
        self.assertEqual([(f['tool'], f['line']) for f in result], [('Semgrep', 5), ('PMD', 6)])

    def test_real_git_diff_handles_spaces_new_files_renames_and_deletions(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            try:
                os.chdir(temporary)
                def git(*args):
                    return subprocess.check_output(['git', *args], text=True, stderr=subprocess.DEVNULL).strip()
                git('init', '-q')
                git('config', 'user.email', 'test@example.com')
                git('config', 'user.name', 'Test')
                Path('Keep.java').write_text('class Keep {\n  int a;\n}\n')
                Path('Delete.java').write_text('class Delete {}\n')
                Path('Rename.java').write_text('class Rename {}\n')
                git('add', '.')
                git('commit', '-qm', 'base')
                base = git('rev-parse', 'HEAD')
                Path('Keep.java').write_text('class Keep {\n  int a;\n  int b;\n}\n')
                Path('New File.java').write_text('class NewFile {}\n')
                Path('Delete.java').unlink()
                Path('Rename.java').rename('Renamed.java')
                git('add', '.')
                git('commit', '-qm', 'head')
                changed = scan.changed_java(base, git('rev-parse', 'HEAD'))
                self.assertEqual(changed, {'Keep.java': [(3, 3)], 'New File.java': [(1, 1)], 'Renamed.java': [(1, 1)]})
            finally:
                os.chdir(previous)

    def test_unsafe_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            link = Path(temporary) / 'Link.java'
            link.symlink_to('/etc/passwd')
            with patch.object(scan, 'git', return_value=str(link) + '\0'):
                with self.assertRaisesRegex(ValueError, 'Unsafe'):
                    scan.changed_java('base', 'head')

    def test_scanner_failure_is_recorded_as_incomplete_not_clean(self):
        head, base = 'a' * 40, 'b' * 40
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / 'report.json'
            with patch.dict(os.environ, {'REPORT_PATH': str(report), 'HEAD_SHA': head,
                                        'BASE_SHA': base, 'PR_NUMBER': '7'}), \
                    patch.object(scan, 'git', side_effect=[head, base]), \
                    patch.object(scan, 'changed_java', return_value={'A.java': [(1, 1)]}), \
                    patch.object(scan, 'run_scanners', side_effect=RuntimeError('scanner unavailable')):
                self.assertEqual(scan.main(), 2)
            self.assertEqual(json.loads(report.read_text())['status'], 'error')

    def test_report_caps_findings_without_turning_remaining_findings_into_clean_scan(self):
        head, base = 'a' * 40, 'b' * 40
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / 'report.json'
            with patch.dict(os.environ, {'REPORT_PATH': str(report), 'HEAD_SHA': head,
                                        'BASE_SHA': base, 'PR_NUMBER': '7'}), \
                    patch.object(scan, 'git', side_effect=[head, base]), \
                    patch.object(scan, 'changed_java', return_value={'A.java': [(1, 1)]}), \
                    patch.object(scan, 'run_scanners', return_value=[{'message': 'finding'}] * 60):
                self.assertEqual(scan.main(), 1)
            data = json.loads(report.read_text())
            self.assertEqual(len(data['findings']), 50)
            self.assertEqual(data['total_findings'], 60)


if __name__ == '__main__':
    unittest.main()
