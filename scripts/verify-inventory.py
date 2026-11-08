"""Stage07: inventory adjustments, opening migration, transactional journal and all historical regressions."""
import argparse
import importlib.util
import os
from pathlib import Path
import shutil
from db_migrate import ROOT,MIGRATIONS
spec=importlib.util.spec_from_file_location('inventory_previous',ROOT/'scripts/verify-admin-audit.py')
previous=importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)

class InventoryVerification(previous.AuditVerification):
    def __init__(self,mysql,workers,rounds,http_only=False):
        super().__init__(mysql,workers,rounds,http_only=http_only)
        self.meta['verification']='stage07_inventory_and_full_system'
        self.schemas['inventory_upgrade']=self.schemas['order']+'_inventory_upgrade'

    def start_database(self):
        super().start_database()
        old=self.out/'inventory-v6-only';old.mkdir()
        for path in MIGRATIONS.glob('V*.sql'):
            if int(path.name.split('__')[0][1:])<=6: shutil.copy2(path,old)
        db=self.database('inventory_upgrade')
        db.migrate(log=self.out/'inventory-v6.log',locations=old)
        db.query("""INSERT INTO t_product(id,name,price,stock,sales) VALUES(1,'legacy',1,8,2);
            INSERT INTO t_product_sku(product_id,name,spec_json,spec_hash,price,stock,sales,is_default)
            VALUES(1,'legacy','{}',SHA2('{}',256),1,8,2,1)""")
        before=db.query('SELECT * FROM t_product_sku')
        db.migrate(log=self.out/'inventory-v7.log')
        assert db.query('SELECT * FROM t_product_sku')==before
        assert db.query('SELECT quantity,sales_quantity,before_stock,after_stock FROM t_inventory_ledger')==[
            {'quantity':'8','sales_quantity':'2','before_stock':'0','after_stock':'8'}]
        db.migrate(log=self.out/'inventory-repeat.log')
        assert db.query('SELECT COUNT(*) n FROM t_inventory_ledger')==[{'n':'1'}]
        # An old outstanding order can restore against the opening; no fictional pre-migration deduction is added.
        db.query("""START TRANSACTION; UPDATE t_product_sku SET stock=10,sales=0 WHERE product_id=1;
            INSERT INTO t_inventory_ledger(sku_id,event_type,business_no,quantity,sales_quantity,before_stock,after_stock)
            SELECT id,'CANCEL_RESTORE','20260101000001',2,-2,8,10 FROM t_product_sku WHERE product_id=1; COMMIT""")
        db.migrate(log=self.out/'inventory-live-no-reset.log')
        assert db.query('SELECT SUM(quantity) stock,SUM(sales_quantity) sales FROM t_inventory_ledger')==[{'stock':'10','sales':'0'}]
        # Upgrade V1 straight to latest: V3 data copy runs after V7, so migration entry must fill openings afterwards.
        fresh=self.out/'inventory-v1-only';fresh.mkdir();shutil.copy2(MIGRATIONS/'V1__initial_schema.sql',fresh)
        self.schemas['inventory_legacy']=self.schemas['order']+'_inventory_legacy'
        self.sql('CREATE DATABASE '+self.schemas['inventory_legacy'])
        legacy=self.database('inventory_legacy');legacy.migrate(log=self.out/'inventory-v1.log',locations=fresh)
        legacy.query("INSERT INTO t_product(name,price,stock,sales) VALUES('old',1,7,3)")
        legacy.migrate(log=self.out/'inventory-v1-latest.log')
        assert legacy.query('SELECT quantity,sales_quantity FROM t_inventory_ledger')==[{'quantity':'7','sales_quantity':'3'}]
        self.record('v6_to_v7_opening_preserves_skus_and_v1_late_backfill',repeat_safe=True,live_balances_not_reset=True)

    def smoke(self):
        super().smoke()
        self.inventory_business()

    def inventory_business(self):
        root=self.admin_login();self.management_token=root
        pid=self.product(10);sid=int(self.sql('SELECT id FROM t_product_sku WHERE product_id='+str(pid))[0][0])
        base=f'/product/{pid}/skus/{sid}/inventory'
        def adjust(no,q,kind='ADJUSTMENT',expected=200):
            return self.call('product','POST',base+'/adjustments',
                {'businessNo':no,'quantity':q,'type':kind,'reason':'synthetic verification'},token=root,expected=expected)
        first=adjust('inventory-inbound',5,'INBOUND')[1]
        assert adjust('inventory-inbound',5,'INBOUND')[1]==first
        adjust('inventory-inbound',6,'INBOUND',409)
        adjust('inventory-zero',0,expected=400);adjust('inventory-negative',-1,'INBOUND',400)
        adjust('inventory-overflow',2147483647,expected=409);adjust('inventory-short',-16,expected=409)
        self.call('product','POST',base+'/adjustments',{},expected=401)
        self.call('product','GET',base+'/ledger',token=self.tokens[0],expected=401)
        for suffix in ('/ledger?pageSize=101','/ledger?unknown=1','/ledger?type=INVALID','/balance?unknown=1'):
            self.call('product','GET',base+suffix,token=root,expected=400)
        # Cross-SKU same global business key may commit only one adjustment.
        sibling=self.sku(pid,'inventory-sibling',0,1,{'kind':'sibling'})
        self.call('product','POST',f"/product/{pid}/skus/{sibling['id']}/inventory/adjustments",
            {'businessNo':'inventory-inbound','quantity':5,'type':'INBOUND','reason':'synthetic verification'},token=root,expected=409)
        before=self.sql('SELECT stock,sales FROM t_product_sku WHERE id='+str(sid))
        self.sql("CREATE TRIGGER inventory_http_failure BEFORE INSERT ON t_inventory_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic ledger failure'")
        try:
            adjust('inventory-rollback',2,expected=500)
            self.call('order_a','POST','/order',self.order(pid),token=self.tokens[0],expected=500)
        finally: self.sql('DROP TRIGGER inventory_http_failure')
        assert self.sql('SELECT stock,sales FROM t_product_sku WHERE id='+str(sid))==before
        assert self.sql("SELECT COUNT(*) FROM t_inventory_adjustment WHERE business_no='inventory-rollback'")==[['0']]
        # All simultaneous retries with the same business number apply exactly once.
        outcomes=self.race([lambda:adjust('inventory-race-once',1) for _ in range(self.workers)])
        assert len({row[1]['id'] for row in outcomes})==1
        # Adjustment and checkout share product -> SKU order across product and two order JVMs.
        jobs=[]
        for i in range(self.workers):
            if i%2: jobs.append(lambda:adjust('inventory-concurrent-once',1))
            else: jobs.append(lambda i=i:self.call('order_a' if i%4 else 'order_b','POST','/order',self.order(pid),token=self.tokens[i],expected=(200,409)))
        self.race(jobs)
        adjustment=self.data_call('product','GET',base+'/ledger?businessNo=inventory-inbound',token=root)
        assert adjustment['total']==1 and adjustment['records'][0]['reason']=='synthetic verification'
        balance=self.data_call('product','GET',base+'/balance',token=root)
        assert balance['consistent'] and int(balance['stock'])==int(balance['ledgerStock'])
        no=self.data_call('order_a','POST','/order',self.order(pid),token=self.tokens[0])['orderNo']
        self.data_call('order_b','POST',f'/order/{no}/cancel',token=self.tokens[0])
        self.call('order_a','POST',f'/order/{no}/cancel',token=self.tokens[0],expected=409)
        no=self.data_call('order_a','POST','/order',self.order(pid),token=self.tokens[0])['orderNo']
        self.data_call('order_a','POST',f'/order/{no}/pay',token=self.tokens[0])
        self.data_call('order_b','POST',f'/order/{no}/ship',{'logisticsCompany':'Synthetic','trackingNo':'Synthetic'},token=root)
        self.call('order_a','POST',f'/order/{no}/cancel',token=self.tokens[0],expected=409)
        assert self.sql("SELECT COUNT(*) FROM t_inventory_ledger WHERE event_type IN ('CANCEL_RESTORE','REFUND_RESTORE') AND business_no='"+no+"'")==[['0']]
        self.record('inventory_replay_overflow_rollback_races_trace_and_shipped_guard',concurrent_retries=self.workers,refund_api='reserved_for_later_stage')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home',default=os.environ.get('XIAOKE_MYSQL_HOME','D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers',type=int,default=24);parser.add_argument('--rounds',type=int,default=8)
    parser.add_argument('--http-only',action='store_true');args=parser.parse_args()
    if Path.cwd().resolve()!=ROOT: parser.error('Run from repository root')
    if not 4<=args.workers<=48 or args.workers%2 or not 1<=args.rounds<=20: parser.error('workers even 4..48, rounds 1..20')
    InventoryVerification(Path(args.mysql_home).resolve(),args.workers,args.rounds,http_only=args.http_only).run()
