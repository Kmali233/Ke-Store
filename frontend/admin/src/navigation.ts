export const sections = [
  { name: '商品管理', entries: [
    { path: '/products', title: '商品与规格', permission: 'product:write', icon: 'box', summary: '维护商品资料、上架状态和 SKU 规格。' },
    { path: '/categories', title: '分类管理', permission: 'product:write', icon: 'grid', summary: '组织商城目录，管理父子分类与显示顺序。' },
  ] },
  { name: '交易管理', entries: [
    { path: '/inventory', title: '库存调整', permission: 'inventory:adjust', icon: 'layers', summary: '按 SKU 调整库存、核对余额与流水。' },
    { path: '/refunds', title: '退款审核', permission: 'refund:review', icon: 'list', summary: '审核整单退款，查看退款进度。' },
    { path: '/reconciliation', title: '交易对账', permission: 'trade:reconcile', icon: 'list', summary: '核对支付与退款单，重查通道差异。' },
    { path: '/orders', title: '订单查询', permission: 'order:read', icon: 'list', summary: '按订单号和状态查询订单。' },
  ] },
  { name: '访问控制', entries: [
    { path: '/audit', title: '审计日志', permission: 'audit:read', icon: 'list', summary: '检索管理操作并核对变更内容。' },
    { path: '/accounts', title: '账号与角色', permission: 'admin:manage', icon: 'users', summary: '创建账号、分配角色与管理启停状态。' },
  ] },
]

export function safeNext(value: unknown) {
  return typeof value === 'string' && /^\/(?![/\\])/.test(value) ? value : '/'
}
