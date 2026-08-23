from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
MOD_ID = "DAMNPrefixGuard"
BUILD = ROOT / "build"
MAIN_CLASSES = BUILD / "classes" / "main"
TEST_CLASSES = BUILD / "classes" / "test"
JAR_PATH = BUILD / "libs" / f"{MOD_ID}.jar"
RELEASE_MOD = ROOT / "release" / MOD_ID
ZB_RELATIVE_PATH = Path(
    "steamapps/workshop/content/108600/3619862853/mods/ZombieBuddy/libs/ZombieBuddy.jar"
)
PZ_RELATIVE_PATH = Path("steamapps/common/ProjectZomboid/projectzomboid.jar")


def run(command: list[str]) -> None:
    print("+", subprocess.list2cmdline(command))
    subprocess.run(command, cwd=ROOT, check=True)


def files_under(root: Path, suffix: str) -> list[Path]:
    return sorted(path for path in root.rglob(f"*{suffix}") if path.is_file())


def steam_libraries() -> list[Path]:
    roots: list[Path] = []
    for variable in ("STEAM_LIBRARY", "STEAM_ROOT"):
        value = os.environ.get(variable)
        if value:
            roots.append(Path(value).expanduser())

    if os.name == "nt":
        try:
            import winreg

            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as key:
                roots.append(Path(winreg.QueryValueEx(key, "SteamPath")[0]))
        except (ImportError, FileNotFoundError, OSError):
            pass
        roots.extend((Path(r"C:\Program Files (x86)\Steam"), Path(r"C:\Steam")))
    elif sys.platform == "darwin":
        roots.append(Path.home() / "Library/Application Support/Steam")
    else:
        roots.extend((Path.home() / ".steam/steam", Path.home() / ".local/share/Steam"))

    discovered: list[Path] = []
    for root in roots:
        resolved = root.expanduser().resolve()
        if resolved not in discovered:
            discovered.append(resolved)
        library_file = resolved / "steamapps" / "libraryfolders.vdf"
        if library_file.is_file():
            text = library_file.read_text(encoding="utf-8", errors="replace")
            for value in re.findall(r'"path"\s+"([^"]+)"', text):
                library = Path(value.replace(r"\\", "\\")).expanduser().resolve()
                if library not in discovered:
                    discovered.append(library)
    return discovered


def locate_dependency(environment_name: str, relative_path: Path) -> Path:
    override = os.environ.get(environment_name)
    if override:
        return Path(override).expanduser().resolve()
    for library in steam_libraries():
        candidate = library / relative_path
        if candidate.is_file():
            return candidate.resolve()
    raise RuntimeError(
        f"Could not locate {relative_path}. Set {environment_name} to the exact JAR path."
    )


def zombie_buddy_jar() -> Path:
    path = locate_dependency("ZOMBIE_BUDDY_JAR", ZB_RELATIVE_PATH)
    if not path.is_file():
        raise RuntimeError(f"ZombieBuddy JAR not found: {path}")
    lock = json.loads((ROOT / "dependency-lock.json").read_text(encoding="utf-8"))
    expected = lock["zombieBuddy"]["sha256"].lower()
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(
            "ZombieBuddy JAR changed; refusing to build against an unreviewed interface. "
            f"Expected {expected}, got {actual}."
        )
    return path


