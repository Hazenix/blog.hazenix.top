import { randomUUID } from 'node:crypto'
import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve, sep } from 'node:path'
import { articlePath } from '../src/utils/articleUrl.js'

export function articlePages(articles) {
  return [...new Set(articles.filter(article => String(article.id) !== '1').map(articlePath))]
}

export async function renderSnapshot(browser, baseUrl, outputDir, path, publicOrigin) {
  const url = new URL(path, baseUrl)
  const pathname = decodeURIComponent(url.pathname)
  if (!path.startsWith('/') || url.origin !== new URL(baseUrl).origin || url.search || url.hash
      || /[\\\p{Cc}]/u.test(pathname) || pathname.split('/').some(part => part === '.' || part === '..')) {
    throw new Error('无效的预渲染路径')
  }

  const root = resolve(outputDir)
  const output = resolve(root, pathname === '/' ? 'index.html' : '.' + pathname + '.html')
  if (!output.startsWith(root + sep)) throw new Error('预渲染路径超出输出目录')

  const page = await browser.newPage()
  const staged = output + '.' + randomUUID() + '.tmp'
  try {
    await page.setRequestInterception(true)
    page.on('request', request => {
      const hostname = new URL(request.url()).hostname
      if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())
          || ['monitor.hazenix.top', 'www.googletagmanager.com', 'www.google-analytics.com'].includes(hostname)) {
        return request.abort()
      }
      return request.continue()
    })

    const response = await page.goto(url.href, { waitUntil: 'networkidle0', timeout: 30000 })
    if (!response?.ok() || new URL(page.url()).pathname !== url.pathname) {
      throw new Error('页面加载失败或跳转到了其他地址')
    }
    if (pathname.startsWith('/article/')) {
      await page.waitForSelector('article .article-title', { timeout: 5000 })
      await page.waitForSelector('article .markdown-content, article .moment-images-section', { timeout: 5000 })
      const canonical = await page.$eval('link[rel="canonical"]', element => element.href)
      if (decodeURIComponent(new URL(canonical).pathname) !== pathname) {
        throw new Error('文章 canonical 与预渲染路径不一致')
      }
    }

    // Vue 使用 createApp，启动后会重建 DOM。爬虫快照仅保留结构化数据脚本。
    await page.evaluate(origin => {
      document.querySelectorAll('script:not([type="application/ld+json"]), link[rel="modulepreload"]')
        .forEach(node => node.remove())
      for (const element of document.querySelectorAll('link[rel="canonical"], meta[property^="og:"]')) {
        const attribute = element.tagName === 'LINK' ? 'href' : 'content'
        const value = element.getAttribute(attribute)
        if (value?.startsWith(location.origin + '/')) {
          element.setAttribute(attribute, origin + value.slice(location.origin.length))
        }
      }
      for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
        script.textContent = script.textContent.replaceAll(location.origin + '/', origin + '/')
      }
    }, publicOrigin)

    await mkdir(dirname(output), { recursive: true })
    await writeFile(staged, await page.content(), 'utf8')
    await rename(staged, output)
  } finally {
    await page.close()
    await rm(staged, { force: true })
  }
}
