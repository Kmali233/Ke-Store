const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

export function money(value: number | null) {
  return value == null ? '—' : currency.format(value)
}

export function specsLabel(specs: Record<string, string> | null) {
  return Object.entries(specs ?? {}).map(([name, value]) => `${name} ${value}`).join(' · ')
}

export function snapshotSpecs(value: string | null) {
  try {
    return specsLabel(JSON.parse(value ?? '{}'))
  } catch {
    return ''
  }
}

export function dateLabel(value: string) {
  return value.replace('T', ' ').slice(0, 19)
}
