"""Maintenance only: complete V7 openings after resumable V3 SKU copy. Never overwrite a journal."""
def finish(db):
    if not any(row['name']=='t_inventory_ledger' for row in db.objects()['tables']):
        return
    db.query("""INSERT INTO t_inventory_ledger(sku_id,event_type,business_no,quantity,sales_quantity,before_stock,after_stock)
        SELECT s.id,'OPENING','OPENING',s.stock,s.sales,0,s.stock FROM t_product_sku s
        WHERE NOT EXISTS(SELECT 1 FROM t_inventory_ledger l WHERE l.sku_id=s.id) ORDER BY s.id""")
    if db.query("""SELECT COUNT(*) n FROM t_product_sku s LEFT JOIN
        (SELECT sku_id,SUM(event_type='OPENING') n FROM t_inventory_ledger GROUP BY sku_id) l
        ON l.sku_id=s.id WHERE COALESCE(l.n,0)<>1""")[0]['n']!='0':
        raise RuntimeError('Inventory opening missing: keep business writers stopped')