def project_zomboid_jar() -> Path:
    path = locate_dependency("PROJECT_ZOMBOID_JAR", PZ_RELATIVE_PATH)
    if not path.is_file():
        raise RuntimeError(f"Project Zomboid JAR not found: {path}")
    lock = json.loads((ROOT / "dependency-lock.json").read_text(encoding="utf-8"))
    expected = lock["projectZomboid"]["sha256"].lower()
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(
            "Project Zomboid JAR changed; refusing to build against an unreviewed interface. "
            f"Expected {expected}, got {actual}."
        )
    result = subprocess.run(
        ["javap", "-classpath", str(path), "-p", "zombie.ZomboidFileSystem"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    required = (
        "public final java.util.HashMap<java.lang.String, java.lang.String> activeFileMap;",
        "public synchronized boolean isKnownFile(java.lang.String);",
        "public java.lang.String getModDir(java.lang.String);",
        "public void validatePrefix(java.lang.String);",
    )
    missing = [signature for signature in required if signature not in result.stdout]
    if missing:
        raise RuntimeError(f"Project Zomboid interface contract changed: {missing}")
    return path


def clean_classes() -> None:
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    MAIN_CLASSES.mkdir(parents=True, exist_ok=True)
    TEST_CLASSES.mkdir(parents=True, exist_ok=True)


def compile_sources() -> Path:
    zb_jar = zombie_buddy_jar()
    pz_jar = project_zomboid_jar()
    compile_classpath = os.pathsep.join((str(zb_jar), str(pz_jar)))
    clean_classes()
    main_sources = files_under(ROOT / "java" / "src" / "main" / "java", ".java")
    test_sources = files_under(ROOT / "java" / "src" / "test" / "java", ".java")
    if not main_sources or not test_sources:
        raise RuntimeError("Expected both main and test Java sources")
    run([
        "javac", "--release", "17", "-encoding", "UTF-8", "-cp", compile_classpath,
        "-d", str(MAIN_CLASSES), *map(str, main_sources),
    ])
    run([
        "javac", "--release", "17", "-encoding", "UTF-8",
        "-cp", os.pathsep.join((str(zb_jar), str(MAIN_CLASSES))),
        "-d", str(TEST_CLASSES), *map(str, test_sources),
    ])
    return zb_jar


def test() -> None:
    zb_jar = compile_sources()
    classpath = os.pathsep.join((str(zb_jar), str(MAIN_CLASSES), str(TEST_CLASSES)))
    for test_class in (
        "pzmods.damn_prefix_guard.PrefixGuardTest",
        "pzmods.damn_prefix_guard.BytecodeIntegrationTest",
    ):
        run(["java", "-ea", "-cp", classpath, test_class])


def build_jar() -> None:
    test()
    JAR_PATH.parent.mkdir(parents=True, exist_ok=True)
    entries = sorted(path for path in MAIN_CLASSES.rglob("*") if path.is_file())
    manifest = b"Manifest-Version: 1.0\r\nCreated-By: DAMNPrefixGuard build.py\r\n\r\n"
    with zipfile.ZipFile(JAR_PATH, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        info = zipfile.ZipInfo("META-INF/MANIFEST.MF", date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        jar.writestr(info, manifest)
        for path in entries:
            name = path.relative_to(MAIN_CLASSES).as_posix()
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            jar.writestr(info, path.read_bytes())
    print(f"Built {JAR_PATH} ({sha256(JAR_PATH)})")


def is_reparse_point(path: Path) -> bool:
    return path.is_symlink() or bool(getattr(os.path, "isjunction", lambda _: False)(path))


def safe_remove_tree(path: Path, parent: Path, expected_name: str = MOD_ID) -> None:
    resolved = path.resolve()
    if resolved.parent != parent.resolve() or resolved.name != expected_name:
        raise RuntimeError(f"Refusing unsafe removal target: {resolved}")
    if is_reparse_point(path):
        raise RuntimeError(f"Refusing to remove reparse point: {path}")
    if resolved.exists():
        shutil.rmtree(resolved)


def package() -> None:
    build_jar()
    release_root = RELEASE_MOD.parent
    release_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{MOD_ID}-", dir=release_root) as temporary:
        staged = Path(temporary) / MOD_ID
        versioned = staged / "42.0"
        jar_dir = versioned / "media" / "java" / "client"
        jar_dir.mkdir(parents=True)
        shutil.copy2(ROOT / "mod.info", staged / "mod.info")
        shutil.copy2(ROOT / "mod.info", versioned / "mod.info")
        shutil.copy2(JAR_PATH, jar_dir / JAR_PATH.name)
        verify_tree(staged)
        safe_remove_tree(RELEASE_MOD, release_root)
        staged.replace(RELEASE_MOD)
    verify_tree(RELEASE_MOD)
    print(f"Packaged {RELEASE_MOD}")


def expected_manifest(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): sha256(path)
        for path in sorted(path for path in root.rglob("*") if path.is_file())
    }


def verify_tree(root: Path) -> None:
    required = {
        "mod.info",
        "42.0/mod.info",
        f"42.0/media/java/client/{MOD_ID}.jar",
    }
    actual = set(expected_manifest(root))
    if actual != required:
        raise RuntimeError(f"Package manifest mismatch. Expected {sorted(required)}, got {sorted(actual)}")
    if (root / "mod.info").read_bytes() != (root / "42.0" / "mod.info").read_bytes():
        raise RuntimeError("Root and versioned mod.info differ")
    packaged_jar = root / "42.0" / "media" / "java" / "client" / f"{MOD_ID}.jar"
    if not JAR_PATH.is_file() or sha256(packaged_jar) != sha256(JAR_PATH):
        raise RuntimeError("Packaged JAR does not match the tested build artifact")


def deploy(target_root: Path) -> None:
    package()
    target_root = target_root.resolve()
    if not target_root.is_dir() or target_root.name.lower() != "mods":
        raise RuntimeError(f"Deployment root must be an existing mods directory: {target_root}")
    if is_reparse_point(target_root):
        raise RuntimeError(f"Deployment root must not be a reparse point: {target_root}")
    target = target_root / MOD_ID
    staging_parent = target_root.parent / ".codex-pzmod-staging"
    staging_parent.mkdir(exist_ok=True)
    staged = Path(tempfile.mkdtemp(prefix=f"{MOD_ID}-", dir=staging_parent)) / MOD_ID
    backup = staging_parent / f"{MOD_ID}.backup"
    try:
        shutil.copytree(RELEASE_MOD, staged)
        verify_tree(staged)
        if target.exists() and expected_manifest(target) == expected_manifest(staged):
            print(f"Deployment already current: {target}")
            return
        if backup.exists():
            safe_remove_tree(backup, staging_parent, backup.name)
        if target.exists() and is_reparse_point(target):
            raise RuntimeError(f"Deployment target must not be a reparse point: {target}")
        if target.exists():
            target.replace(backup)
        try:
            staged.replace(target)
            verify_tree(target)
        except BaseException:
            if target.exists():
                safe_remove_tree(target, target_root)
            if backup.exists():
                backup.replace(target)
            raise
        if backup.exists():
            safe_remove_tree(backup, staging_parent, backup.name)
    finally:
        temporary_root = staged.parent
        if temporary_root.exists():
            shutil.rmtree(temporary_root)
    print(f"Deployed {target}")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("test", "build", "package", "verify", "deploy"))
    parser.add_argument("--target-root", type=Path)
    args = parser.parse_args()
    if args.action == "test":
        test()
    elif args.action == "build":
        build_jar()
    elif args.action == "package":
        package()
    elif args.action == "verify":
        verify_tree(RELEASE_MOD)
        print(f"Verified {RELEASE_MOD}")
    else:
        if args.target_root is None:
            parser.error("deploy requires --target-root")
        deploy(args.target_root)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
