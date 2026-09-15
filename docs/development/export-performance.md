# 导出压缩链路与性能验证

## 实现策略

使用现有 Apache Commons Compress 1.28.0。ZIP/CBZ 内的媒体字节保持原样，默认策略如下：

| 内容 | ZIP 方法 | 压缩级别 |
|---|---|---|
| JPEG、PNG、WebP、GIF、AVIF、JXL、HEIC/HEIF 与常见编码视频 | STORED | 0（直接打包） |
| metadata.json、ComicInfo.xml、其他文件 | DEFLATED | 1（快速压缩） |

扩展名识别不区分大小写。BMP、未识别格式仍按普通文件压缩。0 级不改变图片或视频质量；在当前文件输出中，媒体使用 ZIP 的 STORED 方法，条目数据大小与原文件完全一致。

当前锁定的 Commons Compress 1.28.0 中，普通文件与 `ZipSplitOutputStream` 均使用随机访问输出，`isSeekable()` 为 true，支持写入后回填 CRC。因此 STORE 也能单次读取、跨卷流式构建，无需预扫描源文件。实现仅在输出不可随机访问时回退到 DEFLATE 0 级。该行为已通过单卷、跨卷及源文件读取次数回归测试验证；不要把旧版文档关于非随机访问分卷的限制套用到当前版本。对本项目图片、视频为主的负载，优先减少无效压缩与磁盘读写，而不是增加并行压缩线程。

继续保留 UTF-8 名称、按需 Zip64、标准 `.z01…zip` 分卷、以未压缩总量判断分卷、完整读回校验及任务目录原子发布。CBZ 分卷先在 staging 内构建标准 ZIP，再把主卷改回请求的 `.cbz` 名称，避免 Commons 关闭时固定生成 `.zip` 导致回读找不到主卷；编号卷仍是同 basename 的 `.z01…zNN`。数据量和条目数量仍受 `worker.zip` 限制。源文件和 ZIP 条目流使用 256 KiB 固定缓冲区，内存不随单个媒体大小增长；清单、校验值和 ZIP 中央目录的内存随条目数增长。

## 校验与任务生命周期

1. 收集漫画、章节、媒体、标签一次，生成元数据时复用同一批结果。
2. 新建归档每个媒体只读一次，在写入过程中记录实际长度和 CRC；检查源文件读取前后的大小、修改时间和文件标识，拒绝观察到的变化。这是文件变化检测，不是文件系统快照。
3. 完整读取新归档所有条目，对照写入时记录的 CRC、清单长度和元数据内容。拒绝额外条目、重复条目、错误长度、损坏数据及不支持的条目类型。不通过第二次读取源文件来计算相同的 CRC。
4. 同一任务重投时先验证最终目录。已有产物必须对照当前源文件独立计算 CRC，并完整读回；通过后直接复用，不创建新的归档。不一致则报冲突，保留既有最终目录。
5. 单个 Worker 的导出监听器固定一个消费者；服务内用可中断的锁避免并发导出争抢磁盘或清理相同 staging。没有额外压缩线程池。这不是跨 Worker 实例的分布式锁。
6. 流式复制与校验在每次读取前检查中断；服务停机中断不发业务失败事件，保留中断标记和未确认 MQ 消息，交由连接关闭后的重新投递恢复。这里没有新增用户侧取消导出接口，也不复用导入任务的取消 ID。
7. 构建、校验、发布失败统一清理 staging。清理失败保留异常链；旧 staging 无法清理时不继续构建。不跟随清理目录内的符号链接。

CRC 用于内容一致性和意外损坏检测，不是防恶意碰撞的密码学摘要。默认仍做完整读回，没有通过跳过校验来获得性能数据。

## 配置

| 环境变量 | Spring 配置 | 默认值 | 范围 |
|---|---|---|---|
| `ZIP_COMPRESSION_LEVEL` | `worker.zip.compression-level` | 1 | 0..9 |
| `ZIP_MEDIA_COMPRESSION_LEVEL` | `worker.zip.media-compression-level` | 0 | 0..9 |

