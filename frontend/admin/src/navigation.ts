export const sections = [
  { name: '商品管理', entries: [
    { path: '/products', title: '商品与规格', permission: 'product:write', icon: 'box', summary: '维护商品资料、上架状态和 SKU 规格。' },
    { path: '/categories', title: '分类管理', permission: 'product:write', icon: 'grid', summary: '组织商城目录，管理父子分类与显示顺序。' },
  ] },
  { name: '订单履约', entries: [
    { path: '/inventory', title: '库存调整', permission: 'inventory:adjust', icon: 'layers', summary: '按 SKU 调整库存、核对余额与流水。' },
    { path: '/orders', title: '订单履约', permission: 'order:ship', icon: 'truck', summary: '查询订单及收货信息，登记发货。' },
  ] },
  { name: '访问控制', entries: [
    { path: '/audit', title: '审计日志', permission: 'audit:read', icon: 'list', summary: '检索管理操作并核对变更内容。' },
    { path: '/accounts', title: '账号与角色', permission: 'admin:manage', icon: 'users', summary: '创建账号、分配角色与管理启停状态。' },
  ] },
]

export function safeNext(value: unknown) {
  return typeof value === 'string' && /^\/(?![/\\])/.test(value) ? value : '/'
}
