"""Stage 01: isolated Flyway acceptance plus existing real DB/HTTP concurrency suite.

Run: python scripts/verify-migrations.py
Never connects to the business instance. Demo V2/V3 live only in ignored evidence directories.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil

from db_migrate import Database, ROOT, MIGRATIONS, reference, sha256

spec = importlib.util.spec_from_file_location('system_verification', ROOT / 'scripts/verify-system.py')
system = importlib.util.module_from_spec(spec)
spec.loader.exec_module(system)


class MigrationVerification(system.Verification):
    def __init__(self, mysql, workers, rounds):
        super().__init__(mysql, workers, rounds)
        self.meta['verification'] = 'stage01_migrations_and_full_system'
        suffix = self.schemas['order'].rsplit('_', 1)[1]
        for label in ('fresh', 'legacy', 'mismatch', 'restored', 'partial', 'recovered'):
            self.schemas[label] = f'xiaoke_migration_verify_{label}_{suffix}'

    def database(self, label):
        env = os.environ.copy()
        env.update({'XIAOKE_MIGRATION_HOST': '127.0.0.1', 'XIAOKE_MIGRATION_PORT': str(self.port),
                    'XIAOKE_MIGRATION_DATABASE': self.schemas[label],
                    'XIAOKE_MIGRATION_USER': 'root', 'XIAOKE_MIGRATION_PASSWORD': ''})
        return Database(str(self.mysql), env)

    def expect_failure(self, action, phrase):
        try:
            action()
        except RuntimeError as failure:
            assert phrase.lower() in str(failure).lower(), str(failure)
        else:
            raise AssertionError('Expected failure: ' + phrase)

    def business_rows(self, db):
        return {table: db.query(f'SELECT * FROM `{table}` ORDER BY id') for table in reference()['ddl']}

    def start_database(self):
        super().start_database()
        self.migration_checks()

    def migration_checks(self):
        # Enforce one reviewed DDL source for JUnit fixture subsets before running any migration.
        sync_spec = importlib.util.spec_from_file_location('schema_sync', ROOT / 'scripts/sync-test-schemas.py')
        sync = importlib.util.module_from_spec(sync_spec)
        sync_spec.loader.exec_module(sync)
        current_tables = sync.sync(check=True, mysql_home=self.mysql)
        self.record('test_schema_files_match_all_migrations')
        # Keep historical V1/failure demos isolated from the real V2 and future versions.
        v1_only = self.out / 'v1-only-migrations'
        v1_only.mkdir()
        shutil.copy2(MIGRATIONS / 'V1__initial_schema.sql', v1_only)

        fresh = self.database('fresh')
        fresh.migrate(log=self.out / 'migration-empty.log', locations=v1_only)
        fresh.check_v1()
        assert all(not rows for rows in self.business_rows(fresh).values())
        history = fresh.query('SELECT version, checksum, success FROM flyway_schema_history')
        assert len(history) == 1 and history[0]['version'] == '1' and history[0]['success'] == '1'
        fresh.migrate(log=self.out / 'migration-repeat.log', locations=v1_only)
        assert fresh.query('SELECT version, checksum, success FROM flyway_schema_history') == history
        self.record('empty_migration_and_repeat', business_tables=7, inserted_demo_accounts=0, history_rows=1)
        fresh.query('CREATE TABLE unexpected_managed(id INT)')
        self.expect_failure(lambda: fresh.migrate(log=self.out / 'managed-drift-refused.log'), 'structure mismatch')
        fresh.query('DROP TABLE unexpected_managed')
        self.record('v1_history_does_not_bypass_structure_validation')

        # Reproduce an actual pre-Flyway schema using only DDL from the old initialization script.
        legacy = self.database('legacy')
        legacy.query(f'ALTER DATABASE `{legacy.name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci')
        old_ddl = re.findall(r'CREATE TABLE t_\w+\s*\([\s\S]*?\) ENGINE=InnoDB[^;]*;',
                             (ROOT / 'sql/xiaoke_mall.sql').read_text(encoding='utf-8'))
        assert len(old_ddl) == 7
        legacy.query('\n'.join(old_ddl))
        legacy.query("""
            INSERT INTO t_user(id,username,password,nickname) VALUES(1,'migration_keep_me','unused-test-hash','迁移保留');
            INSERT INTO t_address(id,user_id,receiver_name,receiver_phone,detail_address) VALUES(1,1,'test','test','isolated sample');
            INSERT INTO t_category(id,name) VALUES(1,'migration category');
            INSERT INTO t_product(id,name,category_id,price,stock,sales) VALUES(1,'migration product',1,12.34,8,2);
            INSERT INTO t_product_attr(id,product_id,attr_name,attr_value) VALUES(1,1,'test','retained');
            INSERT INTO t_orders(id,order_no,user_id,total_amount,receiver_name,receiver_phone,receiver_address)
                VALUES(1,'MIGRATION-SAMPLE',1,24.68,'test','test','isolated sample');
            INSERT INTO t_order_item(id,order_id,order_no,product_id,product_name,price,quantity)
                VALUES(1,1,'MIGRATION-SAMPLE',1,'snapshot retained',12.34,2);
        """)
        before = self.business_rows(legacy)
        self.expect_failure(lambda: legacy.migrate(log=self.out / 'unmanaged.log'), 'Non-empty unmanaged')
        assert not legacy.has_history() and self.business_rows(legacy) == before
        self.record('unmanaged_database_never_automatically_baselined')

        mismatch = self.database('mismatch')
        mismatch.query((MIGRATIONS / 'V1__initial_schema.sql').read_text(encoding='utf-8'))
        mutations = (
            ('column_type', 'ALTER TABLE t_product MODIFY stock BIGINT NOT NULL DEFAULT 0',
             "ALTER TABLE t_product MODIFY stock INT NOT NULL DEFAULT 0 COMMENT '库存'"),
            ('missing_index', 'ALTER TABLE t_product DROP INDEX idx_name', 'ALTER TABLE t_product ADD INDEX idx_name(name)'),
            ('collation', 'ALTER TABLE t_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
             'ALTER TABLE t_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'),
            ('extra_table', 'CREATE TABLE unexpected(id INT)', 'DROP TABLE unexpected'),
            ('extra_view', 'CREATE VIEW unexpected_view AS SELECT id FROM t_user', 'DROP VIEW unexpected_view'),
            ('extra_trigger', 'CREATE TRIGGER unexpected_trigger BEFORE INSERT ON t_user FOR EACH ROW SET NEW.status=1',
             'DROP TRIGGER unexpected_trigger'),
        )
        for name, change, undo in mutations:
            mismatch.query(change)
            self.expect_failure(lambda: mismatch.baseline(log=self.out / f'refused-{name}.log'), 'structure mismatch')
            assert not mismatch.has_history()
            mismatch.query(undo)
            mismatch.check_v1()
        self.record('unknown_schema_adoption_refused', variations=[case[0] for case in mutations])

        # A real client error during dump must stop before baseline writes the history table.
        dump = legacy.bins['mysqldump']
        legacy.bins['mysqldump'] = legacy.bins['mysql']
        self.expect_failure(lambda: legacy.baseline(log=self.out / 'backup-failed.log'), 'Backup failed')
        legacy.bins['mysqldump'] = dump
        assert not legacy.has_history() and self.business_rows(legacy) == before
        backup = legacy.baseline(log=self.out / 'migration-baseline.log')
        marker = legacy.query('SELECT version,type,success FROM flyway_schema_history')
        assert marker == [{'version': '1', 'type': 'BASELINE', 'success': '1'}]
        assert self.business_rows(legacy) == before
        self.record('legacy_backup_and_explicit_baseline_preserve_all_data', tables_with_preserved_rows=7,
                    backup=str(backup.relative_to(ROOT)), backup_sha256=sha256(backup), backup_failure_blocks_baseline=True)

        demo = self.out / 'demo-migrations'
        demo.mkdir()
        shutil.copy2(MIGRATIONS / 'V1__initial_schema.sql', demo)
        v2 = demo / 'V2__migration_demo.sql'
        v2.write_text("ALTER TABLE t_product ADD COLUMN migration_note VARCHAR(64) DEFAULT NULL COMMENT 'isolated migration demo';\n", encoding='utf-8')
        legacy.migrate(log=self.out / 'migration-v2.log', locations=demo)
        assert legacy.query('SELECT migration_note FROM t_product') == [{'migration_note': None}]
        assert legacy.query('SELECT stock,sales,price FROM t_product') == [{'stock': '8', 'sales': '2', 'price': '12.34'}]
        upgraded = self.business_rows(legacy)
        for row in upgraded['t_product']:
            row.pop('migration_note')
        assert upgraded == before
        assert len(legacy.query('SELECT version FROM flyway_schema_history')) == 2
        self.expect_failure(lambda: legacy.flyway('validate', log=self.out / 'future-version-refused.log', locations=v1_only), 'failed')
        applied = v2.read_text(encoding='utf-8')
        v2.write_text(applied.replace('VARCHAR(64)', 'VARCHAR(65)'), encoding='utf-8')
        self.expect_failure(lambda: legacy.flyway('validate', log=self.out / 'checksum-refused.log', locations=demo), 'failed')
        assert 'checksum mismatch' in (self.out / 'checksum-refused.log').read_text(encoding='utf-8').lower()
        self.expect_failure(lambda: legacy.migrate(log=self.out / 'checksum-migrate-refused.log', locations=demo), 'failed')
        v2.write_text(applied, encoding='utf-8')
        legacy.flyway('validate', log=self.out / 'checksum-restored.log', locations=demo)
        self.record('incremental_column_upgrade_and_checksum_refusal', isolated_baseline_versions=['1'], demo_version='2',
                    rows_preserved=True, higher_database_version_refused=True)

        restored = self.database('restored')
        tampered = self.out / 'tampered-backup.sql'
        shutil.copy2(backup, tampered)
        shutil.copy2(backup.with_suffix('.json'), tampered.with_suffix('.json'))
        with tampered.open('ab') as stream:
            stream.write(b'\n-- changed after backup\n')
        self.expect_failure(lambda: restored.restore(tampered), 'SHA-256 mismatch')
        assert not any(restored.objects().values())
        restored.restore(backup)
        restored.check_v1()
        assert self.business_rows(restored) == before
        assert not restored.has_history()  # Backup was taken BEFORE the baseline marker.
        self.expect_failure(lambda: restored.restore(backup), 'EMPTY database')
        self.record('backup_restore_into_empty_schema', preserved_tables=7, overwrite_refused=True, tampered_backup_refused=True)

        # MySQL DDL is not transactional: demonstrate partial DDL and recover via pre-upgrade backup.
        partial = self.database('partial')
        partial.migrate(log=self.out / 'partial-v1.log', locations=v1_only)
        partial.query("INSERT INTO t_category(id,name) VALUES(1,'recovery sample'); "
                      "INSERT INTO t_product(id,name,category_id,stock) VALUES(1,'recovery sample',1,3)")
        before_failure = self.business_rows(partial)
        recovery_backup = partial.backup()
        broken = self.out / 'broken-migrations'
        broken.mkdir()
        shutil.copy2(MIGRATIONS / 'V1__initial_schema.sql', broken)
        (broken / 'V2__partial_failure.sql').write_text(
            'ALTER TABLE t_product ADD COLUMN partial_demo INT DEFAULT NULL;\n'
            'ALTER TABLE table_that_does_not_exist ADD COLUMN failure INT;\n', encoding='utf-8')
        self.expect_failure(lambda: partial.migrate(log=self.out / 'ddl-failure.log', locations=broken), 'failed')
        assert partial.query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_product' AND COLUMN_NAME='partial_demo'")
        self.expect_failure(lambda: partial.migrate(log=self.out / 'ddl-blind-retry-refused.log', locations=broken), 'failed')
        recovered = self.database('recovered')
        recovered.restore(recovery_backup)
        recovered.flyway('validate', log=self.out / 'recovery-validate.log', locations=v1_only)
        recovered.check_v1()
        assert self.business_rows(recovered) == before_failure
        self.record('nontransactional_ddl_failure_and_backup_recovery', partial_ddl_observed=True,
                    automatic_retry_blocked=True, business_data_restored=True)

        # Compare actual MySQL-rendered test table shapes as well as generated file contents.
        for label, module in (('restored', 'user'), ('mismatch', 'product'), ('fresh', 'order'), ('recovered', 'admin')):
            # Use a new schema in this dedicated instance, never an existing/business database.
            schema = self.schemas[label] + '_fixture'
            self.schemas[label + '_fixture'] = schema
            self.sql('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4', self.schemas['fresh'])
            fixture = self.database(label + '_fixture')
            fixture.query((ROOT / f'xiaoke-{module}/src/test/resources/{module}-schema.sql').read_text(encoding='utf-8'))
            assert fixture.structure()['ddl'] == {name: current_tables[name] for name in sync.MODULE_TABLES[module]}
        self.record('mysql_fixture_structures_match_versioned_schema', modules=['user', 'product', 'order', 'admin'])

    def build(self):
        super().build()
        import zipfile
        for module in ('product', 'user', 'order', 'admin'):
            with zipfile.ZipFile(ROOT / f'xiaoke-{module}/target/xiaoke-{module}-1.0.0.jar') as jar:
                assert not any('flyway' in name.lower() for name in jar.namelist()), module
        self.record('business_jars_do_not_include_flyway', modules=['product', 'user', 'order', 'admin'])
        # JUnit intentionally uses fixture subsets. Actual HTTP processes below use a Flyway-created DB.
        schema = self.schemas['order']
        assert self.port != 3306 and schema.startswith('xiaoke_order_verify_')
        self.sql('DROP DATABASE `' + schema + '`; CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4')
        self.database('order').migrate(log=self.out / 'http-database-migrate.log')
        self.database('order').flyway('validate', log=self.out / 'http-database-validate.log')
        self.record('http_services_use_flyway_created_schema')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home', default=os.environ.get('XIAOKE_MYSQL_HOME', 'D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers', type=int, default=24)
    parser.add_argument('--rounds', type=int, default=8)
    args = parser.parse_args()
    if Path.cwd().resolve() != ROOT:
        parser.error('Run from the repository root')
    if not 4 <= args.workers <= 48 or not 1 <= args.rounds <= 20:
        parser.error('workers must be 4..48 and rounds 1..20')
    MigrationVerification(Path(args.mysql_home).resolve(), args.workers, args.rounds).run()


if __name__ == '__main__':
    main()
