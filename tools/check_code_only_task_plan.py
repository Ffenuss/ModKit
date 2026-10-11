#!/usr/bin/env python3
"""Fail before execution if a source-only Gradle task graph would build/install APKs."""
from pathlib import Path
import re
import sys


def check(text):
    tasks = re.findall(r'^(:\S+)\s+SKIPPED\s*$', text, re.MULTILINE)
    if not tasks:
        raise ValueError('No Gradle dry-run tasks found; cannot verify code-only execution')
    blocked = [task for task in tasks if re.match(
        r'(assemble|install|connected|externalNativeBuild|buildCMake|buildNdkBuild)|'
        r'package(?:Debug|Release)(?:AndroidTest)?$|'
        r'generateRuntimeProbeDexAsset$|prepareFixtureAssets$|prepareNativeFixtureAssets$',
        task.rsplit(':', 1)[-1])]
    if blocked:
        raise ValueError('APK/native/device tasks are forbidden in code-only mode: ' + ', '.join(blocked))
    return tasks


if __name__ == '__main__':
    tasks = check(Path(sys.argv[1]).read_text())
    print(f'Verified {len(tasks)} source-only Gradle tasks; no APK/native/device tasks')