非法级别在启动时拒绝。默认值可直接用于媒体导出，无需额外安装 7-Zip、原生压缩库或配置线程数。若特定素材确有可观的二次压缩收益，可调整级别后重启 Worker。配置不影响导入解压规则。

成功构建日志记录媒体条目数、未压缩字节数、归档字节数、卷数、`writeMs` 和 `verifyMs`；服务记录 `collectMs` 与任务 ID。可据此区分查询、压缩写入和回读阶段的耗时。

## 可复现基准

在研发分支仓库根目录执行：

```powershell
pwsh -NoProfile -File scripts/dev/run-tests.ps1 -pl worker-service -am test '-Dtest=ExportCompressionBenchmarkTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dexport.benchmark=true'
```

基准只使用 JUnit 临时目录，不读取漫画库、不访问业务接口、不发送 MQ 任务。数据包括 16 张实际由 ImageIO 编码的 1024×1024 JPEG 与 128 MiB 伪随机字节（模拟已编码视频的不可压缩负载，不是可播放视频）。可用 `-Dexport.benchmark.videoMiB=256` 调整模拟媒体大小。

对比三种链路：

- `legacy`：重现旧版默认 DEFLATE 串行写入，校验时重新读取源文件并完整读回 ZIP。为共用校验标准，该基线使用当前校验器，其缓冲区比旧版更大，不是旧提交二进制的逐字节重放。
- `level1`：新链路，全部媒体改用 1 级，写入时记录 CRC。
- `media0`：新链路默认策略，媒体 STORE、其他条目 1 级。

覆盖单卷与 32 MiB 标准分卷。每组一轮预热、三轮计时，交替顺序。计时包含归档关闭和完整验证，不包括夹具生成和删除。原始结果写入 `worker-service/target/export-benchmark.csv`；报告不提交到 Git。不要在 CI 中断言固定提速倍数，磁盘、杀毒扫描和系统缓存会影响结果。

### 本次结果

2026-09-15，Windows、JDK 21.0.2，本地临时目录，默认合成数据共 144,307,908 字节媒体。以下为三轮中位数，包含写入与完整校验：

| 布局 | 旧链路基线 | 全部媒体 1 级 | 新默认 STORE 策略 | 相对基线提速 |
|---|---:|---:|---:|---:|
| 单卷 | 4,396.172 ms | 4,216.416 ms | 170.222 ms | 25.83 倍 |
| 32 MiB 分卷 | 4,651.280 ms | 4,465.285 ms | 213.643 ms | 21.77 倍 |

单卷旧基线产物 144,347,078 字节，新策略 144,310,346 字节，大小基本相同。分卷分别多 4 字节分卷签名。数据说明本负载主要受媒体重复压缩影响，仅降低到 1 级的收益有限。这是合成负载与系统缓存参与下的本机结果，不代表真实漫画库、机械硬盘或网络盘上的固定提速倍数。

同一版本执行 `pwsh -NoProfile -File scripts/dev/run-tests.ps1 verify '-Dexport.benchmark=true'`，全仓库 817 项测试：812 项通过、5 项按条件跳过、0 失败；所有模块 Checkstyle 通过。覆盖单卷、标准分卷、CBZ 分卷、重新导入、损坏检测、源文件变化、单次读取、中断、重投复用和清理异常保留。

## 开源方案依据

- [Commons Compress ZIP 文档](https://commons.apache.org/proper/commons-compress/zip.html)：标准分卷、STORED 流式输出约束和 Zip64。
- [ParallelScatterZipCreator](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ParallelScatterZipCreator.html)：需要保留压缩时可评估的并行方案；本次没有引入中间存储或额外线程。
- [Zip4j](https://github.com/srikanth-lingala/zip4j)：另一个支持标准 ZIP 分卷的 Java 库；本次无须切换归档实现。
- [libdeflate](https://github.com/ebiggers/libdeflate#api)：不支持流式处理，未用于大媒体导出。
