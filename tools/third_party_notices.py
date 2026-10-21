#!/usr/bin/env python3
"""Writes THIRD-PARTY-NOTICES: every library the two apps ship, with its license, and the license
texts and NOTICE files those licenses require passing on.

Run it after adding, removing or updating a dependency (it runs Gradle itself):

    tools/third_party_notices.py

The libraries come from Gradle's resolved runtime classpaths (desktop: runtimeClasspath; Android:
releaseRuntimeClasspath); each one's license from its published POM (or the parent POM it
inherits from). A license it does not recognise stops it, so nothing is listed under a wrong one.
"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
OUT = os.path.join(ROOT, "THIRD-PARTY-NOTICES")
CONFIGURATIONS = [
    ("Desktop app (Linux)", ":desktopApp:dependencies", "runtimeClasspath"),
    ("Android app", ":androidApp:dependencies", "releaseRuntimeClasspath"),
]

# Declared license names (as found in the POMs) -> how they are listed.
APACHE = "Apache-2.0"
MIT = "MIT"
BOUNCY_CASTLE = "MIT (Bouncy Castle Licence)"
APACHE_OR_EPL = "Apache-2.0 OR EPL-1.0 (used under Apache-2.0)"


def classify(names):
    joined = "; ".join(names)
    if len(names) == 2 and any("Apache" in n for n in names) and any("Eclipse Public License" in n for n in names):
        return APACHE_OR_EPL
    if len(names) == 1:
        name = names[0]
        if "Apache" in name and "2.0" in name:
            return APACHE
        if name == "MIT" or name.startswith("MIT License"):
            return MIT
        if name == "Bouncy Castle Licence":
            return BOUNCY_CASTLE
    sys.exit(f"Unrecognised license {joined!r}: check it and add it to classify() in {__file__}")


def resolved_coordinates(task, configuration):
    """group:artifact:version of every module Gradle resolves for this configuration."""
    output = subprocess.run(
        [os.path.join(ROOT, "gradlew"), "-q", "--console=plain", task, "--configuration", configuration],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout
    coordinates = set()
    pattern = re.compile(r"--- ([^\s:]+):([^\s:]+):(\{[^}]*\}|[^\s]+)(?: -> ([^\s]+))?(.*)$")
    for line in output.splitlines():
        match = pattern.search(line)
        if not match:
            continue
        group, artifact, requested, resolved, rest = match.groups()
        if "(c)" in rest or "(n)" in rest:  # constraints and unresolved entries are not modules
            continue
        version = resolved or requested
        if version.startswith("{"):
            sys.exit(f"Could not tell the version of {group}:{artifact} from: {line.strip()}")
        coordinates.add((group, artifact, version))
    return coordinates


def find(group, artifact, version, extension):
    hits = glob.glob(f"{CACHE}/{group}/{artifact}/{version}/*/{artifact}-{version}.{extension}")
    return hits[0] if hits else None


def tag(element):
    return re.sub(r"\{[^}]*\}", "", element.tag)


def child(element, name):
    return next((c for c in element if tag(c) == name), None)


def pom_info(group, artifact, version):
    """(license names, packaging) from the POM, following parent POMs for the licenses."""
    path = find(group, artifact, version, "pom")
    if path is None:
        sys.exit(f"No POM for {group}:{artifact}:{version} in Gradle's cache; build the apps first")
    packaging = None
    for _ in range(6):
        root = ET.parse(path).getroot()
        if packaging is None:
            node = child(root, "packaging")
            packaging = node.text.strip() if node is not None and node.text else "jar"
        licenses = child(root, "licenses")
        if licenses is not None and len(licenses):
            names = [(child(l, "name").text or "").strip() for l in licenses if child(l, "name") is not None]
            return names, packaging
        parent = child(root, "parent")
        if parent is None:
            break
        path = find(*(child(parent, k).text.strip() for k in ("groupId", "artifactId", "version")), "pom")
        if path is None:
            break
    sys.exit(f"No license declared for {group}:{artifact}:{version} (nor in its parent POMs)")


def text_from_jar(group, artifact, version, entry):
    jar = find(group, artifact, version, "jar")
    if jar is None:
        sys.exit(f"No jar for {group}:{artifact}:{version} in Gradle's cache")
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8").strip("\n")


def runtime_version():
    release = os.path.join(ROOT, "desktopApp/build/compose/binaries/main/app/linkdrop/lib/runtime/release")
    if not os.path.isfile(release):
        sys.exit("Build the desktop distributable first: ./gradlew :desktopApp:createDistributable")
    match = re.search(r'^JAVA_VERSION="([^"]+)"', open(release).read(), re.M)
    return match.group(1)


def main():
    apps = []
    everything = {}
    for title, task, configuration in CONFIGURATIONS:
        rows = []
        for group, artifact, version in sorted(resolved_coordinates(task, configuration)):
            names, packaging = pom_info(group, artifact, version)
            if packaging == "pom":  # a BOM: only versions, no code
                continue
            license_ = classify(names)
            rows.append((f"{group}:{artifact}", version, license_))
            everything[(group, artifact, version)] = license_
        apps.append((title, rows))

    def resolved(group, artifact):
        versions = [v for (g, a, v) in everything if g == group and a == artifact]
        if not versions:
            sys.exit(f"{group}:{artifact} is no longer a dependency: update {__file__}")
        return versions[0]

    out = []
    out.append("THIRD-PARTY NOTICES")
    out.append("===================")
    out.append("")
    out.append("LinkDrop (see LICENSE) ships the third-party software listed below, each under the")
    out.append("license shown. The license texts and NOTICE files these licenses require follow the")
    out.append("lists.")
    out.append("")
    out.append("Generated by tools/third_party_notices.py from the apps' runtime dependencies. Do not")
    out.append("edit by hand: run that script after changing a dependency.")
    for title, rows in apps:
        out.append("")
        out.append("")
        out.append(title)
        out.append("-" * len(title))
        out.append("")
        width = max(len(name) + len(version) + 1 for name, version, _ in rows)
        for name, version, license_ in rows:
            out.append(f"  {(name + ' ' + version).ljust(width)}  {license_}")
        if title.startswith("Desktop"):
            out.append("")
            out.append(f"  Java runtime (OpenJDK {runtime_version()}): GPL-2.0 with the Classpath Exception. Its")
            out.append("  own notices are installed with it, in lib/runtime/legal/.")
            out.append("  libskiko (in org.jetbrains.skiko:skiko-awt-runtime-linux-x64) contains the Skia")
            out.append("  graphics library, which is under the BSD 3-Clause License (https://skia.org/).")

    def section(heading, body):
        out.append("")
        out.append("")
        out.append("=" * 78)
        out.append(heading)
        out.append("=" * 78)
        out.append("")
        out.append(body)

    section(
        "Apache License 2.0 (most libraries above)",
        text_from_jar("androidx.annotation", "annotation-jvm", resolved("androidx.annotation", "annotation-jvm"),
                      "META-INF/androidx/annotation/annotation/LICENSE.txt"),
    )
    section("MIT License: SLF4J (org.slf4j:slf4j-api, org.slf4j:slf4j-simple)",
            open(os.path.join(ROOT, "tools/licenses/SLF4J-LICENSE.txt")).read().strip("\n"))
    section(
        "MIT License: Bouncy Castle (org.bouncycastle:bcprov-jdk18on, bcpkix-jdk18on, bcutil-jdk18on)",
        text_from_jar("org.bouncycastle", "bcprov-jdk18on", resolved("org.bouncycastle", "bcprov-jdk18on"),
                      "META-INF/LICENSE.md"),
    )
    quic = ("io.netty", "netty-codec-native-quic")
    quic_version = resolved(*quic)
    quic_jar_name = f"{quic[1]}-{quic_version}-linux-x86_64.jar"
    quic_jar = next(iter(glob.glob(f"{CACHE}/{quic[0]}/{quic[1]}/{quic_version}/*/{quic_jar_name}")), None)
    if quic_jar is None:
        sys.exit(f"No {quic_jar_name} in Gradle's cache; build the desktop app first")
    with zipfile.ZipFile(quic_jar) as z:
        netty_notice = z.read("META-INF/NOTICE.txt").decode("utf-8").strip("\n")
        harmony_notice = z.read("META-INF/license/NOTICE.harmony.txt").decode("utf-8").strip("\n")
    section("NOTICE: Netty (io.netty:*), as the Apache License requires", netty_notice)
    section("NOTICE: Apache Harmony (bundled by Netty)", harmony_notice)

    with open(OUT, "w") as f:
        f.write("\n".join(out) + "\n")
    print(f"Wrote {OUT}: " + ", ".join(f"{title}: {len(rows)} libraries" for title, rows in apps))


if __name__ == "__main__":
    main()
