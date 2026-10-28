"""Full isolated acceptance: clean Maven verify + real HTTP races across two order JVMs.
Run at repository root: python scripts/verify-system.py
Only Python stdlib and the project's existing Java/Maven/MySQL installation are used.
"""
import argparse
import base64
import http.client
import http.server
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


class Verification:
    def __init__(self, mysql, workers, rounds):
        stamp = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
        self.out = ROOT / 'output' / 'final-verification' / (stamp + '-' + uuid.uuid4().hex[:6])
        self.out.mkdir(parents=True)
        self.mysql = mysql
        self.workers, self.rounds = workers, rounds
        suffix = uuid.uuid4().hex[:12]
        self.schemas = {m: f'xiaoke_{m}_verify_{suffix}' for m in ('common', 'user', 'product', 'order', 'admin')}
        self.port = free_port()
        assert self.port != 3306
        self.client = [str(mysql / 'bin/mysql.exe'), '--no-defaults', '--protocol=TCP',
                       '--host=127.0.0.1', '--port=' + str(self.port), '--user=root',
                       '--batch', '--skip-column-names', '--default-character-set=utf8mb4']
        self.data = Path(tempfile.mkdtemp(prefix='xiaoke-final-mysql-')).resolve()
        self.server = None
        self.apps = []
        self.app_specs = {}
        self.urls = {}
        self.key = secrets.token_urlsafe(32)
        self.tokens = []
        self.initial_stock = {}
        self.checks = []
        self.results = []
        self.lock = threading.Lock()
        self.meta = {'started_at': datetime.now(timezone(timedelta(hours=8))).isoformat(),
                     'existing_database_modified': False, 'schemas': self.schemas,
                     'workers': workers, 'rounds': rounds, 'completed': False}

    def sql(self, query, schema=None):
        schema = schema or self.schemas['order']
        if schema not in self.schemas.values() or not schema.startswith('xiaoke_'):
            raise RuntimeError('Refusing a non-verification schema')
        result = subprocess.run(self.client + [schema], input=query, text=True, encoding='utf-8',
                                capture_output=True, timeout=15, check=True, creationflags=FLAGS)
        return [line.split('\t') for line in result.stdout.strip().splitlines() if line]

    def start_database(self):
        with (self.out / 'mysql-initialize.log').open('wb') as log:
            subprocess.run([str(self.mysql / 'bin/mysqld.exe'), '--no-defaults', '--initialize-insecure',
                '--basedir=' + str(self.mysql), '--datadir=' + str(self.data), '--console'],
                stdout=log, stderr=subprocess.STDOUT, timeout=90, check=True, creationflags=FLAGS)
        with (self.out / 'mysql-server.log').open('wb') as log:
            self.server = subprocess.Popen([str(self.mysql / 'bin/mysqld.exe'), '--no-defaults',
                '--basedir=' + str(self.mysql), '--datadir=' + str(self.data), '--port=' + str(self.port),
                '--bind-address=127.0.0.1', '--mysqlx=OFF', '--skip-log-bin',
                '--innodb-buffer-pool-size=128M', '--innodb-redo-log-capacity=32M', '--console'],
                stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        end = time.monotonic() + 50
        while time.monotonic() < end:
            ready = subprocess.run(self.client + ['--execute', 'SELECT 1'], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, creationflags=FLAGS)
            if ready.returncode == 0:
                break
            if self.server.poll() is not None:
                raise RuntimeError('Isolated MySQL exited')
            time.sleep(0.2)
        else:
            raise RuntimeError('Isolated MySQL did not become ready')
        for schema in self.schemas.values():
            subprocess.run(self.client + ['--execute', 'CREATE DATABASE ' + schema +
                ' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'],
                check=True, capture_output=True, creationflags=FLAGS)
        print('Isolated MySQL ready; original port 3306 is never used.', flush=True)

    def build(self):
        env = os.environ.copy()
        env.update({'XIAOKE_RUN_DB_TESTS': 'true', 'DB_USERNAME': 'root', 'DB_PASSWORD': ''})
        names = {'common': 'XIAOKE_TEST_DB_URL', 'user': 'XIAOKE_USER_TEST_DB_URL',
                 'product': 'XIAOKE_PRODUCT_TEST_DB_URL', 'order': 'XIAOKE_ORDER_TEST_DB_URL', 'admin': 'XIAOKE_ADMIN_TEST_DB_URL'}
        for module, variable in names.items():
            env[variable] = f'jdbc:mysql://127.0.0.1:{self.port}/{self.schemas[module]}?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
            if module != 'common':
                env['XIAOKE_' + module.upper() + '_DB_TESTS'] = 'true'
        with (self.out / 'maven-clean-verify.log').open('wb') as log:
            result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                'mvn -B -ntp clean verify'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                timeout=600, creationflags=FLAGS)
        self.meta['maven_exit_code'] = result.returncode
        if result.returncode:
            raise RuntimeError('Maven clean verify failed; see maven-clean-verify.log')
        reports = {}
        cases = []
        for module in ('common', 'product', 'user', 'order', 'admin'):
            counts = {k: 0 for k in ('tests', 'failures', 'errors', 'skipped')}
            for file in (ROOT / f'xiaoke-{module}/target/surefire-reports').glob('TEST-*.xml'):
                node = ET.parse(file).getroot()
                for key in counts:
                    counts[key] += int(node.get(key))
                for case in node.findall('testcase'):
                    cases.append({'class': case.get('classname'), 'name': case.get('name'),
                                  'seconds': case.get('time'), 'skipped': case.find('skipped') is not None})
            assert counts['tests'] and not (counts['failures'] or counts['errors'] or counts['skipped']), (module, counts)
            reports[module] = counts
        self.meta['maven_tests'] = reports
        (self.out / 'test-cases.json').write_text(json.dumps(cases, indent=2), encoding='utf-8')
        print('Clean verify passed, all real DB gates enabled: ' +
              str(sum(v['tests'] for v in reports.values())) + ' cases.', flush=True)

    def start_apps(self):
        # All app instances share only the fresh order verification schema.
        self.sql('DELETE FROM t_product_sku; DELETE FROM t_order_idempotency; DELETE FROM t_order_item; DELETE FROM t_orders; DELETE FROM t_product_attr; '
                 'DELETE FROM t_product; DELETE FROM t_category; DELETE FROM t_address; DELETE FROM t_user;')
        env = os.environ.copy()
        env.update({'DB_HOST': '127.0.0.1', 'DB_PORT': str(self.port), 'DB_NAME': self.schemas['order'],
                    'DB_USERNAME': 'root', 'DB_PASSWORD': '', 'LOCAL_ADMIN_KEY': self.key,
                    'XIAOKE_JWT_SECRET_BASE64': base64.b64encode(secrets.token_bytes(32)).decode()})
        for name, module in (('product', 'product'), ('user', 'user'), ('order_a', 'order'), ('order_b', 'order')):
            port = free_port()
            if module == 'order':
                env['PRODUCT_BASE_URL'] = self.urls['product']
            with (self.out / (name + '.log')).open('wb') as log:
                command = ['java', '-jar', str(ROOT / f'xiaoke-{module}/target/xiaoke-{module}-1.0.0.jar'),
                    '--server.port=' + str(port), '--spring.profiles.active=dev', '--xiaoke.local-admin-enabled=true',
                    '--spring.datasource.hikari.connection-timeout=2000']
                self.app_specs[name] = (command, env.copy())
                process = subprocess.Popen(command,
                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
            self.apps.append(process)
            self.urls[name] = f'http://127.0.0.1:{port}/api'
            end = time.monotonic() + 60
            while time.monotonic() < end:
                if process.poll() is not None:
                    raise RuntimeError('Service exited: ' + name)
                try:
                    urllib.request.urlopen(self.urls[name] + '/probe', timeout=1)
                except urllib.error.HTTPError:
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(0.2)
            else:
                raise RuntimeError('Service startup timed out: ' + name)
        print('Product, user and two independent order JVMs ready.', flush=True)

    def call(self, app, method, path, body=None, token=None, admin=False, expected=None, headers=None):
        headers = dict(headers or {})
        if token:
            headers['Authorization'] = token
        if admin:
            headers['X-Local-Admin-Key'] = self.key
        payload = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request(self.urls[app] + path, data=payload, headers=headers, method=method)
        start = time.monotonic()
        try:
            response = urllib.request.urlopen(req, timeout=25)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            value = json.loads(response.read(), parse_float=Decimal)
            status = response.status
            trace = response.headers.get('X-Trace-Id', '')
        elapsed = round((time.monotonic() - start) * 1000, 2)
        assert status == value['code'] and len(trace) == 32, (app, path, status)
        if status != 200:
            assert value['data'] is None
        if expected is not None:
            allowed = (expected,) if isinstance(expected, int) else expected
            assert status in allowed, (app, method, path, status, value.get('message'))
        with self.lock:
            self.checks.append({'app': app, 'method': method, 'path': path, 'http': status,
                                'elapsed_ms': elapsed, 'trace_id': trace})
        return status, value['data']

    def data_call(self, *args, **kwargs):
        kwargs['expected'] = 200
        return self.call(*args, **kwargs)[1]

    def race(self, jobs):
        barrier = threading.Barrier(len(jobs))
        def run(job):
            barrier.wait(timeout=15)
            return job()
        with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
            futures = [pool.submit(run, job) for job in jobs]
            return [f.result(timeout=40) for f in futures]

    def record(self, name, **values):
        self.results.append({'scenario': name, 'passed': True, **values})
        print('PASS ' + name + ': ' + json.dumps(values), flush=True)

    def product(self, stock=20, category=None):
        name = 'verify_' + uuid.uuid4().hex[:14]
        body = {'name': name, 'price': 12.34, 'stock': stock}
        if category is not None:
            body['categoryId'] = category
        self.data_call('product', 'POST', '/product', body, admin=True)
        page = self.data_call('product', 'GET', '/product/page?' + urllib.parse.urlencode({'name': name}))
        assert page['total'] == 1
        pid = page['records'][0]['id']
        self.initial_stock[pid] = stock
        return pid

    def category(self):
        name = 'cat_' + uuid.uuid4().hex[:14]
        self.data_call('product', 'POST', '/category', {'name': name}, admin=True)
        return next(c['id'] for c in self.data_call('product', 'GET', '/category/list') if c['name'] == name)

    def order(self, pid, buyer=0, quantity=1, address=None):
        body = {'items': [{'productId': pid, 'quantity': quantity}]}
        if address is None:
            body.update(receiverName='Verifier', receiverPhone='000', receiverAddress='Isolated road')
        else:
            body['addressId'] = address
        return body

    def counters(self, pid):
        return tuple(map(int, self.sql('SELECT COALESCE(SUM(stock),0),COALESCE(SUM(sales),0) FROM t_product_sku WHERE product_id=' + str(pid))[0]))

    def actors(self):
        for i in range(self.workers):
            name = 'final_buyer_' + str(i)
            self.data_call('user', 'POST', '/user/register', {'username': name, 'password': 'isolated123'})
            token = self.data_call('user', 'POST', '/user/login', {'username': name, 'password': 'isolated123'})['token']
            self.tokens.append(token)
        self.record('registered_isolated_buyers', buyers=len(self.tokens))

    def smoke(self):
        token = self.tokens[0]
        pid = self.product()
        no = self.data_call('order_a', 'POST', '/order', self.order(pid), token)['orderNo']
        assert self.data_call('order_b', 'GET', '/order/page', token=token)['total'] >= 1
        assert self.data_call('order_b', 'GET', '/order/' + no, token=token)['totalAmount'] == Decimal('12.34')
        assert self.data_call('order_a', 'GET', '/order/' + no + '/status', token=token)['status'] == 0
        self.call('order_b', 'GET', '/order/' + no, token=self.tokens[1], expected=404)
        self.data_call('order_a', 'POST', '/order/' + no + '/pay', token=token)
        self.call('order_b', 'POST', '/order/' + no + '/ship',
                  {'logisticsCompany': 'Verifier', 'trackingNo': 'SMOKE'}, expected=401)
        self.data_call('order_b', 'POST', '/order/' + no + '/ship',
                       {'logisticsCompany': 'Verifier', 'trackingNo': 'SMOKE'}, admin=True)
        self.call('order_a', 'POST', '/order/' + no + '/cancel', token=token, expected=409)
        self.record('seven_order_endpoints_and_owner', stock=list(self.counters(pid)))

    def registration_and_defaults(self):
        name = 'final_same_username'
        results = self.race([lambda i=i: self.call('user', 'POST', '/user/register',
            {'username': name.upper() if i % 2 else name, 'password': 'isolated123'}, expected=(200, 400))
            for i in range(self.workers)])
        counts = Counter(r[0] for r in results)
        assert counts[200] == 1 and counts[400] == self.workers - 1, counts
        assert self.sql("SELECT COUNT(*) FROM t_user WHERE username='final_same_username'")[0][0] == '1'
        token = self.tokens[0]
        self.race([lambda i=i: self.call('user', 'POST', '/address',
            {'receiverName': 'A' + str(i), 'receiverPhone': '000', 'detailAddress': 'Road ' + str(i), 'isDefault': True},
            token, expected=200) for i in range(self.workers)])
        addresses = self.data_call('user', 'GET', '/address/list', token=token)
        assert len(addresses) == self.workers and sum(a['isDefault'] for a in addresses) == 1
        self.record('same_username_and_default_addresses', registration=dict(counts), active_defaults=1)

    def last_stock(self):
        available = max(2, self.workers // 3)
        pid = self.product(available)
        results = self.race([lambda i=i: self.call('order_a' if i % 2 else 'order_b', 'POST', '/order',
            self.order(pid), self.tokens[i], expected=(200, 409)) for i in range(self.workers)])
        counts = Counter(r[0] for r in results)
        assert counts[200] == available and counts[409] == self.workers - available, counts
        assert self.counters(pid) == (0, available)
        numbers = [r[1]['orderNo'] for r in results if r[0] == 200]
        assert len(set(numbers)) == len(numbers)
        self.record('two_jvms_last_stock', concurrent_buyers=self.workers, initial_stock=available, responses=dict(counts))

    def pay_cancel(self):
        pid = self.product(3)
        no = self.data_call('order_a', 'POST', '/order', self.order(pid, quantity=2), self.tokens[0])['orderNo']
        distributions = {}
        for action in ('pay', 'cancel'):
            results = self.race([lambda i=i: self.call('order_a' if i % 2 else 'order_b', 'POST',
                f'/order/{no}/{action}', token=self.tokens[0], expected=(200, 409)) for i in range(self.workers)])
            counts = Counter(r[0] for r in results)
            assert counts[200] == 1 and counts[409] == self.workers - 1, counts
            distributions[action] = dict(counts)
        assert self.counters(pid) == (3, 0)
        self.record('duplicate_pay_cancel_across_two_jvms', responses=distributions, restored_once=True)

    def distinct_product_orders(self):
        products = [self.product(2) for _ in range(self.workers)]
        results = self.race([lambda i=i: self.call('order_a' if i % 2 else 'order_b', 'POST', '/order',
            self.order(products[i]), self.tokens[i], expected=(200, 409)) for i in range(self.workers)])
        numbers = [data['orderNo'] for code, data in results if code == 200]
        assert numbers and len(numbers) == len(set(numbers))
        for pid, (code, _) in zip(products, results):
            assert self.counters(pid) == ((1, 1) if code == 200 else (2, 0))
            rows = self.sql('SELECT COUNT(*) FROM t_order_item WHERE product_id=' + str(pid))
            assert int(rows[0][0]) == (1 if code == 200 else 0)
        self.record('two_jvms_distinct_products_order_numbers', requests=self.workers,
                    responses=dict(Counter(code for code, _ in results)), unique_numbers=len(numbers),
                    failed_attempts_fully_rolled_back=True)

    def lost_committed_response(self, idempotency_key=None):
        # A test-only forwarding proxy drops the client response after the real order server commits.
        verifier = self
        pid = self.product(4)
        body = self.order(pid)
        token = self.tokens[0]
        headers = {'Idempotency-Key': idempotency_key} if idempotency_key else {}
        observed = {}
        finished = threading.Event()
        class DropResponse(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                try:
                    self.rfile.read(int(self.headers.get('Content-Length', '0')))
                    observed['forwarded'] = observed.get('forwarded', 0) + 1
                    observed['order'] = verifier.data_call('order_a', 'POST', '/order', body, token, headers=headers)
                except Exception as error:
                    observed['error'] = type(error).__name__
                finally:
                    self.close_connection = True
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    finished.set()
        proxy = http.server.HTTPServer(('127.0.0.1', 0), DropResponse)
        thread = threading.Thread(target=proxy.serve_forever, kwargs={'poll_interval': 0.1}, daemon=True)
        thread.start()
        client = http.client.HTTPConnection('127.0.0.1', proxy.server_port, timeout=25)
        lost = False
        try:
            client.request('POST', '/order', json.dumps(body), {'Content-Type': 'application/json'})
            try:
                client.getresponse()
            except (http.client.RemoteDisconnected, ConnectionResetError):
                lost = True
            assert finished.wait(5) and lost and 'error' not in observed and observed.get('forwarded') == 1
            no = observed['order']['orderNo']
            assert self.data_call('order_b', 'GET', '/order/' + no, token=token)['status'] == 0
            assert self.counters(pid) == (3, 1)
            second = self.data_call('order_b', 'POST', '/order', body, token, headers=headers)
            if idempotency_key:
                assert second == observed['order'] and self.counters(pid) == (3, 1)
                assert self.data_call('order_b', 'GET', '/order/idempotency/' + idempotency_key, token=token) == second
            else:
                assert second['orderNo'] != no and self.counters(pid) == (2, 2)
        finally:
            client.close()
            proxy.shutdown()
            proxy.server_close()
            thread.join(timeout=5)
        self.record('idempotent_response_lost_after_real_commit' if idempotency_key else 'legacy_response_lost_after_real_commit',
                    client_saw_response=False, first_request_committed=True, automatic_retries=0,
                    same_key_returns_original=bool(idempotency_key), explicit_new_request_creates_new_order=not bool(idempotency_key))

    def shipping_cancel(self):
        pid = self.product(self.rounds * 2)
        shipped = 0
        for i in range(self.rounds):
            no = self.data_call('order_a', 'POST', '/order', self.order(pid, quantity=2), self.tokens[0])['orderNo']
            self.data_call('order_a', 'POST', f'/order/{no}/pay', token=self.tokens[0])
            jobs = [lambda: self.call('order_a', 'POST', f'/order/{no}/ship',
                     {'logisticsCompany': 'Verifier', 'trackingNo': 'RACE' + str(i)}, admin=True, expected=(200, 409)),
                    lambda: self.call('order_b', 'POST', f'/order/{no}/cancel', token=self.tokens[0], expected=(200, 409))]
            outcomes = self.race(jobs if i % 2 else list(reversed(jobs)))
            assert sorted(r[0] for r in outcomes) == [200, 409]
            status = self.data_call('order_a', 'GET', f'/order/{no}/status', token=self.tokens[0])['status']
            assert status in (3, 5)
            shipped += status == 3
        assert self.counters(pid) == (self.rounds * 2 - shipped * 2, shipped * 2)
        self.record('shipping_vs_cancel', rounds=self.rounds, shipped=shipped, canceled=self.rounds-shipped)

    def product_updates(self):
        pid = self.product(self.workers + 5)
        initial = self.workers + 5
        jobs = []
        for i in range(self.workers):
            if i % 2:
                jobs.append(lambda: self.call('product', 'PUT', '/product',
                    {'id': pid, 'name': 'stale_verify', 'stock': initial}, admin=True, expected=(200, 409)))
            else:
                jobs.append(lambda i=i: self.call('order_a' if i % 4 else 'order_b', 'POST', '/order',
                    self.order(pid), self.tokens[i], expected=200))
        outcomes = self.race(jobs)
        ordered = (self.workers + 1) // 2
        assert self.counters(pid) == (initial - ordered, ordered)
        self.call('product', 'PUT', '/product', {'id': pid, 'stock': initial}, admin=True, expected=409)
        self.record('product_put_vs_stock_deduction', orders=ordered,
                    put_responses=dict(Counter(outcomes[i][0] for i in range(len(outcomes)) if i % 2)))

    def categories(self):
        for mode in ('product', 'child'):
            for _ in range(self.rounds):
                category = self.category()
                body = {'name': 'race_reference_' + uuid.uuid4().hex[:10]}
                if mode == 'product':
                    body.update(price=12.34, stock=0, categoryId=category)
                    route = '/product'
                else:
                    body['parentId'] = category
                    route = '/category'
                outcomes = self.race([
                    lambda: self.call('product', 'DELETE', '/category/' + str(category), admin=True, expected=(200, 409)),
                    lambda: self.call('product', 'POST', route, body, admin=True, expected=(200, 404, 409))])
                assert not all(r[0] == 200 for r in outcomes)
                if mode == 'product' and outcomes[1][0] == 200:
                    rows = self.sql('SELECT id,stock FROM t_product WHERE category_id=' + str(category) + ' AND deleted=0')
                    for pid, stock in rows:
                        self.initial_stock[int(pid)] = int(stock)
        for _ in range(self.rounds):
            a, b = self.category(), self.category()
            outcomes = self.race([
                lambda: self.call('product', 'PUT', '/category', {'id': a, 'parentId': b}, admin=True, expected=(200, 409)),
                lambda: self.call('product', 'PUT', '/category', {'id': b, 'parentId': a}, admin=True, expected=(200, 409))])
            assert sorted(r[0] for r in outcomes) == [200, 409]
        self.record('category_delete_references_and_mutual_moves', races=self.rounds*3)

    def attributes(self):
        pid = self.product(0)
        def batch(tag):
            return [{'attrName': tag + '_' + str(i), 'attrValue': 'value'} for i in range(4)]
        self.data_call('product', 'POST', f'/product/{pid}/attrs', batch('initial'), admin=True)
        def check_snapshot():
            for _ in range(self.rounds):
                attrs = self.data_call('product', 'GET', f'/product/{pid}')['attrs']
                names = [a['attrName'] for a in attrs]
                assert len(names) == 4 and len({n.rsplit('_', 1)[0] for n in names}) == 1, names
            return 200, None
        jobs = [lambda i=i: self.call('product', 'POST', f'/product/{pid}/attrs',
                                     batch('batch' + str(i)), admin=True, expected=200) for i in range(self.rounds)]
        self.race(jobs + [check_snapshot, check_snapshot])
        for _ in range(self.rounds):
            target = self.product(0)
            self.race([lambda: self.call('product', 'POST', f'/product/{target}/attrs', batch('last'), admin=True, expected=(200, 404)),
                       lambda: self.call('product', 'DELETE', f'/product/{target}', admin=True, expected=200)])
            assert self.sql('SELECT COUNT(*) FROM t_product_attr WHERE deleted=0 AND product_id=' + str(target))[0][0] == '0'
        self.record('atomic_attribute_replacement_snapshot_and_delete', overwrite_writers=self.rounds,
                    snapshot_reads=self.rounds*2, delete_races=self.rounds)

    def address_order(self):
        pid = self.product(self.rounds * 2)
        token = self.tokens[0]
        for i in range(self.rounds):
            old = {'receiverName': 'Old', 'receiverPhone': '111', 'detailAddress': 'Old Road', 'isDefault': False}
            address = self.data_call('user', 'POST', '/address', old, token)
            new = {'id': address['id'], 'receiverName': 'New', 'receiverPhone': '222', 'detailAddress': 'New Road', 'isDefault': False}
            outcomes = self.race([
                lambda: self.call('order_a', 'POST', '/order', self.order(pid, address=address['id']), token, expected=200),
                lambda: self.call('user', 'PUT', '/address', new, token, expected=200)])
            detail = self.data_call('order_b', 'GET', '/order/' + outcomes[0][1]['orderNo'], token=token)
            snapshot = (detail['receiverName'], detail['receiverPhone'], detail['receiverAddress'])
            assert snapshot in (('Old', '111', 'Old Road'), ('New', '222', 'New Road')), snapshot
            outcomes = self.race([
                lambda: self.call('order_b', 'POST', '/order', self.order(pid, address=address['id']), token, expected=(200, 404)),
                lambda: self.call('user', 'DELETE', '/address/' + str(address['id']), token=token, expected=200)])
            if outcomes[0][0] == 200:
                detail = self.data_call('order_a', 'GET', '/order/' + outcomes[0][1]['orderNo'], token=token)
                assert detail['receiverAddress'] == 'New Road'
        self.record('address_update_delete_vs_order_snapshot', update_races=self.rounds, delete_races=self.rounds)

    def inventory_ledger(self):
        queries = {
            'negative_counters': 'SELECT COUNT(*) FROM t_product_sku WHERE stock<0 OR sales<0',
            'orphan_items': 'SELECT COUNT(*) FROM t_order_item i LEFT JOIN t_orders o ON o.id=i.order_id WHERE o.id IS NULL OR i.order_no<>o.order_no',
            'orphan_orders': 'SELECT COUNT(*) FROM t_orders o LEFT JOIN t_user u ON u.id=o.user_id WHERE u.id IS NULL',
            'deleted_category_references': 'SELECT COUNT(*) FROM t_product p LEFT JOIN t_category c ON c.id=p.category_id WHERE p.deleted=0 AND p.category_id IS NOT NULL AND (c.id IS NULL OR c.deleted<>0)',
            'orphan_categories': 'SELECT COUNT(*) FROM t_category c LEFT JOIN t_category p ON p.id=c.parent_id WHERE c.deleted=0 AND c.parent_id<>0 AND (p.id IS NULL OR p.deleted<>0)',
            'orphan_active_attrs': 'SELECT COUNT(*) FROM t_product_attr a LEFT JOIN t_product p ON p.id=a.product_id WHERE a.deleted=0 AND (p.id IS NULL OR p.deleted<>0)',
            'multiple_defaults': 'SELECT COUNT(*) FROM (SELECT user_id FROM t_address WHERE deleted=0 AND is_default=1 GROUP BY user_id HAVING COUNT(*)>1) x',
            'inexact_order_totals': 'SELECT COUNT(*) FROM (SELECT o.id FROM t_orders o LEFT JOIN t_order_item i ON i.order_id=o.id GROUP BY o.id,o.total_amount HAVING COUNT(i.id)<1 OR COUNT(i.id)>20 OR SUM(i.price*i.quantity)<>o.total_amount) x'
        }
        for name, query in queries.items():
            assert self.sql(query)[0][0] == '0', name
        rows = self.sql('SELECT p.id,s.stock,s.sales,COALESCE(x.q,0) FROM t_product p JOIN '
                        '(SELECT product_id,SUM(stock) stock,SUM(sales) sales FROM t_product_sku GROUP BY product_id) s ON s.product_id=p.id LEFT JOIN '
                        '(SELECT i.product_id,SUM(i.quantity) q FROM t_order_item i JOIN t_orders o ON o.id=i.order_id '
                        'WHERE o.deleted=0 AND o.status<>5 GROUP BY i.product_id) x ON x.product_id=p.id')
        assert len(rows) == len(self.initial_stock), (len(rows), len(self.initial_stock))
        for pid, stock, sales, quantity in rows:
            pid, stock, sales, quantity = map(int, (pid, stock, sales, quantity))
            assert stock == self.initial_stock[pid] - quantity and sales == quantity, (pid, stock, sales, quantity)
        # Traverse every active category with no arbitrary depth ceiling.
        categories = {int(i): int(parent) for i, parent in self.sql('SELECT id,parent_id FROM t_category WHERE deleted=0')}
        for start in categories:
            visited, cursor = set(), start
            while cursor:
                assert cursor in categories and cursor not in visited, ('category cycle', start)
                visited.add(cursor)
                cursor = categories[cursor]
        self.record('global_database_integrity_and_exact_inventory_ledger', products=len(rows), invariant_checks=len(queries)+2)

    def real_service_failures(self, pid=None):
        token = self.tokens[0]
        pid = self.product(2) if pid is None else pid
        orders_before = self.sql('SELECT COUNT(*) FROM t_orders')[0][0]
        stock_before = self.counters(pid)
        stop(self.apps[0])  # This verifier's dedicated product JVM only.
        self.call('order_a', 'POST', '/order', self.order(pid), token, expected=503)
        assert self.counters(pid) == stock_before
        assert self.sql('SELECT COUNT(*) FROM t_orders')[0][0] == orders_before
        self.data_call('order_a', 'GET', '/order/page', token=token)
        self.record('real_product_outage_has_no_partial_order', http=503, stock_unchanged=True)
        subprocess.run(self.client + ['--execute', 'SHUTDOWN'], capture_output=True,
                       timeout=15, check=True, creationflags=FLAGS)
        stop(self.server)
        for app, path in (('user', '/user/me'), ('order_a', '/order/page'), ('order_b', '/order/page')):
            self.call(app, 'GET', path, token=token, expected=503)
        self.record('real_database_outage_returns_503', protected_reads=3, automatic_write_retry=False)

    def audit_logs(self):
        import re
        credentials = [self.key, 'isolated123', *self.tokens]
        for file in self.out.glob('*.log'):
            content = file.read_text(encoding='utf-8', errors='replace')
            assert not any(secret in content for secret in credentials), ('credential in logs', file.name)
            assert not re.search(r'eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}', content), ('JWT/hash in logs', file.name)
            assert '[DEBUG-final-db]' not in content, ('debug probe remains', file.name)
        self.record('logs_do_not_contain_credentials_or_debug_probes', checked_logs=len(list(self.out.glob('*.log'))))

    def run(self, faults_only=False):
        try:
            self.start_database()
            if faults_only:
                self.meta['mode'] = 'faults_only_using_existing_jars'
                self.sql((ROOT / 'xiaoke-order/src/test/resources/order-schema.sql').read_text(encoding='utf-8'))
            else:
                self.meta['mode'] = 'full_clean_verify_concurrency_and_faults'
                self.build()
            self.start_apps()
            self.actors()
            scenarios = (self.smoke,) if faults_only else (self.smoke, self.registration_and_defaults, self.last_stock,
                             self.distinct_product_orders, self.pay_cancel,
                             self.shipping_cancel, self.product_updates, self.categories, self.attributes,
                             self.address_order, self.lost_committed_response, self.inventory_ledger)
            for scenario in scenarios:
                scenario()
            self.real_service_failures()
            self.audit_logs()
            self.meta['completed'] = True
        except BaseException as failure:
            self.meta['failure_type'] = type(failure).__name__
            self.meta['failure'] = str(failure)
            raise
        finally:
            for app in reversed(self.apps):
                stop(app)
            if self.server is not None:
                subprocess.run(self.client + ['--execute', 'SHUTDOWN'], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=15, creationflags=FLAGS)
            stop(self.server)
            # Verify the dedicated server no longer listens before removing its exact temp directory.
            with socket.socket() as probe:
                self.meta['temporary_processes_stopped'] = all(p.poll() is not None for p in self.apps) and probe.connect_ex(('127.0.0.1', self.port)) != 0
            if self.data.parent != Path(tempfile.gettempdir()).resolve() or not self.data.name.startswith('xiaoke-final-mysql-'):
                raise RuntimeError('Refusing unexpected data directory')
            for attempt in range(15):
                try:
                    shutil.rmtree(self.data)
                    self.meta['temporary_data_removed'] = True
                    break
                except OSError:
                    time.sleep(0.3)
            else:
                self.meta['temporary_data_removed'] = False
                self.meta['remaining_temporary_directory'] = str(self.data)
            self.meta['ended_at'] = datetime.now(timezone(timedelta(hours=8))).isoformat()
            self.meta['http_checks'] = len(self.checks)
            self.meta['http_statuses'] = dict(Counter(c['http'] for c in self.checks))
            self.meta['scenarios'] = self.results
            (self.out / 'http-checks.json').write_text(json.dumps(self.checks, indent=2), encoding='utf-8')
            (self.out / 'result.json').write_text(json.dumps(self.meta, ensure_ascii=False, indent=2), encoding='utf-8')
            print('Evidence: ' + str(self.out.relative_to(ROOT)), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home', default=os.environ.get('XIAOKE_MYSQL_HOME', 'D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers', type=int, default=24)
    parser.add_argument('--rounds', type=int, default=8)
    parser.add_argument('--faults-only', action='store_true',
                        help='Use existing verified jars to run only smoke and real service outage checks; no Maven build')
    args = parser.parse_args()
    if Path.cwd().resolve() != ROOT:
        raise RuntimeError('Run this command from the repository root')
    if not 4 <= args.workers <= 48 or not 1 <= args.rounds <= 20:
        raise ValueError('workers must be 4..48; rounds must be 1..20')
    mysql = Path(args.mysql_home).resolve()
    if not all((mysql / 'bin' / name).is_file() for name in ('mysql.exe', 'mysqld.exe')):
        raise RuntimeError('Provide the installed MySQL directory with --mysql-home')
    Verification(mysql, args.workers, args.rounds).run(faults_only=args.faults_only)


if __name__ == '__main__':
    main()
