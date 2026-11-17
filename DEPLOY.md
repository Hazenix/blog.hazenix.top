# DEPLOY.md

本文记录本项目的线上部署流程，供后续 AI 或维护者快速接手。

> 安全约束：本文只写部署方法和路径，不记录服务器地址、端口、用户名、密码、Token、Cookie、数据库密码或任何真实密钥。连接信息请放在本机 SSH 配置、密码管理器或受控的运维文档中。

## 1. 项目部署形态

当前线上是 Docker Compose 部署：

- 后端：Spring Boot fat jar，容器内以 `/app.jar` 运行。
- 前端：Vue/Vite 构建后的静态文件，由 Nginx 容器挂载目录提供。
- 反向代理：Nginx 将 `/api/` 转发到后端容器，其它路径走前端静态资源和 SPA fallback。
- 缓存：Redis 容器，后端使用 Spring Cache。
- 数据库：PostgreSQL 容器。

线上常见目录约定：

```text
<deploy-root>/docker-compose.yml
<deploy-root>/blog/
  Dockerfile
  application-prod.yml
  blog-server-1.0-SNAPSHOT.jar
<deploy-root>/nginx/nginx.conf
<deploy-root>/nginx/html/blog/
```

如线上目录发生变化，先以 `docker compose config`、`docker ps`、`docker inspect` 和 Nginx 配置为准，不要盲目覆盖。

## 2. 发布前检查

### 2.1 确认 Git 状态

```bash
git status --short
git diff --name-only
```

如果工作区有无关改动，只部署本次任务涉及的文件或基于干净副本构建，避免把未确认改动带上线。

### 2.2 后端构建环境

后端需要使用 JDK 17。仓库中已有本地构建环境脚本：

```bash
source .superpowers/build-env.sh
java -version
mvn -version
```

确认 Maven 输出的 Java version 是 17 系列。若使用更高版本 JDK，Lombok/Javac 可能出现类似错误：

```text
Fatal error compiling: java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN
```

### 2.3 前端构建环境

前端依赖以 `frontend/package-lock.json` 为准：

```bash
cd frontend
npm ci
```

若本地已有可信依赖且只是快速发布，也可以直接使用现有 `node_modules`，但 CI 或新机器上优先 `npm ci`。

## 3. 本地构建

### 3.1 构建后端 jar

```bash
source .superpowers/build-env.sh
cd backend
mvn -pl blog-server -am clean package -DskipTests
```

产物位置：

```text
backend/blog-server/target/blog-server-1.0-SNAPSHOT.jar
```

### 3.2 构建前端静态资源

如果只需要 Vite 静态构建：

```bash
cd frontend
npx vite build
```

如果需要执行项目定义的完整构建流程：

```bash
cd frontend
npm run build
```

构建时使用 `PRERENDER_BASE_URL` 指定只读内容 API 的站点地址，默认使用线上博客。脚本通过本机预览服务渲染本次构建的产物，前端 API 地址使用相对路径 `/api`。如果只验证前端编译，可运行 `npx vite build`。

参照 `docs/nginx-blog.conf` 将 `/sitemap.xml` 代理到后端，启用文章发布状态检查和预渲染路由。配置中的后端地址需与实际部署一致，Nginx 需要 `http_auth_request_module`。先部署包含状态接口的后端，再启用 Nginx 规则。增量渲染服务使用正常浏览器路由获取页面，新发布文章通过 `prerender.server-url` 配置的服务生成快照。

前端产物位置：

```text
frontend/dist/
```

## 4. 推荐的安全构建方式

当本地工作区存在其它未提交改动时，建议用 Git 当前提交导出一个临时干净目录，再只覆盖本次要发布的文件：

```bash
BUILD_ROOT=/tmp/blog-deploy
rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT"
git archive HEAD | tar -x -C "$BUILD_ROOT"

# 示例：只覆盖本次任务涉及文件
cp frontend/src/components/layout/AppHeader.vue "$BUILD_ROOT/frontend/src/components/layout/AppHeader.vue"
cp frontend/src/views/CategoryList.vue "$BUILD_ROOT/frontend/src/views/CategoryList.vue"
cp backend/blog-server/src/main/resources/mapper/CategoryMapper.xml "$BUILD_ROOT/backend/blog-server/src/main/resources/mapper/CategoryMapper.xml"
cp backend/blog-server/src/main/java/top/hazenix/controller/admin/ArticleController.java "$BUILD_ROOT/backend/blog-server/src/main/java/top/hazenix/controller/admin/ArticleController.java"
```

然后在 `$BUILD_ROOT` 中执行构建。这样可以确保线上只包含本次任务改动，不夹带本地其它变更。

## 5. 上传与备份

建议把生产服务器配置成 SSH alias，例如：

```text
Host blog-prod
  HostName <managed-outside-this-file>
  User <managed-outside-this-file>
  IdentityFile <managed-outside-this-file>
```

不要把真实连接信息提交进仓库。

### 5.1 备份线上文件

