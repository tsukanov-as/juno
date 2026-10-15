#!/usr/bin/env python3
"""Compare trusted BSL cases on isolated 1C, OneScript, juno CLI and standalone JAR."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid


def bsl_string(value):
    return '"' + str(value).replace('"', '""') + '"'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_float=Decimal)


def write_json(path, value):
    # Raw engine JSON remains in the workspace; decimal tags preserve report precision.
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2,
                              default=lambda x: {'$decimal': str(x)} if isinstance(x, Decimal)
                              else str(x)) + '\n', encoding='utf-8')


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def repo_root():
    for start in (Path(__file__).resolve(), Path.cwd().resolve()):
        for directory in start.parents if start.is_file() else (start, *start.parents):
            if (directory / 'Build.java').is_file():
                return directory
    return None


def version_key(path):
    match = re.search(r'\d+(?:\.\d+){3}', path)
    return tuple(map(int, match.group().split('.'))) if match else ()


def find_tool(explicit, env, commands=(), exact=(), patterns=()):
    """Resolve an engine: argument, environment variable, exact paths, PATH, install globs.

    Returns (path, source), (None, None) when nothing is found, or raises ValueError
    when an explicitly given path or command does not exist.
    """
    for source, value in (('argument', explicit), (env, os.environ.get(env))):
        if value:
            found = str(value) if Path(value).is_file() else shutil.which(str(value))
            if not found:
                raise ValueError(f'{source}={value} is not an existing file or command')
            return Path(found).resolve(), source
    for candidate in exact:
        if candidate and Path(candidate).is_file():
            return Path(candidate).resolve(), 'default location'
    for command in commands:
        found = shutil.which(command)
        if found:
            return Path(found).resolve(), 'PATH'
    matches = [m for pattern in patterns for m in glob.glob(pattern) if os.path.isfile(m)]
    if matches:
        return Path(max(matches, key=version_key)).resolve(), 'auto-detected install'
    return None, None


def install_dirs(*variables):
    return [os.environ[v] for v in variables if os.environ.get(v)]


def tool_lookup(root):
    windows, java = sys.platform == 'win32', 'java.exe' if sys.platform == 'win32' else 'java'
    program_files = install_dirs('ProgramFiles', 'ProgramFiles(x86)')
    platform_patterns = (
        [os.path.join(d, '1cv8', '*', 'bin', '1cv8.exe') for d in program_files] if windows
        else ['/opt/1cv8t/*/1cv8t'] if sys.platform == 'darwin'
        else ['/opt/1cv8/x86_64/*/1cv8', '/opt/1cv8/*/1cv8', '/opt/1C/v8.3/x86_64/1cv8'])
    oscript_patterns = ([os.path.join(d, 'OneScript', 'bin', 'oscript.exe') for d in program_files]
                        if windows else ['/opt/oscript/bin/oscript'])
    java_home = os.environ.get('JAVA_HOME')
    return dict(
        platform=dict(env='ONEC_PLATFORM_BIN', patterns=platform_patterns),
        oscript=dict(env='ONESCRIPT_BIN', commands=['oscript'], patterns=oscript_patterns),
        java=dict(env='JAVA_BIN', exact=[os.path.join(java_home, 'bin', java)] if java_home else [],
                  commands=['java']),
        juno=dict(env='JUNO_JAR', exact=[root / 'build' / 'juno.jar'] if root else []))


def equivalent(a, b):
    if isinstance(a, bool) or isinstance(b, bool):
        return type(a) is type(b) and a == b
    if isinstance(a, (int, Decimal)) and isinstance(b, (int, Decimal)):
        return a == b
    if type(a) is not type(b):
        return False
    if isinstance(a, list):
        return len(a) == len(b) and all(equivalent(x, y) for x, y in zip(a, b))
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(equivalent(a[k], b[k]) for k in a)
    return a == b


def core_source(suite, cases, directory):
    source = suite.get('prelude', '') + '\n'
    for i, case in enumerate(cases):
        source += f'Функция Проба{i}()\n{case["body"]}\nКонецФункции\n'
    source += '\nПроцедура ВыполнитьПробы() Экспорт\nВсе=Новый Массив;\n'
    for i, case in enumerate(cases):
        source += f'''П=Новый ТекстовыйДокумент;
П.УстановитьТекст({bsl_string(case['id'])});
П.Записать({bsl_string(directory / 'progress.txt')},КодировкаТекста.UTF8);
С=Новый Структура("id,status",{bsl_string(case['id'])},"ok");
Попытка
С.Вставить("value",Проба{i}());
Исключение С.status="error"; С.Вставить("error",ОписаниеОшибки()); КонецПопытки;
Все.Добавить(С);
З=Новый ЗаписьJSON;
З.ОткрытьФайл({bsl_string(directory / 'results.json')},"UTF-8");
ЗаписатьJSON(З,Все); З.Закрыть();
'''
    return source + 'КонецПроцедуры\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--output', type=Path,
                        help='report path; default: report.json in the temporary workspace')
    parser.add_argument('--engines', nargs='+', choices=['platform', 'oscript', 'juno', 'jar'],
                        default=['platform', 'oscript', 'juno', 'jar'])
    parser.add_argument('--platform', help='1C binary; or ONEC_PLATFORM_BIN, or an installed platform')
    parser.add_argument('--oscript', help='OneScript binary; or ONESCRIPT_BIN, PATH')
    parser.add_argument('--java', help='Java 21+ binary; or JAVA_BIN, JAVA_HOME, PATH')
    parser.add_argument('--juno', help='juno JAR; or JUNO_JAR, <repository>/build/juno.jar')
    parser.add_argument('--case', action='append', dest='case_ids')
    parser.add_argument('--timeout', type=float, default=45)
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(errors='backslashreplace')
    if len(set(args.engines)) < 2:
        parser.error('Select at least two different engines.')
    if args.timeout <= 0:
        parser.error('--timeout must be positive.')
    required = []
    if 'platform' in args.engines:
        required.append('platform')
    if 'oscript' in args.engines:
        required.append('oscript')
    if {'juno', 'jar'} & set(args.engines):
        required.extend(['java', 'juno'])
    lookup, sources = tool_lookup(repo_root()), {}
    for name in required:
        try:
            path, sources[name] = find_tool(getattr(args, name), **lookup[name])
        except ValueError as exc:
            parser.error(f'--{name}: {exc}.')
        if path is None:
            hint = ' (run `java Build.java build` first)' if name == 'juno' else ''
            parser.error(f'{name} was not found{hint}. Pass --{name} or set {lookup[name]["env"]}; '
                         'use --engines to run without it and report it as unverified.')
        setattr(args, name, path)
        print(f'{name}: {path} ({sources[name]})', flush=True)
    suite = read_json(args.cases)
    cases = suite['cases']
    if not cases or any(not re.fullmatch(r'[a-zA-Z0-9_.-]+', c['id'])
                        or not isinstance(c['body'], str) for c in cases):
        parser.error('Cases need nonempty ASCII IDs and BSL body strings.')
    if len({c['id'] for c in cases}) != len(cases):
        parser.error('Duplicate case IDs.')
    if args.case_ids:
        unknown = set(args.case_ids) - {c['id'] for c in cases}
        if unknown:
            parser.error(f'Unknown cases: {sorted(unknown)}')
        cases = [c for c in cases if c['id'] in args.case_ids]
    workspace = Path(tempfile.mkdtemp(prefix='juno-differential-'))
    report = dict(recorded_at=datetime.now(timezone.utc).isoformat(), workspace=str(workspace),
                  suite=str(args.cases.resolve()), suite_sha256=sha256(args.cases),
                  selected_cases=[c['id'] for c in cases], timeout_seconds=args.timeout,
                  error_comparison='status only; engine error text retained', tools={},
                  records=[], differences=[])
    for name in required:
        path = getattr(args, name)
        report['tools'][name] = dict(path=str(path), source=sources[name], sha256=sha256(path))
    shutil.copyfile(args.cases, workspace / 'suite.json')

    def invoke(record, label, command, cwd):
        command = [str(x) for x in command]
        stdout, stderr = cwd / (label + '.stdout.txt'), cwd / (label + '.stderr.txt')
        item = dict(phase=label, command=command, cwd=str(cwd), stdout=str(stdout), stderr=str(stderr))
        record['commands'].append(item)
        with stdout.open('w', encoding='utf-8') as out, stderr.open('w', encoding='utf-8') as err:
            try:
                result = subprocess.run(command, cwd=cwd, stdout=out, stderr=err,
                                        text=True, timeout=args.timeout)
            except subprocess.TimeoutExpired:
                item['timeout'] = True
                raise RuntimeError(f'{label}: timeout after {args.timeout}s')
        item['exit_code'] = result.returncode
        if result.returncode:
            raise RuntimeError(f'{label}: exit {result.returncode}; see command logs')

    def native(record, directory, core):
        binary, db, config = args.platform, directory / 'db', directory / 'config'

        def phase(label, *argv):
            invoke(record, label, [binary, *argv, '/DisableStartupDialogs',
                                  '/Out', directory / (label + '.platform.log')], directory)

        # The platform parses the raw Windows command line; escaped inner quotes would be literal.
        phase('create', 'CREATEINFOBASE', f'File={db};' if sys.platform == 'win32' else f'File="{db}";')
        phase('dump', 'DESIGNER', '/F', db, '/DumpConfigToFiles', config)
        cfg = config / 'Configuration.xml'
        text = cfg.read_text(encoding='utf-8-sig')
        marker = '<Language>Русский</Language>'
        if text.count(marker) != 1:
            raise RuntimeError('Unsupported configuration template; inspect Configuration.xml')
        cfg.write_text(text.replace(marker, marker + '\n<CommonModule>DifferentialProbe</CommonModule>'),
                       encoding='utf-8-sig')
        common = config / 'CommonModules'
        common.mkdir(exist_ok=True)
        (common / 'DifferentialProbe.xml').write_text('''<?xml version="1.0" encoding="UTF-8"?>
<MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="2.20">
<CommonModule uuid="%s"><Properties><Name>DifferentialProbe</Name><Synonym/><Comment/>
<Global>false</Global><ClientManagedApplication>false</ClientManagedApplication><Server>true</Server>
<ExternalConnection>false</ExternalConnection><ClientOrdinaryApplication>false</ClientOrdinaryApplication>
<ServerCall>true</ServerCall><Privileged>false</Privileged><ReturnValuesReuse>DontUse</ReturnValuesReuse>
</Properties></CommonModule></MetaDataObject>''' % uuid.uuid4(), encoding='utf-8-sig')
        ext = common / 'DifferentialProbe/Ext'
        ext.mkdir(parents=True)
        (ext / 'Module.bsl').write_text(core, encoding='utf-8-sig')
        (config / 'Ext').mkdir(exist_ok=True)
        (config / 'Ext/ManagedApplicationModule.bsl').write_text(f'''
Процедура ПриНачалеРаботыСистемы()
Попытка DifferentialProbe.ВыполнитьПробы(); Исключение
Т=Новый ТекстовыйДокумент;
Т.УстановитьТекст(ПодробноеПредставлениеОшибки(ИнформацияОбОшибке()));
Т.Записать({bsl_string(directory / 'fatal.txt')},КодировкаТекста.UTF8);
КонецПопытки;
ЗавершитьРаботуСистемы(Ложь);
КонецПроцедуры
''', encoding='utf-8-sig')
        phase('load', 'DESIGNER', '/F', db, '/LoadConfigFromFiles', config, '/UpdateDBCfg')
        phase('run', 'ENTERPRISE', '/F', db, '/DisableStartupMessages')
        if (directory / 'fatal.txt').exists():
            raise RuntimeError('Native fatal error; see fatal.txt')

    for engine in dict.fromkeys(args.engines):
        directory = workspace / engine
        directory.mkdir()
        record = dict(engine=engine, status='incomplete', commands=[], observations=[])
        report['records'].append(record)
        core = core_source(suite, cases, directory)
        # OneScript runs its own .os; juno only treats .bsl as source.
        entry = directory / ('Probe.os' if engine == 'oscript' else 'Probe.bsl')
        entry.write_text(core + '\nВыполнитьПробы();\n', encoding='utf-8')
        print(f'{engine}: {len(cases)} cases', flush=True)
        try:
            if engine == 'platform':
                native(record, directory, core)
            elif engine == 'oscript':
                invoke(record, 'version', [args.oscript, '-version'], directory)
                invoke(record, 'run', [args.oscript, entry], directory)
            else:
                invoke(record, 'version', [args.java, '-version'], directory)
                if engine == 'juno':
                    invoke(record, 'run', [args.java, '-jar', args.juno, 'run', entry], directory)
                else:
                    app = directory / 'Application.jar'
                    invoke(record, 'build', [args.java, '-jar', args.juno, 'build', entry,
                                            '--output', app], directory)
                    entry.rename(directory / 'Probe.bsl.saved')
                    launch = directory / 'launch'
                    launch.mkdir()
                    invoke(record, 'run', [args.java, '-jar', app], launch)
            observations = read_json(directory / 'results.json')
            if [c['id'] for c in observations] != report['selected_cases']:
                raise RuntimeError('Missing, repeated or reordered case observations')
            for case in observations:
                if case['status'] not in ('ok', 'error') or (
                        case['status'] == 'ok' and 'value' not in case):
                    raise RuntimeError('Invalid case result')
            record.update(status='complete', observations=observations)
        except (OSError, ValueError, KeyError, RuntimeError) as exc:
            record['failure'] = str(exc)
            progress = directory / 'progress.txt'
            if progress.exists():
                record['last_started_case'] = progress.read_text(encoding='utf-8-sig')
            partial = directory / 'results.json'
            if partial.exists():
                try:
                    record['observations'] = read_json(partial)
                except ValueError:
                    pass
    complete = [r for r in report['records'] if r['status'] == 'complete']
    for i, left in enumerate(complete):
        for right in complete[i + 1:]:
            for a, b in zip(left['observations'], right['observations']):
                if a['status'] != b['status'] or (a['status'] == 'ok'
                                                and not equivalent(a['value'], b['value'])):
                    report['differences'].append(dict(id=a['id'], engines=[left['engine'], right['engine']],
                                                     left=a, right=b))
    report['result'] = ('INCOMPLETE' if len(complete) != len(report['records']) else
                        'DIFFERENCES' if report['differences'] else 'MATCH')
    output = args.output or workspace / 'report.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    write_json(output, report)
    print(f'{report["result"]}: {output.resolve()}\nArtifacts: {workspace}', flush=True)
    return 2 if report['result'] == 'INCOMPLETE' else 1 if report['differences'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
