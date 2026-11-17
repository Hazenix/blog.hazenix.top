export function articleIdentifier({ id, slug }) {
  // 数字路径一直按文章 ID 解析，无法作为独立的 slug。
  if (typeof slug === 'string' && slug.trim() && !/^\d+$/.test(slug)
      && slug !== '.' && slug !== '..' && !/[\\/\p{Cc}]/u.test(slug)) {
    return slug
  }
  return String(id)
}

export function articlePath(article) {
  const identifier = encodeURIComponent(articleIdentifier(article))
    .replace(/[!'()*]/g, char => '%' + char.charCodeAt(0).toString(16).toUpperCase())
  return '/article/' + identifier
}
