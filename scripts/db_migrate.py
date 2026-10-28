"""Explicit MySQL migration entry point. Run from the repository root; see sql/README.md."""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'sql/migration'
REFERENCE = ROOT / 'sql/schema-v1.json'
FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
HISTORY = 'flyway_schema_history'


def sha256(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def canonical_ddl(ddl):
    # Data changes advance this table option; it is not a structural difference.
    ddl = re.sub(r' AUTO_INCREMENT=\d+', '', ddl).strip()
    lines = ddl.splitlines()
    keys = [line.strip().rstrip(',') for line in lines
            if re.match(r'\s*(?:PRIMARY KEY|UNIQUE KEY|KEY)\s', line)]
    if keys:
        # Index declaration order has no meaning; column order WITHIN each index still does.
        lines = [line for line in lines if not re.match(r'\s*(?:PRIMARY KEY|UNIQUE KEY|KEY)\s', line)]
        # SHOW CREATE puts CHECK constraints after indexes; their final line originally has no comma.
        # Moving indexes behind them must restore the separator so the canonical DDL is executable.
        lines[-2] = lines[-2].rstrip().rstrip(',') + ','
        lines[-1:-1] = ['  ' + key + (',' if i < len(keys) - 1 else '')
                        for i, key in enumerate(sorted(keys))]
    return '\n'.join(lines)


def migration_sha256(path):
    # Git's Windows CRLF checkout must not invalidate an otherwise identical SQL file.
    return hashlib.sha256(Path(path).read_text(encoding='utf-8-sig').encode('utf-8')).hexdigest()


def reference():
    result = json.loads(REFERENCE.read_text(encoding='utf-8'))
    if result['v1_sha256'] != migration_sha256(MIGRATIONS / 'V1__initial_schema.sql'):
        raise RuntimeError('V1 differs from its reviewed baseline structure; do not rewrite applied migrations')
    structure = result['structure']
    structure['ddl'] = {name: canonical_ddl(ddl) for name, ddl in structure['ddl'].items()}
    return structure


class Database:
    def __init__(self, mysql_home=None, env=None):
        self.env = dict(os.environ if env is None else env)
        prefix = 'XIAOKE_MIGRATION_'
        required = ('HOST', 'PORT', 'DATABASE', 'USER', 'PASSWORD')
        if any(prefix + key not in self.env for key in required):
            raise RuntimeError('Explicit XIAOKE_MIGRATION_HOST/PORT/DATABASE/USER/PASSWORD are required; no business DB defaults')
        self.host, port, self.name, self.user, self.password = (self.env[prefix + k] for k in required)
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', self.host) or not re.fullmatch(r'[A-Za-z0-9_]+', self.name):
            raise RuntimeError('Host/database contains unsupported characters')
        if not self.user or self.name.lower() in ('mysql', 'sys', 'information_schema', 'performance_schema'):
            raise RuntimeError('A non-system database and explicit user are required')
        self.port = int(port)
        if not 1 <= self.port <= 65535:
            raise RuntimeError('Invalid MySQL port')
        home = mysql_home or self.env.get('XIAOKE_MYSQL_HOME')
        suffix = '.exe' if os.name == 'nt' else ''
        self.bins = {}
        for name in ('mysql', 'mysqldump'):
            path = str(Path(home) / 'bin' / (name + suffix)) if home else shutil.which(name)
            if not path or not Path(path).is_file():
                raise RuntimeError('MySQL client tools missing; set XIAOKE_MYSQL_HOME or --mysql-home')
            self.bins[name] = path

    def client_env(self):
        env = self.env.copy()
        # Do not inherit mysql login paths or put a password in the process arguments.
        env['MYSQL_PWD'] = self.password
        login_file = ROOT / 'output/.migration-no-login-file'
        if login_file.exists():
            raise RuntimeError('Reserved no-login-file path unexpectedly exists')
        env['MYSQL_TEST_LOGIN_FILE'] = str(login_file)
        return env

    def client(self, tool='mysql'):
        return [self.bins[tool], '--no-defaults', '--protocol=TCP',
                '--host=' + self.host, '--port=' + str(self.port), '--user=' + self.user,
                '--default-character-set=utf8mb4']

    def query(self, sql):
        result = subprocess.run(self.client() + ['--xml', self.name], input=sql, text=True,
                                encoding='utf-8', capture_output=True, env=self.client_env(),
                                cwd=ROOT, timeout=30, creationflags=FLAGS)
        if result.returncode:
            raise RuntimeError(f'MySQL query failed (exit {result.returncode}); check connectivity, privileges and schema')
        root = ET.fromstring(result.stdout) if result.stdout.strip() else None
        return [] if root is None else [
            {field.get('name'): field.text for field in row.findall('field')}
            for row in root.findall('row')]

    def objects(self):
        # Include non-table objects: a schema with views/routines/events is not an empty/canonical V1 schema.
        queries = {
            'tables': 'SELECT TABLE_NAME AS name, TABLE_TYPE AS kind FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME',
            'triggers': 'SELECT TRIGGER_NAME AS name FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME',
            'routines': 'SELECT ROUTINE_NAME AS name, ROUTINE_TYPE AS kind FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() ORDER BY ROUTINE_NAME',
            'events': 'SELECT EVENT_NAME AS name FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE() ORDER BY EVENT_NAME',
        }
        return {key: self.query(sql) for key, sql in queries.items()}

    def structure(self):
        objects = self.objects()
        tables = {}
        for item in objects['tables']:
            name = item['name']
            if name == HISTORY:
                continue
            if not re.fullmatch(r'[A-Za-z0-9_]+', name):
                raise RuntimeError('Unexpected object name; refusing schema adoption')
            if item['kind'] == 'BASE TABLE':
                tables[name] = canonical_ddl(self.query(f'SHOW CREATE TABLE `{name}`')[0]['Create Table'])
        objects['tables'] = [row for row in objects['tables'] if row['name'] != HISTORY]
        return {'objects': objects, 'ddl': tables}

    def check_v1(self):
        expected, actual = reference(), self.structure()
        if actual != expected:
            different = sorted(name for name in set(actual['ddl']) | set(expected['ddl'])
                               if actual['ddl'].get(name) != expected['ddl'].get(name))
            raise RuntimeError('V1 structure mismatch; baseline refused. Check tables/columns/indexes/collations/constraints and extra objects. Tables: '
                               + ', '.join(different))
        print('V1 structure matches: seven business tables, columns, indexes and table options.')

    def has_history(self):
        return any(item['name'] == HISTORY for item in self.objects()['tables'])

    def backup(self):
        out = ROOT / 'output/database-backups'
        out.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
        file = out / f'{self.name}-{stamp}-{uuid.uuid4().hex[:8]}.sql'
        before = self.structure()
        # Maintenance mode is required by the operator; single-transaction protects InnoDB data snapshots.
        with file.open('xb') as stream:
            result = subprocess.run(self.client('mysqldump') + [
                '--single-transaction', '--routines', '--events', '--triggers',
                '--hex-blob', '--no-tablespaces', '--set-gtid-purged=OFF', self.name],
                stdout=stream, stderr=subprocess.PIPE, env=self.client_env(),
                cwd=ROOT, timeout=300, creationflags=FLAGS)
        if result.returncode or not file.stat().st_size:
            raise RuntimeError(f'Backup failed; no migration performed. Partial backup: {file}')
        if before != self.structure():
            raise RuntimeError('Schema changed during backup; stop other DDL before retrying')
        receipt = {'source': {'host': self.host, 'port': self.port, 'database': self.name},
                   'sha256': sha256(file), 'structure': before}
        file.with_suffix('.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Backup and SHA-256 receipt: ' + str(file.relative_to(ROOT)))
        return file

    def flyway(self, goal, log=None, locations=None, allow_pending=False):
        if goal not in ('migrate', 'baseline', 'validate', 'info'):
            raise RuntimeError('Only migrate/baseline/validate/info are supported; no clean or automatic repair')
        env = {key: value for key, value in self.env.items() if not key.upper().startswith('FLYWAY_')}
        env.update({
            'FLYWAY_URL': f'jdbc:mysql://{self.host}:{self.port}/{self.name}?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true',
            'FLYWAY_USER': self.user, 'FLYWAY_PASSWORD': self.password,
            'FLYWAY_LOCATIONS': 'filesystem:' + (locations or MIGRATIONS).as_posix(),
            'FLYWAY_CLEAN_DISABLED': 'true', 'FLYWAY_BASELINE_ON_MIGRATE': 'false',
            'FLYWAY_BASELINE_VERSION': '1', 'FLYWAY_CREATE_SCHEMAS': 'false',
            'FLYWAY_VALIDATE_ON_MIGRATE': 'true', 'FLYWAY_OUT_OF_ORDER': 'false',
            'FLYWAY_IGNORE_MIGRATION_PATTERNS': '*:pending' if allow_pending else '',
        })
        # -N confines the plugin to the root POM. Credentials stay in the child environment.
        mvn = shutil.which('mvn')
        if not mvn:
            raise RuntimeError('Maven is not on PATH')
        command = [mvn, '-N', '-Pdb-migration', '-B', '-ntp', 'flyway:' + goal]
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=600,
                                creationflags=FLAGS)
        output = result.stdout + result.stderr
        if self.password:
            output = output.replace(self.password, '[REDACTED]')
        if log:
            Path(log).write_text(output, encoding='utf-8')
        else:
            print(output)
        if result.returncode:
            raise RuntimeError(f'Flyway {goal} failed (exit {result.returncode}); inspect the log; do not blindly repair/retry DDL')

    def baseline(self, log=None):
        if self.has_history():
            raise RuntimeError('History already exists; baseline is only for a reviewed legacy V1 database')
        self.check_v1()
        backup = self.backup()  # Failure stops before writing the history table.
        self.check_v1()
        self.flyway('baseline', log=log)
        self.check_v1()
        return backup

    def migrate(self, log=None, locations=None):
        objects = self.objects()
        if any(objects.values()) and not self.has_history():
            raise RuntimeError('Non-empty unmanaged database: run check-v1, then explicit baseline with backup; automatic adoption is disabled')
        # Validate before backup or DDL. migrate validates again under Flyway's history lock.
        if self.has_history():
            history = self.query('SELECT version,success FROM flyway_schema_history')
            if history and all(row['version'] == '1' and row['success'] == '1' for row in history):
                # A history table alone does not prove this is a known mall schema.
                self.check_v1()
            self.flyway('validate', log=Path(log).with_suffix('.validate.log') if log else None,
                        locations=locations, allow_pending=True)
            self.backup()
        self.flyway('migrate', log=log, locations=locations)
        # V3 DDL can commit before the copy finishes. A repeated maintenance run resumes, never resets counters.
        from sku_backfill import finish
        finish(self)

    def restore(self, file):
        file = Path(file).resolve()
        receipt = json.loads(file.with_suffix('.json').read_text(encoding='utf-8'))
        if sha256(file) != receipt['sha256']:
            raise RuntimeError('Backup SHA-256 mismatch; restore refused')
        if any(self.objects().values()):
            raise RuntimeError('Restore target must be an explicitly selected EMPTY database; no automatic drop/overwrite')
        with file.open('rb') as source:
            result = subprocess.run(self.client() + [self.name], stdin=source,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                    env=self.client_env(), cwd=ROOT, timeout=300, creationflags=FLAGS)
        if result.returncode:
            raise RuntimeError('Restore failed; target may be partially restored. Inspect before doing anything else')
        if self.structure() != receipt['structure']:
            raise RuntimeError('Restored structure differs from backup receipt')
        print('Restore completed; structure matches receipt. Validate history and business data before switching applications.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('check-v1', 'migrate', 'baseline', 'validate', 'info', 'backup', 'restore'))
    parser.add_argument('--mysql-home')
    parser.add_argument('--maintenance', action='store_true', help='Confirm writers/services and other DDL operators have been stopped')
    parser.add_argument('--backup-file', type=Path, help='SQL backup with matching .json receipt, for restore only')
    args = parser.parse_args()
    if Path.cwd().resolve() != ROOT:
        parser.error('Run from the repository root')
    if args.command in ('baseline', 'migrate', 'restore', 'backup') and not args.maintenance:
        parser.error('Stop business writers and other DDL operators, then explicitly pass --maintenance')
    if args.command == 'restore' and not args.backup_file:
        parser.error('restore requires --backup-file')
    db = Database(args.mysql_home)
    if args.command == 'check-v1':
        db.check_v1()
    elif args.command == 'restore':
        db.restore(args.backup_file)
    elif args.command in ('validate', 'info'):
        db.flyway(args.command)
        if args.command == 'validate' and any(row['name']=='t_catalog_migration' for row in db.objects()['tables']):
            from sku_backfill import require_ready
            require_ready(db)
            if not db.query('SELECT version FROM t_catalog_migration WHERE version=3'):
                raise RuntimeError('V3 default SKU backfill has not completed; rerun migrate --maintenance')
    else:
        getattr(db, args.command)()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
