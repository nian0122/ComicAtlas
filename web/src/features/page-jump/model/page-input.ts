/** 拒绝小数、空值、非有限数与越界页码，避免跳到不存在的媒体。 */
export function parsePageInput(input: string | number, total: number): number | null {
  if (!/^\d+$/.test(String(input).trim())) return null
  const page = Number(input)
  return Number.isSafeInteger(page) && page >= 1 && page <= total ? page : null
}
