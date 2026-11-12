from pathlib import Path
import argparse
import glob
import json
import os
import platform
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
parser.add_argument('--kotlin-lib', required=True, help='Kotlin/Gradle lib directory containing compiler and stdlib jars')
parser.add_argument('--cargo', default='cargo')
parser.add_argument('--no-rust', action='store_true')
parser.add_argument('--bench', action='store_true')
args = parser.parse_args()
if not args.java_home:
    parser.error('set JAVA_HOME or --java-home')
root = Path(__file__).resolve().parent
repo = root.parents[1]
java_home = Path(args.java_home)
suffix = '.exe' if os.name == 'nt' else ''
java, javac = [str(java_home / 'bin' / (name + suffix)) for name in ('java','javac')]
environment = os.environ.copy()
if os.name == 'nt':
    environment['CXXFLAGS'] = environment.get('CXXFLAGS','') + ' /Zc:__cplusplus'
results = root / 'results'
results.mkdir(exist_ok=True)

def run(command, log):
    with (results / log).open('wb') as output:
        result = subprocess.run(command, cwd=root, env=environment, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print((results/log).read_text(encoding='utf-8',errors='replace')[-7000:])
        raise SystemExit(result.returncode)

if not args.no_rust:
    run([args.cargo,'test','--release','--locked'], 'rust-tests.log')
    run([args.cargo,'build','--release','--locked'], 'rust-build.log')
version = subprocess.check_output([java,'-version'],stderr=subprocess.STDOUT).decode('utf-8',errors='replace')
major = int(version.split('"')[1].split('.')[0])
if major < 21:
    parser.error('JDK 21 or newer is required')
classes = root / 'build' / ('java' + str(major))
classes.mkdir(parents=True, exist_ok=True)
lib = Path(args.kotlin_lib)
stdlib = glob.glob(str(lib/'kotlin-stdlib-*.jar'))
if not stdlib:
    parser.error('no kotlin-stdlib jar found under --kotlin-lib')
classpath = os.pathsep.join([str(classes),*stdlib])
run([java,'-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
     '-no-stdlib','-no-reflect','-jdk-home',str(java_home),'-jvm-target','21',
     '-classpath',classpath,'-d',str(classes),
     str(repo/'src/main/kotlin/com/alananasss/kittytune/audio/TimeStretcher.kt')], 'kotlin-compile.log')
preview = ['--enable-preview'] if major == 21 else []
run([javac,*preview,'--release',str(major),'-cp',classpath,'-d',str(classes),
     *map(str,sorted((root/'java').glob('*.java')))], 'java-compile-' + str(major) + '.log')
library_name = 'kittytune_stretch_poc.dll' if os.name == 'nt' else ('libkittytune_stretch_poc.dylib' if sys.platform == 'darwin' else 'libkittytune_stretch_poc.so')
command = [java,*preview,'--enable-native-access=ALL-UNNAMED','-Xms256m','-Xmx256m',
           '-Dkittytune.stretch.library='+str(root/'target/release'/library_name),'-cp',classpath,'poc.StretchProbe']
run(command,'java-checks-'+str(major)+'.log')
if args.bench:
    run([*command,'bench'],'benchmark-'+str(major)+'.csv')
(results/('environment-'+str(major)+'.json')).write_text(json.dumps({
    'java':version,'os':platform.platform(),'machine':platform.machine(),
    'processor':platform.processor(),'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo).decode().strip(),
    'note':'Different DSP algorithms. Wall times include copies/conversion/FFM. JVM allocation excludes native allocation.'
},indent=2),encoding='utf-8')
print('passed on JDK',major,'-',results)
