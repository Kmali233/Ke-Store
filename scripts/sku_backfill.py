"""Maintenance-only, resumable default SKU copy. Called by the guarded migration entry point."""

def finish(db, batch_size=200, after_batch=None):
    objects = db.objects()
    if not any(row['name'] == 't_catalog_migration' for row in objects['tables']):
        return
    if db.query('SELECT version FROM t_catalog_migration WHERE version=3'):
        require_ready(db)
        return
    invalid = db.query('SELECT COUNT(*) n FROM t_product WHERE stock<0 OR sales<0 OR price<0')[0]['n']
    if invalid != '0':
        raise RuntimeError('Invalid legacy price/counters: default SKU backfill refused')
    cursor = 0
    batches = 0
    while True:
        ids = db.query(f'SELECT id FROM t_product WHERE id>{cursor} ORDER BY id LIMIT {int(batch_size)}')
        if not ids:
            break
        upper = int(ids[-1]['id'])
        # Each INSERT is a committed batch. Existing default rows are never overwritten on resume.
        db.query(f'''INSERT INTO t_product_sku
            (product_id,name,spec_json,spec_hash,price,stock,sales,status,deleted,is_default)
            SELECT p.id,'默认规格','{{}}',SHA2('{{}}',256),p.price,p.stock,p.sales,1,0,1
            FROM t_product p WHERE p.id>{cursor} AND p.id<={upper}
            AND NOT EXISTS(SELECT 1 FROM t_product_sku s WHERE s.product_id=p.id AND s.is_default=1)
            ORDER BY p.id''')
        cursor = upper
        batches += 1
        if after_batch:
            after_batch(batches)
    require_ready(db)
    mismatch = db.query('''SELECT COUNT(*) n FROM t_product p JOIN t_product_sku s
        ON s.product_id=p.id AND s.is_default=1
        WHERE s.price<>p.price OR s.stock<>p.stock OR s.sales<>p.sales OR s.deleted<>0''')[0]['n']
    if mismatch != '0':
        raise RuntimeError('Legacy/default SKU reconciliation mismatch: keep maintenance mode; do not overwrite existing SKU data')
    db.query('INSERT INTO t_catalog_migration(version) VALUES(3)')
    print(f'Default SKU backfill complete; {batches} batches reconciled. Old writers must stay stopped.', flush=True)


def require_ready(db):
    missing = db.query('''SELECT COUNT(*) n FROM t_product p LEFT JOIN t_product_sku s
        ON s.product_id=p.id AND s.is_default=1 WHERE s.id IS NULL OR s.deleted<>0''')[0]['n']
    if missing != '0':
        raise RuntimeError('Default SKU missing: do not start business writers')