```bash
ssh blog-prod 'set -e
TS=$(date +%Y%m%d-%H%M%S)
cp <deploy-root>/blog/blog-server-1.0-SNAPSHOT.jar <deploy-root>/blog/blog-server-1.0-SNAPSHOT.jar.bak.$TS
mkdir -p <deploy-root>/nginx/html/blog.bak.$TS
cp -a <deploy-root>/nginx/html/blog/. <deploy-root>/nginx/html/blog.bak.$TS/
'
```

### 5.2 上传后端 jar

```bash
scp backend/blog-server/target/blog-server-1.0-SNAPSHOT.jar \
  blog-prod:<deploy-root>/blog/blog-server-1.0-SNAPSHOT.jar
```

### 5.3 上传前端 dist

推荐先上传到临时目录，再在服务器上原子替换或 rsync 到目标目录：

```bash
ssh blog-prod 'rm -rf /tmp/blog-dist && mkdir -p /tmp/blog-dist'
scp -r frontend/dist/. blog-prod:/tmp/blog-dist/
ssh blog-prod 'rsync -a --delete /tmp/blog-dist/ <deploy-root>/nginx/html/blog/'
```

如果服务器没有 `rsync`，可以改用 `cp -a`，但要谨慎处理旧 hash 资源文件，避免旧资源残留或新资源缺失。

### 5.4 生产版本包含仓库之外的改动

2026-11-17 的 SEO 发布使用正在运行的镜像作为基底，只在原 jar 增加 `ArticleSeoOverlayController`，并为博客的 Nginx 虚拟主机新增站点地图和爬虫路由。生产镜像内包含启动所需的 `/config/application-prod.yml`；仅用本仓库的 jar 和当前 Dockerfile 重建会因缺少该路径无法启动。

爬虫可读取预渲染 HTML。新文章或更新时间与快照不匹配时，后端按当前公开状态提供正文。SEO 路由验证应覆盖 `/sitemap.xml`、旧站点地图、中文与数字文章地址、真实 `404` 和原 Vue 入口。回滚时恢复该次发布保存的旧镜像、jar 和 Nginx 配置。

## 6. 重建与重启服务

后端 jar 已经被 Dockerfile 复制进镜像，因此替换 jar 后需要重建后端镜像并重启后端容器：

```bash
ssh blog-prod 'set -e
cd <deploy-root>
docker compose build blog
docker compose up -d blog
'
```

前端静态资源是 Nginx 挂载目录，通常上传后无需重建 Nginx 镜像。若修改了 Nginx 配置，则执行：

```bash
ssh blog-prod 'set -e
nginx -t || docker exec nginx nginx -t
docker compose up -d nginx
'
```

## 7. 缓存处理

涉及分类、文章状态、文章分类归属或分类计数时，可能需要清理分类缓存。

优先通过后端已有逻辑触发缓存失效；如果必须手动清 Redis，应只清业务相关 cache key，避免误删其它数据。执行前先查看 key：

```bash
ssh blog-prod 'docker exec redis redis-cli --scan --pattern "*categoriesCache*"'
```

确认 key 后再删除。不要执行 `FLUSHALL` 或 `FLUSHDB`，除非明确知道影响范围并已人工确认。

## 8. 发布后验证

### 8.1 容器状态

```bash
ssh blog-prod 'docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"'
```

### 8.2 后端日志

```bash
ssh blog-prod 'docker logs --tail=200 blog'
```

确认没有启动失败、数据库连接失败、Redis 连接失败或 Mapper XML 解析错误。

### 8.3 接口验证

通过浏览器或本机请求验证：

```bash
curl -sS https://<site-domain>/api/user/categories
curl -sS https://<site-domain>/api/user/articles
```

分类接口应返回非空 `data`，且有文章的分类 `articleCount > 0`。

### 8.4 页面验证

至少检查：

- 首页能打开。
- 顶部“文章”下拉菜单显示分类，而不是“暂无分类”。
- `/categories` 分类页显示分类。
- 点击分类后进入文章列表并按分类筛选。
- `/api/` 相关请求无 4xx/5xx 异常。

## 9. 回滚

如果发布后异常：

### 9.1 回滚后端

```bash
ssh blog-prod 'set -e
cd <deploy-root>/blog
cp blog-server-1.0-SNAPSHOT.jar.bak.<timestamp> blog-server-1.0-SNAPSHOT.jar
cd <deploy-root>
docker compose build blog
docker compose up -d blog
'
```

### 9.2 回滚前端

```bash
ssh blog-prod 'rsync -a --delete <deploy-root>/nginx/html/blog.bak.<timestamp>/ <deploy-root>/nginx/html/blog/'
```

回滚后重复执行“发布后验证”。

## 10. 本次分类修复相关发布提示

本次分类问题的关键链路：

- 前端导航和分类页不要再通过文章列表二次统计分类数量。
- `/user/categories` 是分类名称和 `articleCount` 的权威来源。
- 后端文章新增、更新、删除、状态变化后要清理 `categoriesCache`。
- 分类计数只统计已发布文章，并排除留言板专用文章和手记分类。

如果线上仍显示“暂无分类”，优先检查：

1. `/api/user/categories` 是否返回非空数据。
2. `articleCount` 是否大于 0。
3. Redis 中是否有旧的 `categoriesCache`。
4. 前端静态资源是否确实更新，浏览器是否命中旧缓存。
5. Nginx 是否代理到当前后端容器。
