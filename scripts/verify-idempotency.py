"""Stage02: real migrations + all JUnit/HTTP regressions + two-JVM idempotency and failure checks."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
from db_migrate import ROOT

spec = importlib.util.spec_from_file_location('migration_verification', ROOT / 'scripts/verify-migrations.py')
migrations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migrations)
system = migrations.system


class IdempotencyVerification(migrations.MigrationVerification):
    def __init__(self, mysql, workers, rounds):
        super().__init__(mysql, workers, rounds)
        self.meta['verification'] = 'stage02_idempotency_migrations_and_full_system'

    def build(self):
        super().build()
        db = self.database('order')
        history = db.query('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank')
        assert history == [{'version': str(v), 'success': '1'} for v in (1,2,3,4)]
        assert db.query('SELECT version FROM t_catalog_migration') == [{'version':'3'}]
        db.migrate(log=self.out / 'stage02-repeat-migration.log')
        assert db.query('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank') == history
        self.record('current_flyway_migration_and_repeat', versions=['1', '2', '3', '4'], receipt_table_empty=True)

    def keyed(self, app, body, key, buyer=0, expected=200):
        return self.call(app, 'POST', '/order', body, self.tokens[buyer], expected=expected,
                         headers={'Idempotency-Key': key})[1]

    def smoke(self):
        super().smoke()
        if self.meta.get('mode') == 'faults_only_using_existing_jars':
            return
        self.idempotency_races()
        self.rollback_and_retry()
        self.restart_and_replay()

    def idempotency_races(self):
        pid = self.product(100)
        body = self.order(pid, quantity=2)
        results = self.race([lambda i=i: self.keyed('order_a' if i % 2 else 'order_b', body, 'same-key')
                             for i in range(self.workers)])
        original = results[0]
        assert all(result == original for result in results)
        assert self.counters(pid) == (98, 2)
        assert self.sql("SELECT COUNT(*) FROM t_order_idempotency WHERE idempotency_key='same-key'")[0][0] == '1'
        assert self.sql('SELECT COUNT(*) FROM t_order_item WHERE product_id=' + str(pid))[0][0] == '1'
        self.keyed('order_b', self.order(pid, quantity=3), 'same-key', expected=409)
        second = self.keyed('order_b', body, 'same-key', buyer=1)
        assert second['orderNo'] != original['orderNo'] and self.counters(pid) == (96, 4)
        self.call('order_a', 'GET', '/order/idempotency/same-key', token=self.tokens[2], expected=404)
        self.call('order_a', 'GET', '/order/idempotency/same-key', expected=401)
        for key in ('', 'bad key', 'x' * 65, 'bad,key'):
            self.keyed('order_a', body, key, expected=400)
        self.data_call('order_a', 'POST', '/order/' + original['orderNo'] + '/cancel', token=self.tokens[0])
        self.sql('UPDATE t_product SET status=0 WHERE id=' + str(pid))
        self.sql('UPDATE t_product_sku SET price=88 WHERE product_id=' + str(pid))
        assert self.keyed('order_b', body, 'same-key') == original
        assert self.counters(pid) == (98, 2)
        self.sql('UPDATE t_product SET status=1 WHERE id=' + str(pid))
        self.sql('UPDATE t_product_sku SET price=12.34 WHERE product_id=' + str(pid))
        self.record('two_jvms_same_key_single_order', concurrent_requests=self.workers,
                    identical_results=True, same_key_different_body_http=409, separate_user_succeeds=True,
                    cancelled_order_and_delisted_product_replay_original=True)

        pid = self.product(10)
        jobs = [lambda i=i: self.call('order_a' if i % 2 else 'order_b', 'POST', '/order',
                    self.order(pid, quantity=1 + i % 2), self.tokens[0], expected=(200, 409),
                    headers={'Idempotency-Key': 'competing-content'}) for i in range(self.workers)]
        outcomes = self.race(jobs)
        winners = [value for code, value in outcomes if code == 200]
        assert winners and all(result == winners[0] for result in winners)
        assert sum(code == 409 for code, _ in outcomes) == self.workers // 2
        quantity = int(self.sql('SELECT quantity FROM t_order_item WHERE product_id=' + str(pid))[0][0])
        assert self.counters(pid) == (10 - quantity, quantity)
        self.record('two_jvms_same_key_competing_content', successes=len(winners), conflicts=self.workers // 2,
                    only_one_order=True, deducted_quantity=quantity)

    def rollback_and_retry(self):
        pid = self.product(5)
        body = self.order(pid, quantity=2)
        before = self.sql('SELECT COUNT(*) FROM t_orders')[0][0]
        # Real MySQL failure after receipt insertion; every earlier write must roll back too.
        self.sql("CREATE TRIGGER verify_receipt_failure AFTER INSERT ON t_order_idempotency FOR EACH ROW "
                 "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated injected receipt failure'")
        try:
            self.keyed('order_a', body, 'rolled-back-key', expected=500)
        finally:
            self.sql('DROP TRIGGER verify_receipt_failure')
        assert self.counters(pid) == (5, 0)
        assert self.sql('SELECT COUNT(*) FROM t_orders')[0][0] == before
        assert self.sql('SELECT COUNT(*) FROM t_order_item WHERE product_id=' + str(pid))[0][0] == '0'
        assert self.sql("SELECT COUNT(*) FROM t_order_idempotency WHERE idempotency_key='rolled-back-key'")[0][0] == '0'
        self.call('order_b', 'GET', '/order/idempotency/rolled-back-key', token=self.tokens[0], expected=404)
        result = self.keyed('order_b', body, 'rolled-back-key')
        assert self.keyed('order_a', body, 'rolled-back-key') == result and self.counters(pid) == (3, 2)
        self.record('receipt_sql_failure_rolls_back_everything_and_same_key_retries', inventory_restored=True,
                    partial_orders=0, partial_receipts=0)

    def restart_and_replay(self):
        pid = self.product(5)
        body = self.order(pid)
        original = self.keyed('order_a', body, 'restart-key')
        command, env = self.app_specs['order_a']
        old = self.apps[2]
        system.stop(old)
        with (self.out / 'order_a-restarted.log').open('wb') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=system.FLAGS)
        self.apps[2] = process
        end = time.monotonic() + 60
        while time.monotonic() < end:
            if process.poll() is not None:
                raise RuntimeError('Restarted order process exited')
            try:
                urllib.request.urlopen(self.urls['order_a'] + '/probe', timeout=1)
            except urllib.error.HTTPError:
                break
            except (OSError, urllib.error.URLError):
                time.sleep(0.2)
        else:
            raise RuntimeError('Restart timed out')
        assert self.keyed('order_a', body, 'restart-key') == original
        assert self.data_call('order_a', 'GET', '/order/idempotency/restart-key', token=self.tokens[0]) == original
        assert self.counters(pid) == (4, 1)
        self.record('actual_order_jvm_restart_replays_persisted_receipt', old_process_stopped=True, receipt_unchanged=True)

    def lost_committed_response(self):
        super().lost_committed_response()  # Keep the explicit legacy contract covered.
        super().lost_committed_response('lost-response-key')

    def inventory_ledger(self):
        super().inventory_ledger()
        invalid = self.sql('''SELECT COUNT(*) FROM t_order_idempotency r LEFT JOIN t_orders o
            ON o.order_no=r.order_no WHERE o.id IS NULL OR o.user_id<>r.user_id OR o.total_amount<>r.total_amount''')[0][0]
        assert invalid == '0'
        self.record('all_receipts_match_committed_orders_and_amounts', mismatches=0)

    def real_service_failures(self):
        pid = self.product(3)
        fault_pid = self.product(2)  # Prepare the legacy outage case before stopping product.
        body = self.order(pid)
        original = self.keyed('order_a', body, 'outage-key')
        system.stop(self.apps[0])
        assert self.keyed('order_b', body, 'outage-key') == original
        assert self.data_call('order_b', 'GET', '/order/idempotency/outage-key', token=self.tokens[0]) == original
        self.keyed('order_b', body, 'fresh-during-outage', expected=503)
        assert self.counters(pid) == (2, 1)
        self.record('real_product_jvm_outage_replay_skips_http', original_receipt_returned=True, new_key_http=503)
        super().real_service_failures(pid=fault_pid)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home', default=os.environ.get('XIAOKE_MYSQL_HOME', 'D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers', type=int, default=24)
    parser.add_argument('--rounds', type=int, default=8)
    parser.add_argument('--faults-only', action='store_true', help='Reuse existing verified JARs; rerun only smoke and actual service outages')
    args = parser.parse_args()
    if Path.cwd().resolve() != ROOT:
        parser.error('Run from the repository root')
    if not 4 <= args.workers <= 48 or args.workers % 2 or not 1 <= args.rounds <= 20:
        parser.error('workers must be even, 4..48; rounds must be 1..20')
    IdempotencyVerification(Path(args.mysql_home).resolve(), args.workers, args.rounds).run(faults_only=args.faults_only)
