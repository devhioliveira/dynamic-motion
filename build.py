"""Build the LSPosed module from the supported, locally supplied APK."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from urllib.request import urlopen
from zipfile import ZIP_DEFLATED, ZipFile

from adapt_renderer import adapt_renderer

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "build"
CACHE = OUT / "tools"
BASE_SHA256 = "682fef4c47d0a9b2069bd04bfb7a1485386b4481501b09844e5e7c668d1b6313"
ENTRY = "io.dynamicmotion.zoryx.nowbar.NowBarModule"
OUTPUT_NAME = "Dynamic-Motion-3.0-Native-LSPosed.apk"
SDK_TOOLS_VERSION = "36.0.0"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def java_executable(command):
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (command + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return candidate
    path = shutil.which(command)
    if path is None:
        raise ValueError(f"{command} indisponível. Instale o JDK 21 e configure JAVA_HOME.")
    return path


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare_dependencies():
    CACHE.mkdir(parents=True, exist_ok=True)
    dependencies = json.loads((ROOT / "build-tools.json").read_text(encoding="utf-8"))
    for dep in dependencies:
        target = CACHE / dep["file"]
        if target.is_file() and digest(target) == dep["sha256"]:
            continue
        download = target.with_suffix(".download")
        try:
            with urlopen(dep["url"], timeout=60) as response, download.open("wb") as file:
                shutil.copyfileobj(response, file)
            if digest(download) != dep["sha256"]:
                raise ValueError(f"SHA-256 inesperado para {target.name}")
            download.replace(target)
        finally:
            download.unlink(missing_ok=True)


def patch_dex(apk, temp, java):
    original_dex = temp / "original.dex"
    with ZipFile(apk) as base:
        original_dex.write_bytes(base.read("classes.dex"))
    smali = temp / "smali"
    run(java, "-cp", CACHE / "*", "org.jf.baksmali.Main", "disassemble", "--api", "28",
        "-j", "4", "-o", smali, original_dex)
    adapt_renderer(smali)
    patched = temp / "renderer.dex"
    run(java, "-cp", CACHE / "*", "org.jf.smali.Main", "assemble", "--api", "28",
        "-j", "4", "-o", patched, smali)
    return patched


def compile_module(temp, java, android, tools):
    ecj = [java, "-jar", CACHE / "ecj.jar", "-1.8", "-proc:none"]
    stub_classes = temp / "stubs"
    stub_sources = sorted((ROOT / "stubs").rglob("*.java"))
    run(*ecj, "-classpath", android, "-d", stub_classes, *stub_sources)
    stubs = temp / "renderer-stubs.jar"
    with ZipFile(stubs, "w", ZIP_DEFLATED) as jar:
        for class_file in sorted(stub_classes.rglob("*.class")):
            jar.write(class_file, class_file.relative_to(stub_classes).as_posix())
    classes = temp / "classes"
    xposed = CACHE / "xposed-api.jar"
    module_sources = sorted((ROOT / "src").rglob("*.java"))
    classpath = os.pathsep.join(map(str, (android, xposed, stubs)))
    run(*ecj, "-classpath", classpath, "-d", classes, *module_sources)
    dex = temp / "dex"
    dex.mkdir()
    run(java, "-cp", tools / "lib" / "d8.jar", "com.android.tools.r8.D8",
        "--min-api", "35", "--lib", android, "--classpath", xposed,
        "--classpath", stubs, "--output", dex, *sorted(classes.rglob("*.class")))
    return dex / "classes.dex"


def package_apk(apk, manifest_apk, renderer, module, dest):
    with ZipFile(apk) as base, ZipFile(manifest_apk) as manifest, ZipFile(dest, "w") as result:
        for item in base.infolist():
            name = item.filename
            if name in {"AndroidManifest.xml", "classes.dex"} or name.startswith("assets/dexopt/"):
                continue
            if name.startswith("META-INF/") and name.upper().endswith(
                    (".RSA", ".DSA", ".EC", ".SF", "/MANIFEST.MF")):
                continue
            result.writestr(item, base.read(item))
        result.writestr("AndroidManifest.xml", manifest.read("AndroidManifest.xml"), ZIP_DEFLATED)
        result.writestr("classes.dex", renderer.read_bytes(), ZIP_DEFLATED)
        result.writestr("classes2.dex", module.read_bytes(), ZIP_DEFLATED)
        result.writestr("assets/xposed_init", ENTRY + "\n", ZIP_DEFLATED)


def sign_apk(temp, java, keytool, tools, zipalign, unsigned):
    aligned = temp / "aligned.apk"
    run(zipalign, "-f", "-P", "16", "4", unsigned, aligned)
    key = ROOT / ".signing" / "dynamic-motion.p12"
    key.parent.mkdir(exist_ok=True, mode=0o700)
    if not key.exists():
        run(keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-storepass", "android", "-keypass", "android", "-alias", "dynamic-motion",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Dynamic Motion Local Test Build")
    signed = temp / OUTPUT_NAME
    signer = [java, "-jar", tools / "lib" / "apksigner.jar"]
    run(*signer, "sign", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", "--ks", key,
        "--ks-pass", "pass:android", "--ks-key-alias", "dynamic-motion", "--out", signed, aligned)
    run(*signer, "verify", "--verbose", signed)
    run(zipalign, "-c", "-P", "16", "4", signed)
    signed.replace(OUT / OUTPUT_NAME)


def main():
    parser = argparse.ArgumentParser(description="Compila o Dynamic Motion 3.0 para LSPosed.")
    parser.add_argument("apk", type=Path, help="Caminho do APK original Dynamic Motion 2.0")
    parser.add_argument("--sdk", type=Path, help="Caminho do Android SDK (ou use ANDROID_HOME)")
    args = parser.parse_args()
    apk = args.apk.expanduser().resolve(strict=True)
    if digest(apk) != BASE_SHA256:
        raise ValueError("O APK de entrada não corresponde ao Dynamic Motion 2.0 suportado.")

    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Informe o Android SDK com --sdk ou ANDROID_HOME.")
    sdk = Path(sdk).expanduser().resolve()
    android = sdk / "platforms" / "android-35" / "android.jar"
    tools = sdk / "build-tools" / SDK_TOOLS_VERSION
    exe = ".exe" if os.name == "nt" else ""
    aapt = tools / ("aapt2" + exe)
    zipalign = tools / ("zipalign" + exe)
    for path in (android, tools / "lib" / "d8.jar", tools / "lib" / "apksigner.jar", aapt, zipalign):
        if not path.is_file():
            raise ValueError(f"Arquivo não encontrado: {path}. Instale Android SDK 35 e Build-Tools 36.0.0.")

    java = java_executable("java")
    keytool = java_executable("keytool")
    prepare_dependencies()
    with TemporaryDirectory(prefix="motion-", dir=OUT) as directory:
        temp = Path(directory)
        renderer = patch_dex(apk, temp, java)
        module = compile_module(temp, java, android, tools)
        manifest = temp / "manifest.apk"
        run(aapt, "link", "-I", android, "--manifest", ROOT / "AndroidManifest.xml", "-o", manifest)
        unsigned = temp / "unsigned.apk"
        package_apk(apk, manifest, renderer, module, unsigned)
        sign_apk(temp, java, keytool, tools, zipalign, unsigned)
    print(f"APK gerado em {OUT / OUTPUT_NAME}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"Erro no build: {error}")
