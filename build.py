#!/usr/bin/env python3
"""Offline reproducible build against the server's supplied CoreProtect JAR. No vendor classes packaged."""
import argparse, hashlib, json, pathlib, shutil, subprocess, zipfile

ROOT = pathlib.Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument('--server', type=pathlib.Path, default=ROOT.parent)
p.add_argument('--coreprotect', type=pathlib.Path)
args = p.parse_args()
server = args.server.resolve()
vendor = args.coreprotect or server / 'mods/coreprotect-fabric-1.2.jar'
build = ROOT / 'build'
classes = build / 'classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(parents=True, exist_ok=True)
jars = sorted((server / 'libraries').rglob('*.jar'))
loader = next(j for j in jars if j.name.startswith('fabric-loader-'))
with zipfile.ZipFile(loader) as z:
    entry = next(n for n in z.namelist() if n.startswith('META-INF/jars/mixinextras-') and n.endswith('.jar'))
    (build / 'mixinextras.jar').write_bytes(z.read(entry))
extra_jars = [vendor, build / 'mixinextras.jar']
with zipfile.ZipFile(vendor) as z:
    for n in z.namelist():
        if n.startswith('META-INF/jars/jdbi3-core-') and n.endswith('.jar'):
            jdbi_path = build / 'jdbi3-core.jar'
            jdbi_path.write_bytes(z.read(n))
            extra_jars.append(jdbi_path)
cp = ':'.join(map(str, extra_jars + jars))
sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-proc:none', '-classpath', cp,
                '-d', str(classes)] + list(map(str, sources)), check=True)
shutil.copytree(ROOT / 'src/main/resources', classes, dirs_exist_ok=True)
shutil.copy2(ROOT / 'LICENSE', classes / 'LICENSE')
version = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())['version']
artifact = build / ('libs/coreprotect-meltdown-' + version + '+mc26.2-26.3.jar')
artifact.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(artifact, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in sorted(classes.rglob('*')):
        if f.is_file():
            info = zipfile.ZipInfo(f.relative_to(classes).as_posix(), (2026, 9, 17, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, f.read_bytes())
tests = sorted((ROOT / 'src/test/java').rglob('*.java'))
if tests:
    dest = build / 'test-classes'; dest.mkdir(exist_ok=True)
    test_cp = str(classes) + ':' + cp
    subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-proc:none', '-classpath', test_cp,
                    '-d', str(dest)] + list(map(str, tests)), check=True)
    subprocess.run(['java', '-ea', '-Xmx128m', '-cp', str(dest) + ':' + test_cp,
                    'kr.harusarri.coreprotectmeltdown.LimiterTest'], check=True)
metadata = {'artifact': artifact.name, 'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
            'coreprotect': vendor.name, 'coreprotect_sha256': hashlib.sha256(vendor.read_bytes()).hexdigest()}
(build / 'build-info.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
