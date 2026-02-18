# Spring Runtime Bootstrap (Native Start + DAO/Mapper Mock)

这个项目用于做两件事：

1. 按 Spring 原生方式启动目标应用（`SpringApplicationBuilder`）。
2. 在启动时自动把工程内 `*Dao` / `*Mapper`（含 MyBatis `MapperFactoryBean`）替换成无副作用 mock。

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
- `--keep-running`：启动后不主动关闭上下文
- `--scan-package=com.xxx`：手工指定 mock 扫描包（不传时会从启动类注解中自动解析）
- `--project-dir=/path/to/project`：指定待启动工程目录（从该目录加载 `target/classes`、`target/dependency/*.jar`）
