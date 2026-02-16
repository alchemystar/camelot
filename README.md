# Spring Call Path Analyzer (Static)

一个面向 Spring 工程的静态分析工具，用于从接口入口推导“可能调用路径”（保守分析，宁可多报）。

## 已实现能力

- Java 源码解析（`javaparser-core`）
- Spring 注解入口识别
  - `@RestController` / `@Controller`
  - `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`
- Bean 与注入关系识别（注解）
  - `@Component` / `@Service` / `@Repository` / `@Configuration`
  - `@Autowired` / `@Inject` / `@Resource`
  - 构造器注入（`this.field = param`）
- XML 解析（`*.xml`）
  - `<bean id class>`
  - `<alias>`
  - `<property name ref>` / 嵌套 `<ref bean="...">`
  - `<constructor-arg ref>`（已记录，当前不参与字段名调用匹配）
  - `SimpleUrlHandlerMapping` 的 `urlMap`（`<entry key value-ref>` 和 `<prop key>`）
- 调用图构建与路径搜索
  - 以入口方法为起点的递归下钻（携带类型上下文）
  - 方法级调用边（含调用来源证据 `EVIDENCE:*`）
  - Pipeline 组装推断（`builder().add(stageBean)...build().execute(...)`）
  - 继承/实现链联合分派（按当前接收者类型收敛，多个实现保守分叉）
  - callee 命中 jar 后停止继续下钻
  - 可配置 `max-depth` / `max-paths`
- 输出
  - `analysis-report.json`
  - `call-graph.dot`
  - `external-dependency-tree.txt`（仅外部依赖分支）

## 运行

JDK 要求：`Java 8+`（当前项目以 `source/target 1.8` 编译）。

```bash
mvn -q compile
mvn -q exec:java -Dexec.args="--project /path/to/your-spring-project --out /path/to/output --max-depth 50 --max-paths 200 --endpoint /users/{id}"
mvn -q exec:java -Dexec.args="--project /path/to/your-spring-project --out /path/to/output --entry-method com.example.api.UserService#findUser/1"
mvn -q exec:java -Dexec.args="--project /path/to/your-spring-project --out /path/to/output --entry-method com.example.api.UserService#findUser/1 --debug"
mvn -q exec:java -Dexec.args="--project /path/to/your-spring-project --external-rpc-prefix com.partner.api --non-external-rpc-prefix com.mycorp --non-external-rpc-prefix com.mycorp.shared"
```

参数说明：

- `--project`：被分析工程根目录，默认当前目录
- `--out`：输出目录，默认 `build/reports/spring-call-path`
- `--max-depth`：路径最大深度，默认 `50`
- `--max-paths`：每个入口最多输出路径数，默认 `200`
- `--endpoint`：仅分析某个接口路径（如 `/users/{id}`），默认分析全部接口
- `--entry-method`：仅分析某个方法入口（如 `com.foo.UserService#getById/1` 或 `UserService#getById`）
- `--debug`：输出解析调试日志（默认写到输出目录下 `analysis-debug.log`）
- `--debug-out`：指定调试日志文件路径
- `--external-rpc-prefix`：强制判定为外部 RPC 的包前缀（仅对 jar 类生效；可重复传，支持 `a,b` / `a;b` / `[a,b]` / `["a","b"]`）
- `--non-external-rpc-prefix`：强制判定为非外部 RPC 的包前缀（仅对 jar 类生效；可重复传，支持 `a,b` / `a;b` / `[a,b]` / `["a","b"]`）
- `--internal-jar-prefix`：兼容旧参数，等价于 `--non-external-rpc-prefix`

## 外部依赖识别

会对调用边标记外部依赖类型（`DB` / `CACHE` / `RPC`），并生成树状文件 `external-dependency-tree.txt`：

