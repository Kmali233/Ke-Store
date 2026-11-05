"""Stage06: same-transaction management audit, redaction, access control and full prior regressions.
Run at repository root; all databases and processes are isolated.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import urllib.parse
from db_migrate import ROOT, MIGRATIONS

spec=importlib.util.spec_from_file_location('audit_previous',ROOT/'scripts/verify-admin-rbac.py')
previous=importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)


class AuditVerification(previous.RbacVerification):
    def __init__(self,mysql,workers,rounds,http_only=False):
        super().__init__(mysql,workers,rounds,http_only=http_only)
        self.meta['verification']='stage06_management_audit_and_full_system'
        self.schemas['audit_upgrade']=self.schemas['order']+'_audit_upgrade'
        self.audit_markers=['Synthetic-Audit-Private-Description','Synthetic-Audit-Tracking-Value']

    def start_database(self):
        super().start_database()
        old=self.out/'audit-v5-only'
        old.mkdir()
        for path in MIGRATIONS.glob('V*.sql'):
            if int(path.name.split('__')[0][1:])<=5:
                shutil.copy2(path,old)
        db=self.database('audit_upgrade')
        db.migrate(log=self.out/'audit-v5-baseline.log',locations=old)
        db.query("INSERT INTO t_admin_account(id,username,password) VALUES(1,'system_actor','unused'),(2,'catalog_actor','unused');"
                 "INSERT INTO t_admin_account_role(admin_id,role_id) VALUES(1,1),(2,2)")
        before=db.query('SELECT * FROM t_admin_account ORDER BY id')
        roles=db.query('SELECT * FROM t_admin_account_role ORDER BY admin_id')
        db.migrate(log=self.out/'audit-v6-upgrade.log')
        assert db.query('SELECT * FROM t_admin_account ORDER BY id')==before
        assert db.query('SELECT * FROM t_admin_account_role ORDER BY admin_id')==roles
        assert db.query("SELECT role_id FROM t_admin_role_permission WHERE permission_code='audit:read'")==[{'role_id':'1'}]
        assert db.query('SELECT COUNT(*) n FROM t_admin_audit')==[{'n':'0'}]
        db.migrate(log=self.out/'audit-v6-repeat.log')
        assert db.query("SELECT COUNT(*) n FROM t_admin_permission WHERE code='audit:read'")==[{'n':'1'}]
        self.record('v5_to_v6_preserves_accounts_grants_only_system_audit_read',empty_audit=True,repeat_safe=True)

    def smoke(self):
        super().smoke()
        self.audit_business()

    def audit_page(self,token,**query):
        return self.data_call('admin','GET','/admin/audit/page?'+urllib.parse.urlencode(query),token=token)

    def audit_business(self):
        root=self.admin_login()
        self.management_token=root
        staff=self.data_call('admin','POST','/admin/access/accounts',{'username':'audit_reader_staff','password':self.admin_password},token=root)
        sid=staff['id']
        self.data_call('admin','PUT',f'/admin/access/accounts/{sid}/roles',{'roleIds':[2]},token=root)
        staff_token=self.data_call('admin','POST','/admin/auth/login',{'username':'audit_reader_staff','password':self.admin_password})['token']
        self.admin_tokens.append(staff_token)
        self.call('admin','GET','/admin/audit/page',expected=401)
        self.call('admin','GET','/admin/audit/page',token=self.tokens[0],expected=401)
        self.call('admin','GET','/admin/audit/page',token=staff_token,expected=403)
        pid=self.product(4)
        self.data_call('product','PUT','/product',{'id':pid,'price':20,'description':self.audit_markers[0]},token=staff_token)
        row=self.sql("SELECT actor_id,trace_id,changes_json FROM t_admin_audit WHERE action='PRODUCT_PRICE_CHANGE' AND target_id="+str(pid))[0]
        assert row[0]==str(sid) and len(row[1])==32
        page=self.audit_page(root,actorId=sid,targetType='PRODUCT',targetId=pid,action='PRODUCT_PRICE_CHANGE',traceId=row[1])
        assert page['total']==1 and page['records'][0]['integrityValid']
        changes=page['records'][0]['changes']['PRICE']
        assert changes=={'before':'12.34','after':'20'}
        before=int(self.sql('SELECT COUNT(*) FROM t_admin_audit')[0][0])
        self.data_call('product','PUT','/product',{'id':pid,'price':20},token=staff_token)
        assert int(self.sql('SELECT COUNT(*) FROM t_admin_audit')[0][0])==before
        self.sql("CREATE TRIGGER verify_http_audit_failure BEFORE INSERT ON t_admin_audit FOR EACH ROW "
                 "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'")
        try:
            self.call('product','PUT','/product',{'id':pid,'price':99},token=staff_token,expected=500)
            self.call('admin','PUT',f'/admin/access/accounts/{sid}/roles',{'roleIds':[3]},token=root,expected=500)
        finally:
            self.sql('DROP TRIGGER verify_http_audit_failure')
        assert self.sql('SELECT price FROM t_product_sku WHERE product_id='+str(pid))[0][0]=='20.00'
        assert self.sql('SELECT role_id FROM t_admin_account_role WHERE admin_id='+str(sid))==[['2']]
        assert int(self.sql('SELECT COUNT(*) FROM t_admin_audit')[0][0])==before
        order=self.data_call('order_a','POST','/order',self.order(pid),token=self.tokens[0])
        no=order['orderNo']
        self.data_call('order_a','POST',f'/order/{no}/pay',token=self.tokens[0])
        shipping={'logisticsCompany':'Synthetic','trackingNo':self.audit_markers[1]}
        self.sql("CREATE TRIGGER verify_http_ship_audit BEFORE INSERT ON t_admin_audit FOR EACH ROW "
                 "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic ship audit failure'")
        try:
            self.call('order_b','POST',f'/order/{no}/ship',shipping,token=root,expected=500)
        finally:
            self.sql('DROP TRIGGER verify_http_ship_audit')
        assert self.sql("SELECT status FROM t_orders WHERE order_no='"+no+"'")==[['1']]
        outcomes=self.race([lambda i=i:self.call('order_a' if i%2 else 'order_b','POST',f'/order/{no}/ship',shipping,
                                               token=root,expected=(200,409)) for i in range(self.workers)])
        assert sum(code==200 for code,_ in outcomes)==1
        entries=self.audit_page(root,actorId=1,targetType='ORDER',targetId=no,action='ORDER_SHIP')
        assert entries['total']==1 and entries['records'][0]['integrityValid']
        assert entries['records'][0]['changes']['STATUS']=={'before':1,'after':3}
        self.call('admin','DELETE','/admin/audit/page',token=root,expected=405)
        for query in ('pageSize=101','pageNum=1001','actorId=0','action=UNKNOWN','traceId=invalid',
                      'from=2026-01-01T00:00:00Z&to=2026-04-01T00:00:00Z','unknown=1'):
            self.call('admin','GET','/admin/audit/page?'+query,token=root,expected=400)
        self.sql("UPDATE t_admin_audit SET changes_json=JSON_OBJECT('STATUS',JSON_OBJECT('before',1,'after',99)) "
                 "WHERE action='ORDER_SHIP' AND target_id='"+no+"'")
        invalid=self.audit_page(root,targetType='ORDER',targetId=no)['records'][0]
        assert not invalid['integrityValid'] and invalid['changes'] is None
        self.record('audit_same_transaction_before_after_trace_query_and_redaction',actor_filter=True,order_filter=True,
                    rollback_product_and_roles=True,shipping_rollback=True,concurrent_shipping_successes=1,
                    no_duplicate_success=True,noop_suppressed=True,tamper_detected=True,query_permission=True)

    def audit_logs(self):
        super().audit_logs()
        files=list((self.out/'security').glob('*.log'))
        assert len(files)>=4, 'Expected isolated security files for admin/product/two order JVMs'
        content='\n'.join(path.read_text(encoding='utf-8',errors='replace') for path in files)
        assert 'result=REJECTED' in content and 'result=READ_SUCCESS' in content and 'operation=audit:read' in content
        assert 'http=503' in content, 'Security stream must survive a real database outage'
        assert 'traceId=' in content and 'actorId=' in content
        for path in self.out.rglob('*.log'):
            text=path.read_text(encoding='utf-8',errors='replace')
            assert not any(marker in text for marker in self.audit_markers), ('sensitive data in logs',path.name)
        self.record('independent_security_files_survive_db_outage_and_cover_sensitive_reads',files=len(files),
                    no_request_payloads=True,success_rows_separate_from_security_stream=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-home',default=os.environ.get('XIAOKE_MYSQL_HOME','D:/Develop/mysql-8.0.34-winx64'))
    parser.add_argument('--workers',type=int,default=24)
    parser.add_argument('--rounds',type=int,default=8)
    parser.add_argument('--http-only',action='store_true')
    args=parser.parse_args()
    if Path.cwd().resolve()!=ROOT: parser.error('Run from repository root')
    if not 4<=args.workers<=48 or args.workers%2 or not 1<=args.rounds<=20: parser.error('workers even 4..48, rounds 1..20')
    AuditVerification(Path(args.mysql_home).resolve(),args.workers,args.rounds,http_only=args.http_only).run()
