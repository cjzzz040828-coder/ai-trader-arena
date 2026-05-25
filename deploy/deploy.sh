#!/usr/bin/env bash
# aiTrade 一键部署脚本
# 用法：在项目根目录 git-bash 里运行 `bash deploy/deploy.sh`
#
# 流程：本地构建 (Maven + Vite) → 打包暂存目录 → tar over ssh → 服务器 docker compose up
#
# 前置条件：
#   - SSH key 已配到服务器 root@$SERVER
#   - 本机安装 mvn、npm、ssh、tar (git-bash 或 WSL)
#   - 服务器已装 Docker + Compose v2 + 配好镜像加速 + 开 swap

set -euo pipefail

SERVER="${AITRADE_SERVER:-root@8.137.119.18}"
REMOTE_DIR="${AITRADE_REMOTE_DIR:-/opt/aitrade}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "════════════════════════════════════════════════════════"
echo "  aiTrade 部署 → $SERVER:$REMOTE_DIR"
echo "════════════════════════════════════════════════════════"
echo

# ============= [1/5] 本地构建 Java fat JAR =============
echo "==> [1/5] 构建 Java fat JAR (mvn package)..."
if ! command -v mvn >/dev/null 2>&1; then
  echo "❌ 找不到 mvn，请装 Maven 或加入 PATH"; exit 1
fi
(cd backend-java && mvn package -DskipTests -q)
JAR_FILE=$(ls -1 backend-java/target/backend-java-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)
if [ -z "$JAR_FILE" ]; then
  echo "❌ JAR 没产出"; exit 1
fi
echo "    JAR: $JAR_FILE ($(du -h "$JAR_FILE" | cut -f1))"

# ============= [2/5] 本地构建前端 =============
# 注意：在 git-bash 里直接调 `npm run build` 会因 cmd 子壳 PATH 丢失 node 而失败。
# 改为用 node 直接调 vite/vue-tsc 的 JS 入口，绕开 .cmd 链。
echo
echo "==> [2/5] 构建前端 (node + vite)..."
if ! command -v node >/dev/null 2>&1; then
  echo "❌ 找不到 node"; exit 1
fi
if [ ! -d frontend-vue/node_modules ]; then
  echo "    ⚠️  frontend-vue/node_modules 不存在"
  echo "       请先在 cmd 里手动跑：cd frontend-vue && npm install --registry=https://registry.npmmirror.com"
  exit 1
fi
(cd frontend-vue && \
  node node_modules/vue-tsc/bin/vue-tsc.js --noEmit && \
  node node_modules/vite/bin/vite.js build)
if [ ! -d frontend-vue/dist ]; then
  echo "❌ 前端 dist 没产出"; exit 1
fi
echo "    dist: $(du -sh frontend-vue/dist | cut -f1)"

# ============= [3/5] 准备部署包 (stage 目录) =============
echo
echo "==> [3/5] 准备部署包..."
STAGE=$(mktemp -d -t aitrade-deploy-XXXXXX)
trap "rm -rf $STAGE" EXIT

# 保留原仓库布局以便 docker compose volume 路径不变
mkdir -p "$STAGE"/{backend-java/target,backend-java/data,backend-java/logs,gateway-python/data,gateway-python/logs,doc}

# backend-java
cp "$JAR_FILE"                       "$STAGE/backend-java/target/"
cp backend-java/Dockerfile           "$STAGE/backend-java/"
cp backend-java/.dockerignore        "$STAGE/backend-java/" 2>/dev/null || true
# 数据库：默认不上传，避免每次部署覆盖服务器实时数据（注册的用户、新交易记录会丢）。
# 首次部署或确实要 reset 服务器 db 时：AITRADE_PUSH_DB=1 bash deploy/deploy.sh
if [ "${AITRADE_PUSH_DB:-0}" = "1" ] && [ -f backend-java/data/aitrade.db ]; then
  cp backend-java/data/aitrade.db    "$STAGE/backend-java/data/"
  echo "    ⚠️  推送本地 aitrade.db: $(du -h backend-java/data/aitrade.db | cut -f1) — 将覆盖服务器数据库"
elif [ -f backend-java/data/aitrade.db ]; then
  echo "    ⏭️  跳过 aitrade.db（保护服务器数据；要强制覆盖请用 AITRADE_PUSH_DB=1）"
fi

