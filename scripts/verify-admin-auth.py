"""Stage04: real V4 migration, offline provisioning, cross-JVM revocation and prior regressions.
Run at the repository root. Only isolated MySQL schemas are modified.
"""
import argparse
import base64
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import time
from db_migrate import ROOT

spec = importlib.util.spec_from_file_location('sku_verification', ROOT / 'scripts/verify-skus.py')
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
system = previous.previous.system


class AdminVerification(previous.SkuVerification):
    def __init__(self, mysql, workers, rounds, http_only=False):
        super().__init__(mysql, workers, rounds)
        self.http_only = http_only
        self.meta['verification'] = 'stage04_independent_admin_and_full_system'
        self.admin_secret = base64.b64encode(secrets.token_bytes(32)).decode()
        self.admin_password = secrets.token_urlsafe(24) + 'A1'
        self.admin_passwords = [self.admin_password]
        self.buyer_secret = base64.b64encode(secrets.token_bytes(32)).decode()
        self.admin_tokens = []

    def start_database(self):
        if self.http_only:
            system.Verification.start_database(self)
        else:
            super().start_database()

    def build(self):
        if not self.http_only:
            super().build()
            self.verify_module_ownership()
            return
        # Explicitly requested reuse: does not claim a new compile or JUnit run.
        for module in ('product', 'user', 'order', 'admin'):
            if not (ROOT / f'xiaoke-{module}/target/xiaoke-{module}-1.0.0.jar').is_file():
                raise RuntimeError('HTTP-only requires previously verified JARs')
        self.meta['mode'] = 'full_http_using_existing_verified_jars'
        self.meta['maven_executed'] = False
        db = self.database('order')
        db.migrate(log=self.out / 'http-database-migrate.log')
        db.flyway('validate', log=self.out / 'http-database-validate.log')
        assert db.query('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank') == [
            {'version': str(v), 'success': '1'} for v in (1, 2, 3, 4)]
        self.record('http_only_fresh_flyway_database_and_explicit_jar_reuse', maven_executed=False)
        self.verify_module_ownership()

    def verify_module_ownership(self):
        import zipfile
        with zipfile.ZipFile(ROOT / 'xiaoke-common/target/xiaoke-common-1.0.0.jar') as jar:
            names = jar.namelist()
            assert not any(name.endswith(('AdminIdentityController.class', 'AdminAccountStore.class')) for name in names)
        with zipfile.ZipFile(ROOT / 'xiaoke-user/target/xiaoke-user-1.0.0.jar') as jar:
            assert not any(name.startswith('BOOT-INF/classes/com/xiaoke/user/admin/') for name in jar.namelist())
        with zipfile.ZipFile(ROOT / 'xiaoke-admin/target/xiaoke-admin-1.0.0.jar') as jar:
            names = jar.namelist()
            assert 'BOOT-INF/classes/com/xiaoke/admin/AdminApplication.class' in names
            assert 'BOOT-INF/classes/com/xiaoke/admin/controller/AdminAuthController.class' in names
            assert not any(name.startswith('BOOT-INF/classes/com/xiaoke/user/') for name in names)
        self.record('packaged_module_ownership_no_stale_admin_business_classes')

    def provision(self, mode='create', expected=0):
        env = os.environ.copy()
        env.update({'DB_HOST': '127.0.0.1', 'DB_PORT': str(self.port), 'DB_NAME': self.schemas['order'],
                    'DB_USERNAME': 'root', 'DB_PASSWORD': '', 'XIAOKE_JWT_SECRET_BASE64': self.buyer_secret,
                    'XIAOKE_ADMIN_JWT_SECRET_BASE64': self.admin_secret,
                    'XIAOKE_ADMIN_PROVISION_ENABLED': 'true', 'XIAOKE_ADMIN_PROVISION_MODE': mode,
                    'XIAOKE_ADMIN_PROVISION_USERNAME': 'acceptance_admin',
                    'XIAOKE_ADMIN_PROVISION_PASSWORD': self.admin_password})
        # Administrator maintenance runs in its own offline JAR, never in the buyer process.
        with (self.out / f'admin-provision-{mode}-{len(list(self.out.glob("admin-provision-*.log")))}.log').open('wb') as log:
            result = subprocess.run(['java', '-jar', str(ROOT / 'xiaoke-admin/target/xiaoke-admin-1.0.0.jar'),
                '--spring.main.web-application-type=none', '--spring.profiles.active=dev'],
                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=60, creationflags=system.FLAGS)
        assert (result.returncode == 0) == (expected == 0), ('provision outcome', mode, result.returncode)

    def start_apps(self):
        assert self.sql('SELECT COUNT(*) FROM t_admin_account')[0][0] == '0'
        self.provision()
        self.provision(expected=1)  # Repeat initialization must never reset an existing password.
        self.record('offline_provisioning_and_repeat_refusal', default_accounts=0, created_accounts=1)
        changes = {'XIAOKE_ADMIN_AUTH_ENABLED': 'true', 'XIAOKE_ADMIN_JWT_SECRET_BASE64': self.admin_secret}
        old = {key: os.environ.get(key) for key in changes}
        try:
            os.environ.update(changes)
            super().start_apps()
        finally:
            for key, value in old.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value
        env = self.app_specs['user'][1].copy()
        port = system.free_port()
        command = ['java', '-jar', str(ROOT / 'xiaoke-admin/target/xiaoke-admin-1.0.0.jar'),
                   '--server.port=' + str(port), '--spring.profiles.active=dev',
                   '--spring.datasource.hikari.connection-timeout=2000']
        with (self.out / 'admin.log').open('wb') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=system.FLAGS)
        self.apps.append(process)
        self.app_specs['admin'] = (command, env)
        self.urls['admin'] = f'http://127.0.0.1:{port}/api'
        import urllib.request
        import urllib.error
        end = time.monotonic() + 60
        while time.monotonic() < end:
            if process.poll() is not None:
                raise RuntimeError('Independent admin JAR exited')
            try:
                urllib.request.urlopen(self.urls['admin'] + '/probe', timeout=1)
            except urllib.error.HTTPError:
                break
            except (OSError, urllib.error.URLError):
                time.sleep(0.2)
        else:
            raise RuntimeError('Independent admin JAR startup timed out')
        self.record('five_jvms_with_separate_administrator_process', admin_module='xiaoke-admin')

    def admin_login(self, expected=200, password=None):
        result = self.call('admin', 'POST', '/admin/auth/login',
                           {'username': ' ACCEPTANCE_ADMIN ', 'password': password or self.admin_password},
                           expected=expected)[1]
        if expected == 200:
            assert result['expiresIn'] == 1800
            assert set(result['admin']) == {'id', 'username'}
            self.admin_tokens.append(result['token'])
            return result['token']

    def smoke(self):
        super().smoke()
        self.admin_login(expected=401, password='Wrong-Synthetic-123')
        token, second = self.admin_login(), self.admin_login()
        for app in ('admin',):
            identity = self.data_call(app, 'GET', '/admin/auth/me', token=token)
            assert identity == {'id': 1, 'username': 'acceptance_admin'}
            self.call(app, 'GET', '/admin/auth/me', token=self.tokens[0], expected=401)
            self.call(app, 'GET', '/admin/auth/me', token='forged.invalid.token', expected=401)
            self.call(app, 'GET', '/admin/auth/me', expected=401)
        self.call('user', 'GET', '/user/me', token=token, expected=401)
        self.call('order_a', 'GET', '/order/page', token=token, expected=401)
        self.call('product', 'POST', '/product', {}, token=token, expected=401)
        self.call('order_a', 'POST', '/order/20260101000001/ship', {}, token=token, expected=401)
        for app in ('user', 'product', 'order_a', 'order_b'):
            for method, path in (('GET','/admin/auth/me'),('POST','/admin/auth/login'),('POST','/admin/auth/logout')):
                self.call(app, method, path, token=token, expected=404)
        self.call('admin', 'GET', '/user/me', token=self.tokens[0], expected=404)
        self.record('admin_routes_owned_exclusively_by_independent_module', business_write_gate_preserved=True)
        results = self.race([lambda: self.call('admin', 'POST', '/admin/auth/logout', token=token, expected=(200,401))
                             for _ in range(self.workers)])
        assert sum(code == 200 for code, _ in results) == 1
        assert self.sql('SELECT token_version FROM t_admin_account WHERE id=1')[0][0] == '1'
        for app in ('admin',):
            for old_token in (token, second):
                self.call(app, 'GET', '/admin/auth/me', token=old_token, expected=401)
        self.record('concurrent_logout_revokes_all_administrator_sessions', version=1)
        token = self.admin_login()
        self.sql('UPDATE t_admin_account SET status=0,token_version=token_version+1 WHERE id=1')
        for app in ('admin',):
            self.call(app, 'GET', '/admin/auth/me', token=token, expected=401)
        self.admin_login(expected=401)
        self.admin_password = secrets.token_urlsafe(24) + 'B2'
        self.admin_passwords.append(self.admin_password)
        self.provision(mode='recover')
        assert self.sql('SELECT status FROM t_admin_account WHERE id=1')[0][0] == '0'
        self.admin_login(expected=401)
        self.sql('UPDATE t_admin_account SET status=1 WHERE id=1')
        fresh = self.admin_login()
        for app in ('admin',):
            self.call(app, 'GET', '/admin/auth/me', token=token, expected=401)
            self.data_call(app, 'GET', '/admin/auth/me', token=fresh)
        self.sql('UPDATE t_admin_account SET deleted=1 WHERE id=1')
        for app in ('admin',):
            self.call(app, 'GET', '/admin/auth/me', token=fresh, expected=401)
        self.sql('UPDATE t_admin_account SET deleted=0,token_version=token_version+1 WHERE id=1')
        self.record('disable_recovery_and_deletion_revoke_administrator_identity', recovery_does_not_enable=True)

    def real_service_failures(self):
        # The owning administrator application fails closed on an identity database outage.
        token = self.admin_login()
        command = self.server.args
        subprocess.run(self.client + ['--execute', 'SHUTDOWN'], capture_output=True,
                       timeout=15, check=True, creationflags=system.FLAGS)
        system.stop(self.server)
        # On Windows mysqld's launcher may exit before its worker releases ibdata1.
        # Wait for the server's own shutdown completion before reusing the datadir.
        shutdown_deadline = time.monotonic() + 30
        while time.monotonic() < shutdown_deadline:
            if 'Shutdown complete (mysqld' in (self.out / 'mysql-server.log').read_text(encoding='utf-8', errors='replace'):
                break
            time.sleep(0.2)
        else:
            raise RuntimeError('Dedicated MySQL did not finish shutdown')
        for app in ('admin',):
            self.call(app, 'GET', '/admin/auth/me', token=token, expected=503)
        self.record('admin_database_outage_fails_closed', services=1, http=503)
        with (self.out / 'mysql-restart.log').open('wb') as log:
            self.server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, creationflags=system.FLAGS)
        end = time.monotonic() + 30
        while time.monotonic() < end:
            ready = subprocess.run(self.client + ['--execute', 'SELECT 1'], capture_output=True, creationflags=system.FLAGS)
            if ready.returncode == 0:
                break
            if self.server.poll() is not None:
                raise RuntimeError('Dedicated MySQL restart exited; see mysql-restart.log')
            time.sleep(0.2)
        else:
            raise RuntimeError('Dedicated MySQL did not restart')
        # A listening MySQL port does not mean all JDBC pools have recovered yet.
        # Observe each service becoming ready before testing its subsequent writes.
        readiness = [('admin','/admin/auth/me',token), ('user','/user/me',self.tokens[0]),
                     ('product','/product/page',None), ('order_a','/order/page',self.tokens[0]),
                     ('order_b','/order/page',self.tokens[0])]
        for app, path, credential in readiness:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                status, _ = self.call(app, 'GET', path, token=credential, expected=(200, 503))
                if status == 200:
                    break
                time.sleep(0.2)
            else:
                raise RuntimeError('Service JDBC pool did not recover: ' + app)
        self.record('all_five_services_recover_after_database_restart')
        super().real_service_failures()

    def audit_logs(self):
        super().audit_logs()
        credentials = [self.admin_secret, *self.admin_passwords, self.buyer_secret, *self.admin_tokens]
        for file in self.out.glob('*.log'):
            content = file.read_text(encoding='utf-8', errors='replace')
            assert not any(secret in content for secret in credentials), ('admin credential in logs', file.name)
        self.record('admin_credentials_not_in_logs', checked_logs=len(list(self.out.glob('*.log'))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home', default=os.environ.get('XIAOKE_MYSQL_HOME', 'D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers', type=int, default=24)
    parser.add_argument('--rounds', type=int, default=8)
    parser.add_argument('--http-only', action='store_true',
                        help='Reuse previously verified JARs; run all HTTP races/faults on a fresh migrated DB, no Maven or historical migration demos')
    args = parser.parse_args()
    if Path.cwd().resolve() != ROOT:
        parser.error('Run from the repository root')
    if not 4 <= args.workers <= 48 or args.workers % 2 or not 1 <= args.rounds <= 20:
        parser.error('workers must be even 4..48, rounds 1..20')
    AdminVerification(Path(args.mysql_home).resolve(), args.workers, args.rounds, http_only=args.http_only).run()
