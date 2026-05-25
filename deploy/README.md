# aiTrade 部署说明

## 目标服务器

- 默认：`root@8.137.119.18`（成都阿里云，可被环境变量 `AITRADE_SERVER` 覆盖）
- 目标目录：`/opt/aitrade/`（可被 `AITRADE_REMOTE_DIR` 覆盖）

## 架构

```
host:80 (nginx 容器)
  ├─ /              → 前端静态
  └─ /api/*         → backend-java:8080
                         └─→ http://gateway-python:8000 (容器内 DNS)
```

3 个容器：`backend-java`、`gateway-python`、`frontend`。数据库是挂载到容器的 SQLite 文件，没有 MySQL。

## 前置条件（一次性）

### 本地

- ✅ Maven（`mvn -v`）
- ✅ npm（`npm -v`）
- ✅ ssh + tar（git-bash 自带）
- ✅ SSH key 已加到服务器 `~/.ssh/authorized_keys`（验证：`ssh -o BatchMode=yes root@8.137.119.18 echo ok`）

### 服务器

- ✅ Ubuntu 22.04
- ✅ Docker + Docker Compose v2 plugin
- ✅ 2G swap
- ✅ Docker 镜像加速（`/etc/docker/daemon.json` 配国内 mirror）
- ⏳ **阿里云控制台安全组开放 80 端口入站** ← 必做，否则外网访问不到

## 部署（首次 / 更新通用）

在项目根目录 git-bash 里运行：

```bash
bash deploy/deploy.sh
```

脚本会做 5 件事：

1. `mvn package -DskipTests` 出 fat JAR
2. `npm run build` 出 `dist/`
3. 把 JAR、dist、Python 源码、配置文件、`aitrade.db`、`.sel` 自选股汇总到临时目录
4. tar over ssh 流式上传到 `/opt/aitrade/`
5. ssh 触发 `docker compose up -d --build`

完成后浏览器打开 `http://8.137.119.18/`。

## 内存预算

| 容器 | mem_limit |
|---|---|
| backend-java | 512m |
| gateway-python | 256m |
| frontend (nginx) | 64m |
| 合计 | ~832m |

服务器 955m RAM + 2G swap，预期 swap 用量 < 500m。

## 常用运维

```bash
# 看服务状态
ssh root@8.137.119.18 'cd /opt/aitrade && docker compose ps'

# 看实时日志
ssh root@8.137.119.18 'cd /opt/aitrade && docker compose logs -f --tail=100'

# 单独看某个服务的日志
ssh root@8.137.119.18 'cd /opt/aitrade && docker compose logs -f backend-java'

# 看资源占用
ssh root@8.137.119.18 'docker stats --no-stream'

# 重启某个服务
ssh root@8.137.119.18 'cd /opt/aitrade && docker compose restart backend-java'

# 停止所有
ssh root@8.137.119.18 'cd /opt/aitrade && docker compose down'

# 看 SQLite 数据库大小
ssh root@8.137.119.18 'ls -lh /opt/aitrade/backend-java/data/aitrade.db'
```

## 数据备份

```bash
# 把服务器数据库拷回本地
scp root@8.137.119.18:/opt/aitrade/backend-java/data/aitrade.db ./backup-$(date +%Y%m%d).db
```

## 故障排查

### 容器起不来

```bash
ssh root@8.137.119.18 'cd /opt/aitrade && docker compose logs <service>'
```

### Java 内存溢出（OOMKilled）

调整 `backend-java/Dockerfile` 里 `JAVA_OPTS` 的 `-Xmx`（默认 384m），或调 `docker-compose.yml` 里 `mem_limit`。

### gateway 拉行情失败

```bash
# 进 gateway 容器手动测
ssh root@8.137.119.18 'docker exec -it aitrade-gateway curl -s http://push2.eastmoney.com/api/qt/stock/get?secid=1.600519 | head -c 200'
```

### SSE 实时事件断流

检查 `frontend-vue/nginx.conf` 里 `proxy_buffering off` 是否生效。

## 不部署的东西

`.gitignore` 已排除的密钥/数据文件由 `deploy.sh` 直接打包上传，不入库：

- `backend-java/data/aitrade.db` （SQLite 库 + 用户数据）
- `gateway-python/.env` （含 GATEWAY_TOKEN）
- `gateway-python/data/` （池子运行时数据）
- `doc/*.sel`、`doc/SelfStockInfo.json` （自选股，私密）

应用层密钥 `application.yml`（gateway token + JWT secret）被打包进 fat JAR（classpath），上传随 JAR 一起。
