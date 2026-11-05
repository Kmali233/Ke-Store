"""Stage05: isolated V4->V5 upgrade, all JUnit/transaction/HTTP regressions and RBAC across JVMs.
Run from the repository root: python scripts/verify-admin-rbac.py
"""
import argparse
import importlib.util
import os
from pathlib import Path
import shutil
from db_migrate import ROOT, MIGRATIONS

spec = importlib.util.spec_from_file_location('admin_auth_verification', ROOT / 'scripts/verify-admin-auth.py')
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
system = previous.system


class RbacVerification(previous.AdminVerification):
    def __init__(self, mysql, workers, rounds, http_only=False):
        super().__init__(mysql, workers, rounds, http_only=http_only)
        self.meta['verification'] = 'stage05_rbac_and_full_system'
        self.schemas['rbac_upgrade'] = self.schemas['order'] + '_rbac_upgrade'

    def start_database(self):
        super().start_database()
        old = self.out / 'v4-only-migrations'
        old.mkdir()
        for path in MIGRATIONS.glob('V*.sql'):
            if int(path.name.split('__')[0][1:]) <= 4:
                shutil.copy2(path, old)
        db = self.database('rbac_upgrade')
        db.migrate(log=self.out / 'rbac-upgrade-v4.log', locations=old)
        db.query("INSERT INTO t_admin_account(id,username,password,status) VALUES "
                 "(1,'disabled_admin','synthetic-unused',0),(2,'active_admin','synthetic-unused',1),"
                 "(4,'other_admin','synthetic-unused',1)")
        before = db.query('SELECT * FROM t_admin_account ORDER BY id')
        v5 = self.out / 'v5-only-migrations'
        v5.mkdir()
        for path in MIGRATIONS.glob('V*.sql'):
            if int(path.name.split('__')[0][1:]) <= 5:
                shutil.copy2(path,v5)
        db.migrate(log=self.out / 'rbac-upgrade-v5.log',locations=v5)
        assert db.query('SELECT * FROM t_admin_account ORDER BY id') == before
        assert db.query('SELECT * FROM t_admin_account_role') == [{'admin_id':'2','role_id':'1'}]
        assert len(db.query('SELECT * FROM t_admin_role')) == 4
        assert len(db.query('SELECT * FROM t_admin_permission')) == 5
        db.migrate(log=self.out / 'rbac-upgrade-repeat.log',locations=v5)
        assert db.query('SELECT * FROM t_admin_account_role') == [{'admin_id':'2','role_id':'1'}]
        self.record('v4_to_v5_preserves_accounts_only_first_active_gets_system_role', active_accounts=2,
                    granted_accounts=1, disabled_account_granted=False, repeat_does_not_duplicate=True)

    def smoke(self):
        super().smoke()
        self.rbac()

    def rbac(self):
        root = self.admin_login()
        self.management_token = root
        roles = self.data_call('admin','GET','/admin/access/roles',token=root)
        assert [r['code'] for r in roles] == ['system_admin','product_admin','fulfillment','after_sales']
        actors = {}
        for name, role in (('catalog_actor',2),('fulfillment_actor',3),('support_actor',4),('empty_actor',None)):
            row = self.data_call('admin','POST','/admin/access/accounts',
                                 {'username':name,'password':self.admin_password},token=root)
            if role:
                self.data_call('admin','PUT',f"/admin/access/accounts/{row['id']}/roles",{'roleIds':[role]},token=root)
            token = self.data_call('admin','POST','/admin/auth/login',{'username':name,'password':self.admin_password})['token']
            self.admin_tokens.append(token)
            actors[name] = (row['id'], token)
            identity = self.data_call('admin','GET','/admin/access/me',token=token)
            expected = set(next(r['permissions'] for r in roles if r['id']==role)) if role else set()
            assert set(identity['permissions']) == expected
            self.call('admin','GET','/admin/access/accounts',token=token,expected=403)
            self.call('admin','PUT',f"/admin/access/accounts/{row['id']}/roles",{'roleIds':[1]},token=token,expected=403)
        # Every current management route, with a malformed body to verify authorization precedes parsing.
        routes = [('product',m,p) for m,p in (
            ('POST','/product'),('PUT','/product'),('DELETE','/product/99999999'),
            ('POST','/product/99999999/attrs'),('POST','/category'),('PUT','/category'),
            ('DELETE','/category/99999999'),('POST','/product/99999999/skus'),
            ('PUT','/product/99999999/skus/99999999'),('DELETE','/product/99999999/skus/99999999'))]
        routes += [(app,'POST','/order/20260101000001/ship') for app in ('order_a','order_b')]
        for name, (_, token) in actors.items():
            for app, method, path in routes:
                allowed = (app=='product' and name=='catalog_actor') or (app.startswith('order_') and name=='fulfillment_actor')
                self.call(app,method,path,None if method=='DELETE' else {},token=token,
                          expected=(400,404) if allowed else 403)
        for app,method,path in routes:
            self.call(app,method,path,None if method=='DELETE' else {},expected=401)
            self.call(app,method,path,None if method=='DELETE' else {},token=self.tokens[0],expected=401)
            self.call(app,method,path,None if method=='DELETE' else {},headers={'X-Local-Admin-Key':self.key},expected=401)
        catalog_id,catalog = actors['catalog_actor']
        self.data_call('product','POST','/product',{'name':'rbac-visible-product','price':3,'stock':2},token=catalog)
        pid = int(self.sql("SELECT id FROM t_product WHERE name='rbac-visible-product'")[0][0])
        self.initial_stock[pid] = 2
        order = self.data_call('order_a','POST','/order',self.order(pid),token=self.tokens[0])
        self.data_call('order_a','POST',f"/order/{order['orderNo']}/pay",token=self.tokens[0])
        ship_id,ship = actors['fulfillment_actor']
        self.data_call('order_b','POST',f"/order/{order['orderNo']}/ship",{'logisticsCompany':'Synthetic','trackingNo':'RBAC1'},token=ship)
        assert self.data_call('order_a','GET',f"/order/{order['orderNo']}/status",token=self.tokens[0])['status']==3
        self.data_call('admin','PUT',f'/admin/access/accounts/{catalog_id}/roles',{'roleIds':[]},token=root)
        self.call('product','POST','/product',{},token=catalog,expected=403)
        self.data_call('admin','PUT',f'/admin/access/accounts/{ship_id}/roles',{'roleIds':[]},token=root)
        for app in ('order_a','order_b'):
            self.call(app,'POST','/order/20260101000001/ship',{},token=ship,expected=403)
        self.data_call('admin','PUT',f'/admin/access/accounts/{ship_id}/status',{'status':0},token=root)
        self.call('admin','GET','/admin/access/me',token=ship,expected=401)
        self.data_call('admin','PUT',f'/admin/access/accounts/{ship_id}/status',{'status':1},token=root)
        self.call('order_a','POST','/order/20260101000001/ship',{},token=ship,expected=401)
        self.call('admin','PUT','/admin/access/accounts/1/roles',{'roleIds':[]},token=root,expected=409)
        self.call('admin','PUT','/admin/access/accounts/1/status',{'status':0},token=root,expected=409)
        self.record('all_management_routes_four_role_matrix_no_menu_or_local_key_bypass', routes=len(routes),
                    role_accounts=4, real_product_write=True, real_shipping=True, revocation_across_jvms=True)

    def real_service_failures(self):
        # Reuse the full shutdown/restart suite and include the privileged routes during the outage.
        original = self.call
        checked = False
        def call(app, method, path, *args, **kwargs):
            nonlocal checked
            result = original(app,method,path,*args,**kwargs)
            if not checked and app=='admin' and path=='/admin/auth/me' and result[0]==503:
                checked = True
                for target, route in (('product','/product'),('order_a','/order/20260101000001/ship'),
                                      ('order_b','/order/20260101000001/ship'),('admin','/admin/access/accounts')):
                    original(target,'POST',route,{},token=self.management_token,expected=503)
                self.record('rbac_database_outage_fails_closed_on_all_write_services', services=4, http=503)
            return result
        self.call = call
        try:
            super().real_service_failures()
            assert checked
        finally:
            self.call = original


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home',default=os.environ.get('XIAOKE_MYSQL_HOME','D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers',type=int,default=24)
    parser.add_argument('--rounds',type=int,default=8)
    parser.add_argument('--http-only',action='store_true',
                        help='Reuse verified JARs; fresh migrated DB and all HTTP/races/faults, no Maven or historical migration demos')
    args=parser.parse_args()
    if Path.cwd().resolve()!=ROOT:
        parser.error('Run from repository root')
    if not 4<=args.workers<=48 or args.workers%2 or not 1<=args.rounds<=20:
        parser.error('workers must be even 4..48, rounds 1..20')
    RbacVerification(Path(args.mysql_home).resolve(),args.workers,args.rounds,http_only=args.http_only).run()
