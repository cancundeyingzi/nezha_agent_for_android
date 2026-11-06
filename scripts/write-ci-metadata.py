#!/usr/bin/env python3
"""Record the checked-out source and workflow attempt alongside build/test artifacts."""

import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parent.parent

    def git(*arguments: str) -> str:
        return subprocess.check_output(
            ["git", *arguments], cwd=repository, text=True, encoding="utf-8"
        ).strip()

    metadata = {
        "commit": git("rev-parse", "HEAD"),
        "source_tree_dirty": bool(git("status", "--porcelain")),
        "recorded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "repository": os.environ.get("GITHUB_REPOSITORY"),
        "event": os.environ.get("GITHUB_EVENT_NAME"),
        "event_sha": os.environ.get("GITHUB_SHA"),
        "ref": os.environ.get("GITHUB_REF"),
        "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "job": os.environ.get("GITHUB_JOB"),
        "matrix": os.environ.get("CI_MATRIX"),
        "runner_os": os.environ.get("RUNNER_OS"),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
