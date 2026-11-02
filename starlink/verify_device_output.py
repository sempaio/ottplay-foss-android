#!/usr/bin/env python3
"""Verify adb instrumentation success and coverage of every acceptance class."""
import json
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
output = path.read_text()
expected = {'StarlinkUiInstrumentedTest', 'StarlinkStartupInstrumentedTest', 'NativeUiInstrumentedTest',
            'HlsPlaybackInstrumentedTest', 'NativePlaybackInstrumentedTest', 'NeonLayoutInstrumentedTest', 'CustomerUiInstrumentedTest'}
actual = {name.split('.')[-1] for name in re.findall(r'INSTRUMENTATION_STATUS: class=([^\s]+)', output)}
assert expected <= actual, f'Missing test classes: {sorted(expected - actual)}'
match = re.search(r'\bOK \((\d+) tests?\)', output)
assert match and int(match[1]) >= 19, 'Instrumentation did not report all 19 tests passing'
assert 'FAILURES!!!' not in output and 'INSTRUMENTATION_FAILED' not in output, 'Instrumentation failed'
result = {'tests': int(match[1]), 'failures': 0, 'classes': sorted(actual)}
path.with_suffix('.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