# gateway-python
cp -r gateway-python/app             "$STAGE/gateway-python/"
cp gateway-python/requirements.txt   "$STAGE/gateway-python/"
cp gateway-python/Dockerfile         "$STAGE/gateway-python/"
cp gateway-python/.dockerignore      "$STAGE/gateway-python/" 2>/dev/null || true
# 密钥 .env：默认不推送（保护服务器上已有的 token / Linux 路径 patch / 用户手改的字段）。
# 首次部署或 .env 字段有重大变化时：AITRADE_PUSH_ENV=1 bash deploy/deploy.sh
if [ "${AITRADE_PUSH_ENV:-0}" = "1" ] && [ -f gateway-python/.env ]; then
  cp gateway-python/.env             "$STAGE/gateway-python/"
  # 追加 Linux 容器路径覆盖。原因：config.py 默认值基于 __file__.parent.parent.parent
  # 在 Windows 本地 = D:\...\aiTrade\doc\，在容器 (WORKDIR=/app) 解析成 /doc/，
  # 而 docker-compose 把 doc/ 挂到 /app/doc/ —— 不显式覆盖就找不到 .sel 文件。
  printf '\n# === (deploy.sh) Linux 容器路径覆盖，请勿删除 ===\n' >> "$STAGE/gateway-python/.env"
  printf 'THS_SEL_EXPORT_PATH=/app/doc/自选股.sel\n'              >> "$STAGE/gateway-python/.env"
  printf 'THS_SEL_EXTRA_PATHS=/app/doc/5.4.sel\n'                >> "$STAGE/gateway-python/.env"
  echo "    ⚠️  推送本地 .env 到服务器（含 Linux 路径 patch），覆盖现有"
elif [ -f gateway-python/.env ]; then
  echo "    ⏭️  跳过 .env（保护服务器现有配置；要强制覆盖请用 AITRADE_PUSH_ENV=1）"
fi
# 池子数据：默认不推送，避免覆盖服务器 cron 自动重建的池子快照 / 历史归档。
# 首次部署或本地有新建池子定义需要同步时：AITRADE_PUSH_GATEWAY_DATA=1 bash deploy/deploy.sh
if [ "${AITRADE_PUSH_GATEWAY_DATA:-0}" = "1" ] && [ -d gateway-python/data ] && [ "$(ls -A gateway-python/data 2>/dev/null)" ]; then
  cp -r gateway-python/data/.        "$STAGE/gateway-python/data/" 2>/dev/null || true
  echo "    ⚠️  推送本地 gateway-python/data 到服务器，覆盖现有"
elif [ -d gateway-python/data ] && [ "$(ls -A gateway-python/data 2>/dev/null)" ]; then
  echo "    ⏭️  跳过 gateway-python/data（保护服务器池子运行时；要强制覆盖请用 AITRADE_PUSH_GATEWAY_DATA=1）"
fi

# frontend
mkdir -p "$STAGE/frontend-vue"
cp -r frontend-vue/dist              "$STAGE/frontend-vue/"
cp frontend-vue/Dockerfile           "$STAGE/frontend-vue/"
cp frontend-vue/nginx.conf           "$STAGE/frontend-vue/"
cp frontend-vue/.dockerignore        "$STAGE/frontend-vue/" 2>/dev/null || true

# doc 下自选股相关
for f in doc/*.sel doc/SelfStockInfo.json; do
  [ -f "$f" ] && cp "$f" "$STAGE/doc/"
done

# 顶层 compose 文件
cp docker-compose.yml "$STAGE/"

echo "    📦 stage size: $(du -sh "$STAGE" | cut -f1)"

# ============= [4/5] 上传到服务器 (tar over ssh) =============
echo
echo "==> [4/5] 上传到服务器..."
ssh "$SERVER" "mkdir -p $REMOTE_DIR"
# tar over ssh：流式压缩 + 解压，不依赖 rsync
tar -czf - -C "$STAGE" . | ssh "$SERVER" "tar -xzf - -C $REMOTE_DIR"
echo "    ✅ 上传完成"

# ============= [5/5] 服务器构建并启动 =============
echo
echo "==> [5/5] 服务器构建并启动 (docker compose up -d --build)..."
ssh "$SERVER" "cd $REMOTE_DIR && docker compose up -d --build"

echo
echo "════════════════════════════════════════════════════════"
echo "  ✅ 部署完成"
echo "════════════════════════════════════════════════════════"
echo
echo "  浏览器访问: http://${SERVER#root@}/"
echo
echo "  查看状态:   ssh $SERVER 'cd $REMOTE_DIR && docker compose ps'"
echo "  查看日志:   ssh $SERVER 'cd $REMOTE_DIR && docker compose logs -f --tail=50'"
echo "  资源占用:   ssh $SERVER 'docker stats --no-stream'"
echo "  停止服务:   ssh $SERVER 'cd $REMOTE_DIR && docker compose down'"
echo
echo "  📌 别忘了：阿里云控制台「安全组」开放 80 端口入站规则！"
echo
