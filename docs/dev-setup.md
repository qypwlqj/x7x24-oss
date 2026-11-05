# 开发环境

需要：JDK 21、Maven 3.9+、Node24.22.1+、（可选）Chrome（截图脚本）。

## 一键启动（macOS）

在 Finder 中双击项目根目录的 `start.command`，或在项目目录执行：

```bash
./start.command
```

脚本检查 Node / JDK / Maven 和端口，安装并同步前端依赖、构建前端与 Java 后端，等待服务就绪后打开 `http://localhost:8787`。每次启动都会构建当前代码，首次构建需要下载依赖。保留终端窗口运行，按 Ctrl+C 停止服务。

默认 `ADAPTER_MODE=offline`，无需 GitHub 或 LLM 密钥；离线模式可查看看板和已有数据，不会抓取 GitHub Issue 或执行真实交付。脚本沿用启动终端中已导出的环境变量，不自动读取 `.env`；令牌只通过环境变量传入。数据库默认保存在项目根目录的 `data/`，不会清空。

```bash
./start.command --check            # 仅检查环境
./start.command --no-open          # 启动后不自动打开浏览器
PORT=8788 ./start.command          # 更换前端端口（后端内部默认 18787，可通过 API_PORT 修改）
```

已在终端配置好 `GITHUB_TOKEN`、`TARGET_REPO` 等变量后，可通过 `ADAPTER_MODE=real ./start.command` 启动真实适配器（默认 dry-run）。真实写入需要额外设置 `ADAPTER_DRY_RUN=false`，配置要求见 `docs/adapters.md`。

启动脚本跳过后端测试；需要完整验证时，在根目录运行 `npm run verify:all`。

## 安装 Java（无 root 的做法）
```bash
mkdir -p ~/tools && cd ~/tools
curl -L -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk.tgz            # 解压出 jdk-21.*
curl -LO https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
tar xzf apache-maven-3.9.9-bin.tar.gz
export JAVA_HOME=$(ls -d ~/tools/jdk-21*) PATH=$JAVA_HOME/bin:~/tools/apache-maven-3.9.9/bin:$PATH
java -version && mvn -v
```
macOS：`brew install openjdk@21 maven`；Debian/Ubuntu：`sudo apt install openjdk-21-jdk maven`。

## 构建与验证
```bash
npm ci                      # 前端依赖
npm run verify              # 前端：类型检查 + vitest + 构建（产物 apps/web/.next，含 standalone 服务）
(cd apps/server-java && mvn verify)   # 后端：单元 + 集成测试，产物 target/x7x24-server.jar
```
## 运行
```bash
# 终端 1：启动 Java API（仅提供 API，默认 8787）
WEB_DIR="$PWD/no-web" ADAPTER_MODE=offline ADAPTER_DRY_RUN=true \
  java -jar apps/server-java/target/x7x24-server.jar
# 终端 2：开发模式，打开 http://localhost:5173
npm run dev:web
# 或运行构建后的生产服务（需要先 npm run build）
PORT=5173 npm run start -w @x7x24/web
```
令牌只通过环境变量传入，不要写进文件。真实写入需显式 `ADAPTER_DRY_RUN=false`。

前端为 Next.js 16 App Router + React 19；样式已使用 Tailwind CSS 4，由 `@tailwindcss/postcss` 处理。`src/app` 负责页面与布局，`src/views` 保留业务视图，Vitest 单独使用 `vitest.config.ts`。页面使用 `/admin/integrations` 等 history 地址，刷新和直达由 Next.js 路由处理，旧 hash 地址自动跳转。

`API_PROXY_TARGET` 在启动开发服务或生产构建时指定 Java 地址，默认 `http://127.0.0.1:8787`。一键启动会自动使用内部 `API_PORT`，并通过前端公开端口代理 API。`NEXT_PUBLIC_API_BASE` 留空使用同源代理；若直连独立 API，后端必须允许前端来源。`NEXT_PUBLIC_BASE_PATH` 用于子路径部署，修改后需重新构建。OAuth 的前端来源和通知链接应填写用户访问的前端地址。