- 仅 `Bean` 方法调用会参与外部依赖判定（注入来源如 `@Autowired/@Resource`、XML `property-ref`，含链式 `CHAIN:`）
- `RPC`（重点）：thrift 生成类、来自 jar 的外部类（排除 `java.*` 等 JDK 标准库，且排除 `--non-external-rpc-prefix` 命中前缀）、`Feign/Rpc/Grpc/Client` 特征
- 只有 jar 中的类才会应用这两个前缀规则；工程源码类即使命中前缀，也不会据此前缀判定外部 RPC
- 前缀优先级：若同时命中两类前缀，`--non-external-rpc-prefix` 优先（视为非外部 RPC）
- `DB`：`Repository/Dao/Mapper` 注解、类名、包名等特征
- `CACHE`：`Cacheable/CachePut/CacheEvict` 注解、`cache/redis` 类名或包名特征

树结构只保留“最终命中外部依赖”的分支，纯 Java 内部处理且不触达外部依赖的分支不会展示。

## Pipeline 组装推断

支持在“创建执行流程时动态组装 Pipeline”场景下，补充推断组装后的 stage 调用边：

- 终止调用识别：`execute/run/start/process/handle/invoke/fire`
- 组装调用识别：`add/addStep/addHandler/append/then/link/register/stage/...`
- 从注入 Bean 参数（如 `validateStage`）推断 stage 实现类，再结合 Pipeline 终止方法内部的 step 调用签名（如 `apply/1`）补边
- 支持静态下钻：当组装逻辑分散在父类/子类方法中（如 `AbstractPipeline + 子类 override`），会沿调用链递归收集组装参数并合并推断结果
- 支持 `build()` 返回值追踪：从 `return` 结果反向追踪变量赋值/改写链（含 helper 方法、变量重赋值）以更精确还原组装结果
- 支持范型返回值下钻：如父类 `build()` 返回类型变量 `P`，子类以 `extends Parent<ConcretePipeline>` 绑定实参时，可继续解析 `build().execute()` 链路

调用链下钻遇到反射/动态代理/SPI/native 等不可静态精确建模场景时，会在图中补充 `STOP::*` 节点并附带 `EVIDENCE` 原因。

示例工程中新增了 `/pipeline/{id}` 入口，可用于验证该能力。

## Runtime Sandbox（极简运行）

如果你要跑“模拟执行 + 运行时调用追踪”，可以直接用脚本，只传两个核心参数：

```bash
./scripts/runtime-sandbox.sh /path/to/your-project com.foo.UserService#findUser/1
```

说明：

- 第 1 个参数：目标工程目录（会自动探测 `target/classes` 与常见 jar 依赖）
- 第 2 个参数：`entry-method`，建议写成 `全限定类名#方法名/参数个数`
- 其他参数可选追加（例如 `--arg`、`--out`、`--trace-prefix`、`--debug-runtime`）
- 若目标工程有 `pom.xml`，脚本会自动解析其 runtime 依赖并通过 `--classpath` 传给模拟器
- 若报 `ClassNotFoundException`，加 `--debug-runtime` 可打印类加载器 URL、扫描类数量、候选类名建议等排查日志

## 示例

仓库内提供了示例工程：`examples/demo-spring-app`。

运行：

```bash
mvn -q exec:java -Dexec.args="--project ./examples/demo-spring-app --out ./build/reports/demo"
```

预期可看到三类入口（含 Pipeline 示例）：

- 注解入口：`/users/{id}`
- 注解入口：`/pipeline/{id}`
- 注解入口：`/pipeline/generic/{id}`（父类 `build()` 返回范型变量场景）
- 注解入口：`/complex/pattern/{id}` 与 `/complex/pattern/chain/{id}`（Template + Pipeline + Observer + Visitor + 泛型跨类组装）
- XML 入口：`/legacy/users/{id}`

## 已知限制（当前版本）

- 未处理反射、动态代理细节、SpEL、运行时条件装配
- 对局部变量类型、重载解析、跨模块依赖解析是“启发式”而非完整类型系统
- 对内部类/匿名类参与的复杂链式返回类型推断仍有限，建议优先使用顶层类定义 Pipeline Builder
- `@Bean` 方法产出的 Bean 与复杂 AOP/事务织入尚未完整建模
