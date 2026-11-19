"""Regression checks for the Java image build step in GitHub Actions.

The fake Gradle wrapper intentionally exercises the real workflow shell script
without network access, Java, or modifying the repository's build cache.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "frontend-ci.yml"


def workflow_build_script():
    content = WORKFLOW.read_text(encoding="utf-8")
    section = content.split("      - name: Build only the selected Java service\n", 1)[1]
    section = section.split("        run: |\n", 1)[1]
    section = section.split("      - uses: docker/setup-buildx-action@v3", 1)[0]
    script = textwrap.dedent(section)
    # CI uses bounded backoff; the mock tests should not spend time sleeping.
    return script.replace('sleep "$((attempt * 10))"', "sleep 0")


def bash_binary():
    if os.name == "nt":
        git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
        if git_bash.exists():
            return str(git_bash)
    return shutil.which("bash")


class JavaImageRetryTest(unittest.TestCase):
    def setUp(self):
        self.bash = bash_binary()
        if not self.bash:
            self.skipTest("bash is required to verify the GitHub Actions shell step")

    def run_build(self, scenario):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = root / "backend"
            backend.mkdir()
            (root / "build.sh").write_text(workflow_build_script(), encoding="utf-8")
            (backend / "gradlew").write_text(textwrap.dedent("""\
                #!/usr/bin/env bash
                set -euo pipefail
                count=$(cat ../attempts 2>/dev/null || echo 0)
                count=$((count + 1))
                echo "$count" > ../attempts
                echo "$*" >> ../args.log
                if [[ "$SCENARIO" == 'compile' ]]; then
                  echo 'error: cannot find symbol' >&2
                  exit 1
                fi
                if [[ "$SCENARIO" == 'missing' ]] ||
                    { [[ "$SCENARIO" == 'transient' ]] && (( count == 1 )); }; then
                  echo 'Could not find cloudinary-http44-1.36.0.jar' >&2
                  exit 1
                fi
                mkdir -p promotion-loyalty-service/build/libs
                echo 'valid mock jar' > promotion-loyalty-service/build/libs/promotion-loyalty-service-0.1.0-SNAPSHOT.jar
                """), encoding="utf-8")
            env = {**os.environ, "SERVICE": "promotion-loyalty-service", "SCENARIO": scenario}
            result = subprocess.run([self.bash, "build.sh"], cwd=root,
                                    env=env, capture_output=True, text=True, timeout=30)
            arguments = (root / "args.log").read_text().splitlines()
            image = root / "image-context" / "app.jar"
            return result, arguments, image.read_text() if image.exists() else None

    def test_success_does_not_refresh_or_retry(self):
        result, args, image = self.run_build("success")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(args), 1)
        self.assertNotIn("--refresh-dependencies", args[0])
        self.assertEqual(image.strip(), "valid mock jar")

    def test_cached_missing_artifact_is_refreshed_on_retry(self):
        result, args, image = self.run_build("transient")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(args), 2)
        self.assertNotIn("--refresh-dependencies", args[0])
        self.assertIn("--refresh-dependencies", args[1])
        self.assertEqual(image.strip(), "valid mock jar")

    def test_compile_errors_fail_without_retrying(self):
        result, args, image = self.run_build("compile")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(args), 1)
        self.assertIsNone(image)

    def test_persistent_missing_artifact_fails_after_three_attempts(self):
        result, args, image = self.run_build("missing")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(args), 3)
        self.assertTrue(all("--refresh-dependencies" in arg for arg in args[1:]))
        self.assertIsNone(image)


if __name__ == "__main__":
    unittest.main()
