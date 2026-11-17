/**
 * 预渲染脚本 — 构建时生成静态 HTML，供 Nginx 直接返回给爬虫
 *
 * 用法：
 *   node scripts/prerender.js [baseUrl]
 *
 *   baseUrl 指定只读内容 API，页面始终使用本次构建的 dist
 *   本地测试：node scripts/prerender.js http://localhost:5173
 *
 * 流程：
 *   1. 从 API 获取所有 slugs / categories / tags
 *   2. Puppeteer 渲染每个页面，等待 networkidle0
 *   3. 移除应用脚本并保留 JSON-LD
 *   4. 保存到 dist/prerender/，目录结构对应 URL 路径
 */

let puppeteer
try {
  puppeteer = (await import('puppeteer')).default
} catch {
  console.log('[prerender] puppeteer 未安装，跳过预渲染。npm install puppeteer 后重试。')
  process.exit(1)
}

// 优先用系统 Chrome（可用 puppeteer 安装的 Chrome 常因网络问题安装失败）
const CHROME_PATHS = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/usr/bin/chromium',
]
import { execSync } from 'node:child_process'
function findChrome() {
  for (const p of CHROME_PATHS) {
    try { execSync(`test -x "${p}"`); return p } catch {}
  }
  return null
}

import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { preview } from 'vite'
import { articlePages, renderSnapshot } from './prerenderPage.js'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DIST_DIR = join(__dirname, '..', 'dist')
const PRERENDER_DIR = join(DIST_DIR, 'prerender')

const BASE_URL = process.argv[2] || process.env.PRERENDER_BASE_URL || 'https://blog.hazenix.top'
const API_BASE = new URL('/api', BASE_URL).href
const PUBLIC_ORIGIN = new URL(process.env.PRERENDER_PUBLIC_URL || 'https://blog.hazenix.top').origin

// ========== 页面列表 ==========

const STATIC_PAGES = [
  // `/` 不预渲染 —— 着陆页走独立的 landing.html 入口（已是真静态 HTML）
  '/home',
  '/articles',
  '/categories',
  '/tags',
  '/friend-links',
  '/tree-hole',
  '/messageboard',
  '/about',
  '/about-project',
  '/history',
]

// ========== 数据获取 ==========

async function fetchJSON(path) {
  const url = API_BASE + path
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url} → ${res.status}`)
  const json = await res.json()
  return json.data || json
}

async function collectPages() {
  const pages = [...STATIC_PAGES]

  // 文章页
  const slugs = await fetchJSON('/user/articles/slugs')
  pages.push(...articlePages(slugs))
  console.log(`  [slugs] ${slugs.length} 篇文章`)

  // 分类页
  try {
    const categories = await fetchJSON('/user/categories')
    for (const { id } of categories) {
      pages.push(`/category/${id}`)
    }
    console.log(`  [categories] ${categories.length} 个分类`)
  } catch (e) {
    console.warn(`  [categories] 获取失败: ${e.message}`)
  }

  // 标签页
  try {
    const tags = await fetchJSON('/user/tags')
    for (const { id } of tags) {
      pages.push(`/tag/${id}`)
    }
    console.log(`  [tags] ${tags.length} 个标签`)
  } catch (e) {
    console.warn(`  [tags] 获取失败: ${e.message}`)
  }

  return pages
}

// 构建阶段从本机 dist 渲染，避免使用上个线上版本的页面和资源。
async function main() {
  const pages = await collectPages()
  const server = await preview({
    root: join(__dirname, '..'),
    preview: {
      host: '127.0.0.1',
      port: 0,
      proxy: { '/api': { target: new URL(BASE_URL).origin, changeOrigin: true } },
    },
  })
  let browser
  let failures = 0
  try {
    const chromePath = findChrome()
    browser = await puppeteer.launch({
      executablePath: chromePath || undefined,
      args: process.getuid?.() === 0 ? ['--no-sandbox', '--disable-setuid-sandbox'] : [],
    })
    const origin = `http://127.0.0.1:${server.httpServer.address().port}`
    const concurrency = 4
    for (let index = 0; index < pages.length; index += concurrency) {
      await Promise.all(pages.slice(index, index + concurrency).map(async path => {
        try {
          await renderSnapshot(browser, origin, PRERENDER_DIR, path, PUBLIC_ORIGIN)
        } catch (error) {
          failures++
          console.warn(`  ✗ ${path}: ${error.message}`)
        }
      }))
    }
  } finally {
    await browser?.close()
    server.httpServer.close()
  }
  console.log(`预渲染完成: ${pages.length - failures} 成功, ${failures} 失败`)
  if (failures) process.exitCode = 1
}

main().catch(error => {
  console.error(error)
  process.exitCode = 1
})
