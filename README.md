# Spring Runtime Bootstrap (Native Start + DAO/Mapper Mock)

这个项目用于做两件事：

1. 按 Spring 原生方式启动目标应用（`SpringApplicationBuilder`）。
2. 在启动时自动把工程内 `*Dao` / `*Mapper`（含 MyBatis `MapperFactoryBean`）替换成无副作用 mock。
3. 自动对 `DataSource` 相关 Bean 做 mock（`beanName=dataSource`、类型或类名包含 `DataSource`）。
4. 若某个 Bean 初始化失败，会自动提取失败 Bean 名并重试，把该 Bean 替换为 no-op mock，尽可能让 Spring 继续启动。

## 1. 编译

```bash
mvn -q -Dmaven.repo.local=.m2repo -DskipTests compile
```

## 2. 运行内置 Demo（验证可启动）

内置 demo 启动类：`com.example.demo.StartApp`
（demo 中 `ExternalPaymentDao` / `PaymentMapper` 的真实实现会抛异常，若成功启动说明已被 mock 替换）

```bash
mvn -q -Dmaven.repo.local=.m2repo exec:java \
  -Dexec.mainClass=com.camelot.runtime.bootstrap.SpringRuntimeBootstrapMain \
  -Dexec.args="--startup-class=com.example.demo.StartApp --profile=test"
```

期望看到类似输出：

- `Spring context started.`
- `Profiles: [test]`
- `Mocked bean count: ...`
- `Resolved mapper-locations from Spring Environment: ...`（支持从 `application*.yml/.yaml/.properties` 发现）
- `Resolved mybatis scan packages from context: ...`（从 Spring 上下文里的 mapper scanner `basePackage` 提取）
- `[DEMO] runner finished: ...`（说明启动阶段调用了 Service，Dao/Mapper 没有触发真实外部访问）

## 3. 运行你的 Spring 工程

先在你的目标工程目录执行（确保有 `target/classes`，并准备依赖 jar）：

```bash
cd /path/to/your-project
mvn -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime
```

然后回到本项目执行，传入工程目录 + 启动类，例如：

```bash
mvn -q -Dmaven.repo.local=.m2repo exec:java \
  -Dexec.mainClass=com.camelot.runtime.bootstrap.SpringRuntimeBootstrapMain \
  -Dexec.args="--project-dir=/path/to/your-project --startup-class=com.xxx.StartApp --profile=test"
```

可选参数：

- `--profile=test,local`：覆盖默认 profile（默认是 `test`）
- `--property=key=value`：传入额外 Spring 属性
- `--keep-running`：保持上下文常驻（默认即为常驻）
- `--scan-package=com.xxx`：手工指定 mock 扫描包（不传时会从启动类注解中自动解析）
- `--force-mock-class-prefix=a.b.c,d.e.f`：类名前缀白名单（list），命中前缀即直接 mock
- `--project-dir=/path/to/project`：指定待启动工程目录（从该目录加载 `target/classes`、`target/dependency/*.jar`）

兼容说明：

- 如果启动时遇到 `ApplicationServletEnvironment`（或拼写为 `ApplicaitonServletEnvironment`）相关 `ClassNotFoundException`，启动器会自动降级到 `spring.main.web-application-type=none` 再重试一次。
