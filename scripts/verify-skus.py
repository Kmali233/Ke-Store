"""Stage03: SKU migration/backfill recovery + all prior JUnit/HTTP regressions + SKU races."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
from db_migrate import ROOT, MIGRATIONS
from sku_backfill import finish

spec = importlib.util.spec_from_file_location('idempotency_verification', ROOT / 'scripts/verify-idempotency.py')
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)


class SkuVerification(previous.IdempotencyVerification):
    def __init__(self, mysql, workers, rounds):
        super().__init__(mysql, workers, rounds)
        self.meta['verification'] = 'stage03_skus_and_full_system'
        self.schemas['sku_upgrade'] = self.schemas['order'] + '_sku_upgrade'

    def start_database(self):
        super().start_database()
        db = self.database('sku_upgrade')
        old = self.out / 'pre-sku-migrations'
        old.mkdir()
        for version in ('V1__initial_schema.sql', 'V2__order_idempotency.sql'):
            shutil.copy2(MIGRATIONS / version, old)
        db.migrate(log=self.out / 'sku-old-v2.log', locations=old)
        db.query("""INSERT INTO t_user(id,username,password) VALUES(1,'legacy_sku_buyer','unused-test-only');
            INSERT INTO t_product(id,name,price,stock,sales) VALUES
            (1,'old phone',2.50,3,2),(2,'p2',1,8,0),(3,'p3',2,9,0),(4,'p4',3,10,0),(5,'p5',4,11,0);
            INSERT INTO t_orders(id,order_no,user_id,total_amount,receiver_name,receiver_phone,receiver_address)
                VALUES(1,'20260101000001',1,5,'Old','000','Old Road');
            INSERT INTO t_order_item(order_id,order_no,product_id,product_name,price,quantity)
                VALUES(1,'20260101000001',1,'old snapshot',2.50,2);
            INSERT INTO t_order_idempotency(user_id,idempotency_key,hash_version,request_hash,order_no,result_status,total_amount)
                VALUES(1,'before-sku',1,REPEAT('a',64),'20260101000001',0,5);
        """)
        before_items = db.query('SELECT * FROM t_order_item')
        before_receipts = db.query('SELECT * FROM t_order_idempotency')
        db.flyway('migrate', log=self.out / 'sku-v3-ddl.log')
        def interrupt(batch):
            if batch == 1:
                raise RuntimeError('intentional backfill interruption')
        try:
            finish(db, batch_size=2, after_batch=interrupt)
        except RuntimeError as error:
            assert str(error) == 'intentional backfill interruption'
        else:
            raise AssertionError('Backfill should have been interrupted')
        assert db.query('SELECT COUNT(*) n FROM t_product_sku')[0]['n'] == '2'
        assert db.query('SELECT * FROM t_catalog_migration') == []
        first = db.query('SELECT * FROM t_product_sku ORDER BY id')
        db.migrate(log=self.out / 'sku-resume-migrate.log')
        assert db.query('SELECT * FROM t_product_sku WHERE product_id<=2 ORDER BY id') == first
        assert db.query('SELECT COUNT(*) n FROM t_product_sku')[0]['n'] == '5'
        assert db.query('SELECT COUNT(*) n FROM t_product p JOIN t_product_sku s ON s.product_id=p.id '
                        'WHERE s.price<>p.price OR s.stock<>p.stock OR s.sales<>p.sales')[0]['n'] == '0'
        for item in db.query('SELECT * FROM t_order_item'):
            assert item.pop('sku_id') is None and item.pop('sku_name') is None and item.pop('sku_spec') is None
            assert item in before_items
        assert db.query('SELECT * FROM t_order_idempotency') == before_receipts
        db.migrate(log=self.out / 'sku-repeat.log')
        assert db.query('SELECT COUNT(*) n FROM t_product_sku')[0]['n'] == '5'
        # Completion prevents a later migrate from copying frozen SPU counters over live SKU trades.
        db.query('UPDATE t_product_sku SET stock=2,sales=3 WHERE product_id=1')
        db.migrate(log=self.out / 'sku-live-counters-not-reset.log')
        assert db.query('SELECT stock,sales FROM t_product_sku WHERE product_id=1') == [{'stock':'2','sales':'3'}]
        self.record('v2_to_v3_interrupted_backfill_resume_and_reconcile', products=5, copied_before_interruption=2,
                    old_snapshots_preserved=True, old_receipts_preserved=True, live_counters_never_reset=True)

    def smoke(self):
        super().smoke()
        if self.meta.get('mode') != 'faults_only_using_existing_jars':
            self.sku_business()

    def sku(self, pid, name, stock, price, specs):
        row = self.data_call('product', 'POST', f'/product/{pid}/skus',
                             {'name':name, 'specs':specs, 'price':price, 'stock':stock}, admin=True)
        self.initial_stock[pid] += stock
        return row

    def sku_order(self, sid, quantity=1):
        body = self.order(1, quantity=quantity)
        body['items'] = [{'skuId':sid, 'quantity':quantity}]
        return body

    def sku_counters(self, sid):
        return tuple(map(int, self.sql('SELECT stock,sales FROM t_product_sku WHERE id=' + str(sid))[0]))

    def sku_business(self):
        pid = self.product(5)
        black = self.sku(pid, 'black256', 4, 20.50, {'颜色':'黑色','容量':'256GB'})
        white = self.sku(pid, 'white512', 6, 30.00, {'颜色':'白色','容量':'512GB'})
        duplicate = {'name':'dup','specs':{'容量':'256GB','颜色':' 黑色 '},'price':99,'stock':3}
        self.call('product','POST',f'/product/{pid}/skus',duplicate,admin=True,expected=409)
        self.call('product','POST',f'/product/{pid}/skus',duplicate,expected=401)
        body = self.sku_order(black['id'],2)
        body['items'].append({'skuId':white['id'],'quantity':1})
        created = self.keyed('order_a',body,'sku-two-colors')
        assert str(created['totalAmount']) == '71.00'
        assert self.sku_counters(black['id']) == (2,2) and self.sku_counters(white['id']) == (5,1)
        detail = self.data_call('order_b','GET','/order/'+created['orderNo'],token=self.tokens[0])
        assert {item['skuId'] for item in detail['items']} == {black['id'],white['id']}
        self.data_call('product','PUT',f"/product/{pid}/skus/{black['id']}",
                       {'name':'renamed','price':99,'specs':{'颜色':'灰色','容量':'1TB'}},admin=True)
        assert self.data_call('order_b','GET','/order/'+created['orderNo'],token=self.tokens[0]) == detail
        reverse = dict(body,items=list(reversed(body['items'])))
        assert self.keyed('order_b',reverse,'sku-two-colors') == created
        self.call('product','PUT',f"/product/{pid}/skus/{white['id']}",{'stock':100},admin=True,expected=409)
        self.data_call('product','DELETE',f"/product/{pid}/skus/{black['id']}",admin=True)
        self.keyed('order_b',self.sku_order(black['id']),'deleted-sku',expected=404)
        self.data_call('order_a','POST','/order/'+created['orderNo']+'/cancel',token=self.tokens[0])
        assert self.sku_counters(black['id']) == (4,0) and self.sku_counters(white['id']) == (6,0)
        # Down-shelf SKU cannot be newly purchased, yet cancel above restored the deleted SKU.
        self.data_call('product','PUT',f"/product/{pid}/skus/{white['id']}",{'status':0},admin=True)
        self.keyed('order_a',self.sku_order(white['id']),'down-sku',expected=404)
        self.data_call('product','PUT',f"/product/{pid}/skus/{white['id']}",{'status':1},admin=True)
        self.record('independent_sku_price_inventory_snapshots_and_deleted_restore', total='71.00',
                    default_sku_untouched=True, snapshot_immutable=True, v2_digest_replay=True)

        pid = self.product(0)
        target = self.sku(pid,'race',8,3,{'颜色':'红'})
        sibling = self.sku(pid,'sibling',7,4,{'颜色':'蓝'})
        outcomes = self.race([lambda i=i:self.call('order_a' if i%2 else 'order_b','POST','/order',
                   self.sku_order(target['id']),self.tokens[i],expected=(200,409),
                   headers={'Idempotency-Key':'sku-race-'+str(i)}) for i in range(self.workers)])
        successes = sum(code==200 for code,_ in outcomes)
        assert successes == min(8,self.workers)
        assert self.sku_counters(target['id']) == (8-successes,successes)
        assert self.sku_counters(sibling['id']) == (7,0)
        self.record('two_jvms_sku_last_stock_and_sibling_isolation', requests=self.workers,successes=successes,
                    conflicts=self.workers-successes,sibling_untouched=True)
        page = self.data_call('product','GET','/product/'+str(pid))
        assert page['stock'] == 15-successes and page['price'] == 3
        # Assert assets are served by the actual product JAR, independently of a browser rendering check.
        import urllib.request
        for asset in ('sku-demo.html','sku-demo.js','sku-demo.css'):
            with urllib.request.urlopen(self.urls['product']+'/'+asset,timeout=5) as response:
                assert response.status==200 and len(response.read())>100
        self.record('sku_selector_demo_assets_served',assets=3)

    def inventory_ledger(self):
        super().inventory_ledger()
        query = '''SELECT COUNT(*) FROM t_order_item i LEFT JOIN t_product_sku s ON s.id=i.sku_id
                   WHERE i.sku_id IS NOT NULL AND (s.id IS NULL OR s.product_id<>i.product_id)'''
        assert self.sql(query)[0][0] == '0'
        self.record('sku_item_ownership_and_inventory_authority',orphan_or_wrong_spu=0)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home',default=os.environ.get('XIAOKE_MYSQL_HOME','D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers',type=int,default=24)
    parser.add_argument('--rounds',type=int,default=8)
    args = parser.parse_args()
    if Path.cwd().resolve()!=ROOT:
        parser.error('Run from the repository root')
    if not 4<=args.workers<=48 or args.workers%2 or not 1<=args.rounds<=20:
        parser.error('workers must be even 4..48, rounds 1..20')
    SkuVerification(Path(args.mysql_home).resolve(),args.workers,args.rounds).run()
