#!/usr/bin/env python3
"""Compare current AI with a Git revision using the Kotlin compiler already cached by Gradle."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", default="HEAD", help="Git revision containing the reference AI")
    parser.add_argument("--seed-start", type=int, default=1)
    parser.add_argument("--games", type=int, default=300)
    parser.add_argument("--samples", type=int, default=300, help="Random timing hands, plus three dense hands")
    parser.add_argument("--java", help="Java executable; otherwise JAVA_HOME/bin/java or PATH")
    args = parser.parse_args()
    if args.games < 1 or args.samples < 0:
        parser.error("games must be positive and samples must be nonnegative")
    root = Path(__file__).resolve().parent.parent
    output = root / "app/build/ai-benchmark"
    output.mkdir(parents=True, exist_ok=True)
    kotlin = root / "app/src/main/kotlin/com/arcxya/doudizhu"
    relative = "app/src/main/kotlin/com/arcxya/doudizhu/"

    def git(*command):
        return subprocess.check_output(["git", *command], cwd=root, text=True, encoding="utf-8")

    revision = git("rev-parse", "--verify", args.baseline_ref + "^{commit}").strip()
    source = git("show", revision + ":" + relative + "Rules.kt")
    # Reuse the current public Move/Kind types, while giving the historical policy its own object.
    start = source.index("object Rules {")
    end = source.index("data class Game(", start)
    historical = source[start:end].replace("object Rules {", "object BaselineRules {")
    historical = historical.replace("HandEvaluator", "BaselineHandEvaluator")
    baseline = output / "BaselineRules.kt"
    baseline.write_text("package com.arcxya.doudizhu\nimport kotlin.random.Random\n" + historical, encoding="utf-8")
    sources = [kotlin / "Rules.kt", kotlin / "HandEvaluator.kt", baseline, root / "scripts/AiBenchmark.kt"]
    if "BaselineHandEvaluator" in historical:
        helper = git("show", revision + ":" + relative + "HandEvaluator.kt")
        helper = re.sub(r"\bHandEvaluator\b", "BaselineHandEvaluator", helper)
        helper = re.sub(r"\bRules\b", "BaselineRules", helper)
        helper_path = output / "BaselineHandEvaluator.kt"
        helper_path.write_text(helper, encoding="utf-8")
        sources.append(helper_path)

    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    version = re.search(r"org\.jetbrains\.kotlin\.android' version '([^']+)'", (root / "build.gradle").read_text())[1]

    def jar(group, artifact, version):
        matches = sorted((cache / group / artifact / version).glob("*/*.jar"))
        if not matches:
            raise SystemExit(f"Missing cached {artifact}:{version}; run the Gradle debug build first.")
        return matches[0]

    # Dependencies of the repository's pinned Kotlin 2.0.21 compiler, not Android runtime libraries.
    if version != "2.0.21":
        raise SystemExit("Update the compiler dependency list in this harness after changing Kotlin versions.")
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
    annotations = jar("org.jetbrains", "annotations", "13.0")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version), stdlib,
                jar("org.jetbrains.kotlin", "kotlin-script-runtime", version),
                jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"), annotations]
    java = args.java
    if not java and os.environ.get("JAVA_HOME"):
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    java = java or shutil.which("java")
    if not java:
        raise SystemExit("Set JAVA_HOME or pass --java with a JDK 17+ executable.")
    artifact = output / "benchmark.jar"
    subprocess.run([java, "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(map(str, [stdlib, annotations])),
                    "-d", str(artifact), *map(str, sources)], cwd=root, check=True)
    metadata = [f"Baseline revision: {revision}"]
    for file in (kotlin / "Rules.kt", kotlin / "HandEvaluator.kt"):
        metadata.append(f"Current {file.name} SHA256: {hashlib.sha256(file.read_bytes()).hexdigest()}")
    completed = subprocess.run([java, "-cp", os.pathsep.join(map(str, [artifact, stdlib])),
                                "com.arcxya.doudizhu.AiBenchmarkKt", str(args.seed_start), str(args.games), str(args.samples)],
                               cwd=root, text=True, encoding="utf-8", capture_output=True, check=True)
    result = "\n".join(metadata) + "\n" + completed.stdout
    log = output / f"results-{args.seed_start}-{args.games}.txt"
    log.write_text(result, encoding="utf-8")
    print(result, end="")
    print(f"Saved {log}")


if __name__ == "__main__":
    main()
