# 故障排查指南(Troubleshooting)

> 跑 `./install.sh java` 或 `./install.sh all` 时遇到问题,按本指南排查。

---

## 0. 第一时间该跑的命令

```bash
./install.sh status                    # 看哪些服务没起
tail -50 /tmp/nocobase-java.log        # Java 启动日志
docker ps                              # 看 Docker 容器
docker logs nocobase-postgres         # Postgres 日志
```

---

## Q1: `./install.sh java` 卡在"等待 Java 启动"很久

**症状:** 卡 1~4 分钟,然后报"Java 未启动"

**原因:** mvn 第一次下载依赖慢,或 Flyway migration 失败

**解决:**
```bash
# 看具体卡在哪
tail -100 /tmp/nocobase-java.log

# 如果看到 Downloading from xxx: 说明在下载依赖,等就行(阿里云镜像会快很多)
# 如果看到 FlywayException: 看错误原因,通常是 SQL 语法错
# 如果看到 Connection refused: Postgres 没起,docker ps 看下
```

**最快验证 Postgres:**
```bash
docker exec -it nocobase-postgres psql -U nocobase -d nocobase -c '\dt'
# 应该看到 users / collection_meta / migration_jobs / forms 4 张表
```

---

## Q2: admin / admin123 登录失败

**症状:** `curl /api/auth/login` 返回 401 或 500

**排查:**

```bash
# 1. 看 Java 日志,搜索 Login
grep -i "login\|bcrypt\|401" /tmp/nocobase-java.log | tail -20

# 2. 直接查数据库看 password_hash
docker exec -it nocobase-postgres psql -U nocobase -d nocobase \
  -c "SELECT username, LEFT(password_hash, 30) FROM users;"
# 应该看到 admin 用户的 hash 是 $2b$10$ZFP... 开头

# 3. 如果 hash 不对,重新生成
# 见本文档末尾"重新生成 admin bcrypt hash"章节
```

---

## Q3: Docker 启动报错

### Q3.1: `permission denied while trying to connect to the Docker daemon`

**解决:**
```bash
sudo usermod -aG docker $USER
newgrp docker
# 或重新登录
```

### Q3.2: `port is already allocated`

**症状:** 5432/6379/9000 已被占用

**解决:**
```bash
# 查找占用
sudo lsof -i :5432
sudo lsof -i :6379
sudo lsof -i :9000

# 杀掉占用进程,或改 .env 里的端口
nano .env
# 改 POSTGRES_PORT=5433 等
```

### Q3.3: `no space left on device`

**解决:**
```bash
docker system prune -a
df -h  # 看磁盘
```

---

## Q4: mvn 下载依赖非常慢(10+ 分钟)

**症状:** 卡在 `Downloading from https://repo.maven.apache.org/...`

**原因:** 没配阿里云镜像

**解决:**
```bash
# 检查 settings.xml
cat ~/.m2/settings.xml
# 应该有 aliyun 镜像

# 如果没有,跑 install.sh 会自动配,或手动:
mkdir -p ~/.m2
cat > ~/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

# 删缓存重下
rm -rf ~/.m2/repository
cd backend-java && mvn spring-boot:run
```

---

## Q5: `mvn spring-boot:run` 报 `JAVA_HOME not found`

**解决:**
```bash
# 找 JDK 路径
sudo update-alternatives --list java
# 或
readlink -f $(which java)

# 设 JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64' >> ~/.bashrc
```

---

## Q6: `pnpm install` 失败

### Q6.1: `EACCES` 权限错误

**解决:**
```bash
# 改 npm 全局目录
mkdir -p ~/.npm-global
npm config set prefix '~/.npm-global'
echo 'export PATH=~/.npm-global/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
npm install -g pnpm@9
```

### Q6.2: 下载慢

**解决:**
```bash
pnpm config set registry https://registry.npmmirror.com
```

---

## Q7: 前端 Vite 起不来

**症状:** `Cannot find module '@vitejs/plugin-react'`

**解决:**
```bash
cd frontend
rm -rf node_modules pnpm-lock.yaml
pnpm install --registry=https://registry.npmmirror.com
```

---

## Q8: `verify.sh` 跑出来一堆 FAIL

**症状:** 大部分 API 都失败

**先看:**
```bash
./install.sh status
# 看 Java / Postgres 健康状态
```

**若 Java 不通但 Postgres 通:**
```bash
tail -50 /tmp/nocobase-java.log
# 找 ERROR / Exception
```

**若都是 401:** 登录接口坏了,看 Q2

**若都是 404:** 检查 path,确认 `verify.sh` 里的路径和你的 Java 版本一致

---

## Q9: Java 报 `JWT secret 必须 ≥ 32 字节`

**症状:** 启动报 `IllegalStateException: JWT secret 必须 ≥ 32 字节`

**解决:**
```bash
nano .env
# 确保 JWT_SECRET 至少 32 字符
JWT_SECRET=dev_jwt_secret_at_least_32_characters_long_for_hs256
```

---

## Q10: Flyway migration 失败

**症状:** 启动后看到 `Migration V1__init.sql failed`

**排查:**
```bash
docker logs nocobase-postgres | tail -20
# 看是不是数据库满 / 权限不够
```

**最常见原因:**
1. Postgres 还在启动,Java 先起了 → 等几秒重启 Java
2. V1 之前有旧数据 → `docker compose down -v` 清掉重来
3. SQL 文件有语法错 → 检查 V1~V4

---

## Q11: 端口被占(8080/5173/8000)

```bash
# 查谁占
sudo lsof -i :8080

# 杀掉
kill -9 <PID>

# 或者改 .env
JAVA_PORT=8081
PYTHON_PORT=8001
```

---

## Q12: 前端 Vite 报 `EADDRINUSE :::5173`

```bash
sudo lsof -i :5173 | tail -1 | awk '{print $2}' | xargs kill -9
# 或
pkill -f "vite"
```

---

## 📞 终极排查:完整重启

```bash
# 1. 停所有
./install.sh stop

# 2. 清 Docker 数据(危险!会删数据库)
docker compose down -v

# 3. 清 mvn 缓存
rm -rf ~/.m2/repository/com/nocobase

# 4. 重新跑
./install.sh java
```

---

## 🆘 重新生成 admin bcrypt hash

如果 admin 用户的 hash 不对(V1 migration 用的是某个占位值):

```bash
# 方法 1: 用 Python 生成
python3 -c "import bcrypt; print(bcrypt.hashpw(b'admin123', bcrypt.gensalt(rounds=10)).decode())"
# 把输出替换 V1__init.sql 里的 hash

# 方法 2: 直接用 SQL 更新
docker exec -it nocobase-postgres psql -U nocobase -d nocobase -c "
UPDATE users SET password_hash = '<新 hash>' WHERE username = 'admin';
"
```

---

## 📋 常用命令速查

```bash
# 看状态
./install.sh status
./verify.sh health

# 看日志
tail -f /tmp/nocobase-java.log
tail -f /tmp/nocobase-python.log
tail -f /tmp/nocobase-frontend.log

# 重启某个服务
./install.sh stop
./install.sh java   # 只重启 Java

# 进数据库
docker exec -it nocobase-postgres psql -U nocobase -d nocobase

# 进 Redis
docker exec -it nocobase-redis redis-cli

# 完全清理重来
./install.sh stop
docker compose down -v
./install.sh java
```

---

## 🆘 还没解决?

把下面 3 样贴给 Cline:

1. `./install.sh status` 的输出
2. `tail -100 /tmp/nocobase-java.log` 的输出
3. 你具体跑的命令和报错
