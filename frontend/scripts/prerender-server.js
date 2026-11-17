/**
 * 预渲染服务 — 独立 HTTP 服务，接收 webhook 调用进行增量页面重渲染
 *
 * 启动：
 *   PRERENDER_BASE_URL=https://blog.hazenix.top \
 *   PRERENDER_OUT_DIR=/path/to/frontend/dist \
 *   node scripts/prerender-server.js
 *
 * 端口：默认 3001（通过 PORT 环境变量配置）
 *
 * API：
 *   POST /render?path=/article/slug      — 渲染单个页面
 *   POST /render-all                     — 全量重新渲染
 *   POST /render-affected?type=article&id=1  — 渲染受影响页面（文章→首页+分类页+文章页）
 *   GET  /health                         — 健康检查
 */

import http from 'node:http'
import { join, dirname } from 'node:path'
import { articlePages, renderSnapshot } from './prerenderPage.js'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

const PORT = process.env.PORT || 3001
const BASE_URL = process.env.PRERENDER_BASE_URL || 'https://blog.hazenix.top'
const OUT_DIR = process.env.PRERENDER_OUT_DIR || join(__dirname, '..', 'dist')
const PRERENDER_DIR = join(OUT_DIR, 'prerender')
const API_BASE = new URL('/api', BASE_URL).href
const PUBLIC_ORIGIN = new URL(process.env.PRERENDER_PUBLIC_URL || 'https://blog.hazenix.top').origin

// ========== 浏览器单例 ==========

import { execSync } from 'node:child_process'

const CHROME_PATHS = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/usr/bin/chromium',
]

function findChrome() {
  for (const p of CHROME_PATHS) {
    try { execSync(`test -x "${p}"`); return p } catch {}
  }
  return null
}

let browserPromise = null

function getBrowser() {
  if (!browserPromise) {
    browserPromise = import('puppeteer').then(({ default: puppeteer }) => {
      const opts = { headless: true, args: ['--no-sandbox', '--disable-setuid-sandbox'] }
      const chromePath = findChrome()
      if (chromePath) {
        opts.executablePath = chromePath
        console.log(`[prerender-server] 使用系统 Chrome: ${chromePath}`)
      }
      return puppeteer.launch(opts)
    })
  }
  return browserPromise
}

async function renderPage(browser, path) {
  await renderSnapshot(browser, BASE_URL, PRERENDER_DIR, path, PUBLIC_ORIGIN)
}

// ========== 受影响的页面计算 ==========

async function getAffectedPaths(type, id) {
  const paths = []

  // 文章列表和首页都会展示最近的内容。
  paths.push('/home', '/articles')

  if (type === 'article') {
    try {
      const res = await fetch(`${API_BASE}/user/articles/${id}`)
      if (!res.ok) return paths
      const json = await res.json()
      const article = json.data || json
      if (article.id) {
        paths.push(...articlePages([article]))
        if (article.categoryId || article.category?.id) {
          const cid = article.categoryId || article.category?.id
          paths.push(`/category/${cid}`)
        }
        if (article.tags) {
          for (const tag of article.tags) {
            const tid = typeof tag === 'object' ? tag.id : tag
            if (tid) paths.push(`/tag/${tid}`)
          }
        }
      }
    } catch (e) {
      console.error(`获取文章 ${id} 失败:`, e.message)
    }
  } else if (type === 'category') {
    paths.push(`/category/${id}`)
    paths.push('/categories')
  } else if (type === 'tag') {
    paths.push(`/tag/${id}`)
    paths.push('/tags')
  }

  return paths
}

// ========== HTTP 服务器 ==========

const STATIC_PAGES = [
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

async function collectAllPages() {
  const pages = [...STATIC_PAGES]
  try {
    const res = await fetch(`${API_BASE}/user/articles/slugs`)
    const data = await res.json()
    const slugs = data.data || data || []
    pages.push(...articlePages(slugs))
  } catch (e) { console.error('获取 slugs 失败:', e.message) }
  try {
    const res = await fetch(`${API_BASE}/user/categories`)
    const data = await res.json()
    const cats = data.data || data || []
    for (const { id } of cats) pages.push(`/category/${id}`)
  } catch (e) { console.error('获取 categories 失败:', e.message) }
  try {
    const res = await fetch(`${API_BASE}/user/tags`)
    const data = await res.json()
    const tags = data.data || data || []
    for (const { id } of tags) pages.push(`/tag/${id}`)
  } catch (e) { console.error('获取 tags 失败:', e.message) }
  return pages
}

async function handleRequest(req, res) {
  const url = new URL(req.url, `http://localhost:${PORT}`)

  // 健康检查
  if (req.method === 'GET' && url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ status: 'ok' }))
    return
  }

  // 需要 POST 的端点
  if (req.method !== 'POST') {
    res.writeHead(405)
    res.end('Method Not Allowed')
    return
  }

  const startTime = Date.now()
  try {
    const browser = await getBrowser()
    if (url.pathname === '/render-all') {
      console.log('[prerender-server] 全量渲染开始...')
      const pages = await collectAllPages()
      let ok = 0, fail = 0
      for (const path of pages) {
        try {
          await renderPage(browser, path)
          ok++
        } catch (error) {
          fail++
          console.warn(`[prerender-server] ${path}: ${error.message}`)
        }
      }
      const elapsed = Date.now() - startTime
      console.log(`[prerender-server] 全量完成: ${ok} 成功, ${fail} 失败 (${elapsed}ms)`)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok, fail, elapsed }))

    } else if (url.pathname === '/render') {
      const path = url.searchParams.get('path')
      if (!path) {
        res.writeHead(400)
        res.end('Missing ?path= parameter')
        return
      }
      console.log(`[prerender-server] 渲染: ${path}`)
      await renderPage(browser, path)
      const elapsed = Date.now() - startTime
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok: true, elapsed }))

    } else if (url.pathname === '/render-affected') {
      const type = url.searchParams.get('type')
      const id = url.searchParams.get('id')
      if (!type || !id) {
        res.writeHead(400)
        res.end('Missing ?type= or ?id=')
        return
      }
      console.log(`[prerender-server] 增量渲染: ${type}/${id}`)
      const pages = await getAffectedPaths(type, id)
      let ok = 0, fail = 0
      for (const path of pages) {
        try {
          await renderPage(browser, path)
          ok++
        } catch (error) {
          fail++
          console.warn(`[prerender-server] ${path}: ${error.message}`)
        }
      }
      const elapsed = Date.now() - startTime
      console.log(`[prerender-server] 增量完成: ${ok} 成功, ${fail} 失败 (${elapsed}ms)`)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok, fail, elapsed }))

    } else {
      res.writeHead(404)
      res.end('Not Found')
    }
  } catch (err) {
    console.error('[prerender-server] 错误:', err)
    res.writeHead(500)
    res.end(err.message)
  }
}

const server = http.createServer(handleRequest)
server.listen(PORT, () => {
  console.log(`[prerender-server] 运行在 http://localhost:${PORT}`)
  console.log(`[prerender-server] 目标 URL: ${BASE_URL}`)
  console.log(`[prerender-server] 输出目录: ${PRERENDER_DIR}`)
})
