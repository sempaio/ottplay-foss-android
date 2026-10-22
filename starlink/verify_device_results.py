#!/usr/bin/env python3
"""Reject incomplete instrumentation runs even when the runner reports success."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

folder = Path(sys.argv[1])
files = list(folder.rglob('TEST-*.xml'))
cases = [case for path in files for case in ET.parse(path).getroot().iter('testcase')]
expected = {'StarlinkUiInstrumentedTest', 'StarlinkStartupInstrumentedTest', 'NativeUiInstrumentedTest',
            'HlsPlaybackInstrumentedTest', 'NativePlaybackInstrumentedTest'}
actual = {case.get('classname', '').split('.')[-1] for case in cases}
assert expected <= actual, f'Missing device test classes: {sorted(expected - actual)}'
assert len(cases) >= 12, f'Incomplete suite: only {len(cases)} test cases'
assert not any(case.find(tag) is not None for case in cases for tag in ['failure', 'error', 'skipped']), 'Device tests failed or skipped'
print(f'Validated {len(cases)} device tests across {len(actual)} classes; zero failures or skips')
